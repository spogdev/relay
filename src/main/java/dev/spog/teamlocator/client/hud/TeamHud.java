package dev.spog.teamlocator.client.hud;

import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TrackedPos;
import dev.spog.teamlocator.client.config.TeamConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws one row per tracked player: {@code <face> <name> <x>, <y>, <z>}, with the dimension
 * appended (in parentheses, prettified) only when that player is in a different dimension than the
 * local viewer. A player flashes red while they are signalling that they are under attack.
 */
@Environment(EnvType.CLIENT)
public class TeamHud implements HudElement {
    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(dev.spog.teamlocator.TeamLocatorConstants.MOD_ID, "hud");

    private static final int FACE_SIZE = 8;
    private static final int ROW_HEIGHT = 10;
    private static final int GAP = 2;
    private static final int COLOR_ATTACKED = 0xFFFF5555;
    private static final int EDGE_MARGIN = 2;

    private final TeamConfig config;

    private record Segment(String text, int color) {
    }

    private record Row(TrackedPos entry, PlayerSkin skin, List<Segment> segments) {
    }

    public TeamHud(TeamConfig config) {
        this.config = config;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!config.hudEnabled || mc.player == null || mc.getConnection() == null
                || mc.options.hideGui) {
            return;
        }
        List<TrackedPos> entries = ClientState.latest();
        if (entries.isEmpty()) {
            return;
        }

        Font font = mc.font;
        Identifier viewerDim = mc.player.level().dimension().identifier();

        // First pass: build the rows so we know their widths before placing anything. A player
        // with no PlayerInfo has left this server — drop the row immediately instead of showing a
        // UUID until the position entry expires.
        List<Row> rows = new ArrayList<>(entries.size());
        int maxWidth = 0;
        int primary = config.hudPrimaryArgb();
        int secondary = config.hudSecondaryArgb();
        for (TrackedPos e : entries) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(e.id());
            if (info == null) {
                continue;
            }
            boolean attacked = ClientState.isUnderAttack(e.id());
            int pri = attacked ? COLOR_ATTACKED : primary;
            int sec = attacked ? COLOR_ATTACKED : secondary;
            List<Segment> segments = new ArrayList<>();
            segments.add(new Segment(info.getProfile().name() + "  ", pri));
            segments.add(new Segment(Integer.toString((int) Math.floor(e.x())), sec));
            segments.add(new Segment(", ", pri));
            segments.add(new Segment(Integer.toString((int) Math.floor(e.y())), sec));
            segments.add(new Segment(", ", pri));
            segments.add(new Segment(Integer.toString((int) Math.floor(e.z())), sec));
            if (!e.dimension().equals(viewerDim)) {
                segments.add(new Segment(" (", pri));
                segments.add(new Segment(prettyDimension(e.dimension()), sec));
                segments.add(new Segment(")", pri));
            }
            rows.add(new Row(e, info.getSkin(), segments));
            int textWidth = 0;
            for (Segment seg : segments) {
                textWidth += font.width(seg.text());
            }
            maxWidth = Math.max(maxWidth, FACE_SIZE + 3 + textWidth);
        }
        if (rows.isEmpty()) {
            return;
        }

        // Clamp the anchor so every row stays fully on screen even at extreme slider values,
        // measuring in post-scale pixels since the whole block is drawn under a scale transform.
        float scale = (float) config.hudScale;
        int totalHeight = rows.size() * (ROW_HEIGHT + GAP) - GAP;
        int scaledWidth = Math.round(maxWidth * scale);
        int scaledHeight = Math.round(totalHeight * scale);
        int baseX = (int) Math.round(config.hudX * graphics.guiWidth());
        int baseY = (int) Math.round(config.hudY * graphics.guiHeight());
        baseX = Math.max(EDGE_MARGIN, Math.min(baseX, graphics.guiWidth() - scaledWidth - EDGE_MARGIN));
        baseY = Math.max(EDGE_MARGIN, Math.min(baseY, graphics.guiHeight() - scaledHeight - EDGE_MARGIN));

        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(baseX, baseY);
        pose.scale(scale, scale);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = i * (ROW_HEIGHT + GAP);
            PlayerFaceExtractor.extractRenderState(graphics, row.skin(), 0, y, FACE_SIZE);
            int textY = y + (FACE_SIZE - mc.font.lineHeight / 2) / 2;
            int x = FACE_SIZE + 3;
            for (Segment seg : row.segments()) {
                graphics.text(mc.font, seg.text(), x, textY, seg.color(), true);
                x += mc.font.width(seg.text());
            }
        }
        pose.popMatrix();
    }

    /** {@code minecraft:the_nether} -> "Nether"; unknown ids get their path title-cased. */
    static String prettyDimension(Identifier dim) {
        return switch (dim.toString()) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> {
                StringBuilder out = new StringBuilder();
                for (String word : dim.getPath().split("[_/]")) {
                    if (word.isEmpty() || word.equals("the")) {
                        continue;
                    }
                    if (!out.isEmpty()) {
                        out.append(' ');
                    }
                    out.append(Character.toUpperCase(word.charAt(0)))
                       .append(word.substring(1).toLowerCase(Locale.ROOT));
                }
                yield out.isEmpty() ? dim.getPath() : out.toString();
            }
        };
    }
}
