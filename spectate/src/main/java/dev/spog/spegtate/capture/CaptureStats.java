package dev.spog.spegtate.capture;

import dev.spog.spegtate.SpectateClient;
import net.minecraft.network.packet.Packet;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts what a capture puts on the wire, and says so when it spikes.
 *
 * <p>Exists because "it lags sometimes" is not something that can be optimised. Twice now the
 * obvious culprit has been wrong -- the "weird delay" turned out to be a rate limiter counting
 * frames handed off rather than frames sent, and a later slowdown may simply have been the
 * target's server. Guessing at particles and entity updates without measuring risks removing
 * something that was never the problem while the real cause stays.
 *
 * <p>So: count every forwarded frame by packet kind and by bytes, once per second, and log only
 * when a second breaches a threshold. A quiet session therefore logs nothing at all, and a
 * session that stutters leaves one line naming the three kinds responsible.
 *
 * <p><b>Cost.</b> Two increments and a map lookup per forwarded packet, on the Netty thread that
 * was already encoding it. The map is per-kind, not per-packet, and the summary allocates only
 * on a second that actually breaches. This is cheap enough to leave in permanently, which is the
 * point -- a counter that has to be enabled is never on when the problem happens.
 */
public final class CaptureStats {
    /**
     * Log a summary when a single second carries more than this many bytes.
     *
     * <p>Roughly 2 MB/s. Chosen as "clearly more than a steady stream should need": terrain
     * arrives in bursts when the target moves, and a burst is not a fault, so the threshold sits
     * well above normal movement to avoid logging noise during ordinary play.
     */
    private static final long BYTES_PER_SECOND_WARN = 2 * 1024 * 1024;

    /** Log a summary when a single second carries more than this many frames. */
    private static final int FRAMES_PER_SECOND_WARN = 2000;

    /** How many packet kinds to name in a summary. */
    private static final int TOP_KINDS = 3;

    private final Map<String, int[]> byKind = new HashMap<>();
    private long windowStartMs = System.currentTimeMillis();
    private long bytesThisWindow;
    private int framesThisWindow;

    /** Set while a window has breached, so the same second is not reported twice. */
    private boolean reported;

    /**
     * Record one forwarded frame.
     *
     * <p>Called on the Netty event loop, from the same place that encodes the packet, and is not
     * synchronized: a capture has one inbound pipeline, so this is single-threaded in practice.
     * A miscount under some future concurrency would cost an inaccurate log line and nothing
     * else, which is not worth a lock on this path.
     */
    public void record(Packet<?> packet, int bytes) {
        bytesThisWindow += bytes;
        framesThisWindow++;
        int[] kind = byKind.computeIfAbsent(packet.getClass().getSimpleName(), k -> new int[2]);
        kind[0]++;
        kind[1] += bytes;

        long now = System.currentTimeMillis();
        if (now - windowStartMs < 1000L) {
            if (!reported && breached()) {
                report();
                reported = true;
            }
            return;
        }
        // A window that breached has already been logged; this just starts the next one.
        windowStartMs = now;
        bytesThisWindow = 0;
        framesThisWindow = 0;
        reported = false;
        byKind.clear();
    }

    private boolean breached() {
        return bytesThisWindow > BYTES_PER_SECOND_WARN
                || framesThisWindow > FRAMES_PER_SECOND_WARN;
    }

    /**
     * One line naming the heaviest packet kinds.
     *
     * <p>Ranked by bytes rather than count, because the question being answered is what is
     * filling the wire: a thousand tiny movement packets matter less than a few chunks, and the
     * previous guesses at the cause went wrong by reasoning about counts.
     */
    private void report() {
        List<Map.Entry<String, int[]>> top = new java.util.ArrayList<>(byKind.entrySet());
        top.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[1])
                .reversed());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(TOP_KINDS, top.size()); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Map.Entry<String, int[]> e = top.get(i);
            sb.append(e.getKey()).append(" x").append(e.getValue()[0])
                    .append(" (").append(e.getValue()[1] / 1024).append("KiB)");
        }
        SpectateClient.LOGGER.warn("spectate capture: heavy second -- {} frames, {}KiB; top: {}",
                framesThisWindow, bytesThisWindow / 1024, sb);
    }
}
