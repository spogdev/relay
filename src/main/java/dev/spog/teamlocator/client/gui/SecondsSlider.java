package dev.spog.teamlocator.client.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * A slider that snaps to a fixed list of second values rather than sweeping a linear range, so the
 * useful settings stay reachable across a wide span: the steps are dense where small differences
 * matter (1–30s) and coarse where they don't (minutes). Displayed as "&lt;label&gt;: &lt;n&gt;s" up
 * to a minute and "&lt;label&gt;: &lt;n&gt;m" beyond it.
 *
 * <p>Reports its value live so the caller can persist it immediately.
 */
public class SecondsSlider extends AbstractSliderButton {
    /**
     * The selectable values, ascending. A linear slider over 1–600 would squeeze the low end — where
     * an alert cooldown is actually tuned — into a few pixels, so the scale is stepped instead.
     */
    public static final int[] COOLDOWN_STEPS = {
            1, 5, 10, 15, 20, 30,
            60, 120, 180, 300, 360, 420, 480, 540, 600,
    };

    /**
     * The value meaning "never expire". Chosen as a sentinel rather than a huge number so callers
     * can test for it explicitly instead of comparing against an arbitrary threshold.
     */
    public static final int INFINITE = -1;

    /**
     * Per-player ping cooldown: the alert cooldown's scale with a 0 ("None") step in front, which is
     * the default. 0 is a real value here rather than a sentinel — no gap required between pings.
     */
    public static final int[] PING_COOLDOWN_STEPS = {
            0,
            1, 5, 10, 15, 20, 30,
            60, 120, 180, 300, 360, 420, 480, 540, 600,
    };

    /**
     * Ping duration: an even 5s..600s in 5s steps, then {@link #INFINITE}. Unlike a cooldown, which
     * is tuned finely at the low end, a ping lifetime is a plain "how long does this stay up", so an
     * even scale reads more predictably than a logarithmic one.
     */
    public static final int[] PING_DURATION_STEPS = buildPingDurationSteps();

    private static int[] buildPingDurationSteps() {
        int count = 600 / 5;                 // 5,10,...,600
        int[] steps = new int[count + 1];    // plus the Infinite step on the end
        for (int i = 0; i < count; i++) {
            steps[i] = (i + 1) * 5;
        }
        steps[count] = INFINITE;
        return steps;
    }

    private final String label;
    private final int[] steps;
    private final IntConsumer onChange;

    public SecondsSlider(int x, int y, int width, int height, String label, int[] steps,
                         int initial, IntConsumer onChange) {
        super(x, y, width, height, Component.empty(), positionOf(steps, initial));
        this.label = label;
        this.steps = steps;
        this.onChange = onChange;
        updateMessage();
    }

    /**
     * The slider position (0..1) whose step is closest to {@code initial}. A saved value that is not
     * itself a step — from an older build, or a hand-edited config — snaps to the nearest one rather
     * than being rejected or silently reset to the minimum.
     */
    private static double positionOf(int[] steps, int initial) {
        int best = 0;
        if (initial == INFINITE) {
            // Match the sentinel exactly. Nearest-value matching would compare -1 numerically and
            // land on the smallest step instead, silently turning "Infinite" into "5s" on reopen.
            for (int i = 0; i < steps.length; i++) {
                if (steps[i] == INFINITE) {
                    best = i;
                    break;
                }
            }
        } else {
            for (int i = 1; i < steps.length; i++) {
                if (steps[i] == INFINITE) {
                    continue; // not a numeric candidate; only reachable by an exact match above
                }
                if (Math.abs(steps[i] - initial) < Math.abs(steps[best] - initial)) {
                    best = i;
                }
            }
        }
        return steps.length == 1 ? 0.0 : best / (double) (steps.length - 1);
    }

    private int index() {
        return (int) Math.round(value * (steps.length - 1));
    }

    private int seconds() {
        return steps[index()];
    }

    /**
     * Whole minutes read as "5m"; anything under a minute (or not a whole one) stays in seconds.
     * Mixed values past a minute read as "1m 30s" rather than "90s", which is easier to scan on a
     * 5-second scale where most steps are not whole minutes.
     */
    private static String format(int seconds) {
        if (seconds == INFINITE) {
            return "Infinite";
        }
        if (seconds == 0) {
            return "None";
        }
        if (seconds < 60) {
            return "%ds".formatted(seconds);
        }
        int minutes = seconds / 60;
        int rest = seconds % 60;
        return rest == 0 ? "%dm".formatted(minutes) : "%dm %ds".formatted(minutes, rest);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal("%s: %s".formatted(label, format(seconds()))));
    }

    @Override
    protected void applyValue() {
        onChange.accept(seconds());
    }
}
