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
    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_ATTACKED = 0xFFFF5555;
    private static final int EDGE_MARGIN = 2;

    private final TeamConfig config;

    private record Row(TrackedPos entry, PlayerSkin skin, String text, int color) {
    }

    public TeamHud(TeamConfig config) {
        this.config = config;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.options.hideGui) {
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
        for (TrackedPos e : entries) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(e.id());
            if (info == null) {
                continue;
            }
            String text = "%s  %d, %d, %d".formatted(
                    info.getProfile().name(),
                    (int) Math.floor(e.x()), (int) Math.floor(e.y()), (int) Math.floor(e.z()));
            if (!e.dimension().equals(viewerDim)) {
                text += " (" + prettyDimension(e.dimension()) + ")";
            }
            int color = ClientState.isUnderAttack(e.id()) ? COLOR_ATTACKED : COLOR_NORMAL;
            rows.add(new Row(e, info.getSkin(), text, color));
            maxWidth = Math.max(maxWidth, FACE_SIZE + 3 + font.width(text));
        }
        if (rows.isEmpty()) {
            return;
        }

        // Clamp the anchor so every row stays fully on screen even at extreme slider values.
        int totalHeight = rows.size() * (ROW_HEIGHT + GAP) - GAP;
        int baseX = (int) Math.round(config.hudX * graphics.guiWidth());
        int baseY = (int) Math.round(config.hudY * graphics.guiHeight());
        baseX = Math.max(EDGE_MARGIN, Math.min(baseX, graphics.guiWidth() - maxWidth - EDGE_MARGIN));
        baseY = Math.max(EDGE_MARGIN, Math.min(baseY, graphics.guiHeight() - totalHeight - EDGE_MARGIN));

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = baseY + i * (ROW_HEIGHT + GAP);
            PlayerFaceExtractor.extractRenderState(graphics, row.skin(), baseX, y, FACE_SIZE);
            int textY = y + (FACE_SIZE - mc.font.lineHeight / 2) / 2;
            graphics.text(mc.font, row.text(), baseX + FACE_SIZE + 3, textY, row.color(), true);
        }
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
