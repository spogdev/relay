package dev.spog.teamlocator.client.hud;

import dev.spog.teamlocator.client.ArmorPiece;
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

    private static final int FACE_SIZE = 10;
    private static final int ROW_HEIGHT = 11;
    private static final int GAP = 2;
    private static final int COLOR_ATTACKED = 0xFFFF5555;
    private static final int EDGE_MARGIN = 2;
    /** Shrinks the 16px item icon to sit inside a row without inflating it. */
    private static final float ARMOR_ICON_SCALE = 0.75f; // 16 * 0.75 = 12px, slightly over FACE_SIZE
    /** Space between the row text and the first armor icon. */
    private static final int ARMOR_GAP = 3;

    private final TeamConfig config;

    private record Segment(String text, int color) {
    }

    private record Row(TrackedPos entry, PlayerSkin skin, List<Segment> segments, int width,
                       List<ArmorPiece> armor, int textWidth) {
    }

    public TeamHud(TeamConfig config) {
        this.config = config;
    }

    /**
     * The pieces to draw for one teammate, per the armor-display setting.
     *
     * <p>LOWEST ranks by remaining fraction rather than absolute damage: 50 durability left is
     * nearly-dead leather but barely-scratched netherite, and the point of the setting is to
     * surface the piece actually about to break. Pieces with no durability bar (a carved pumpkin)
     * can never be "lowest" — they never break — so they are only candidates when nothing else is
     * worn, in which case there is no wear to report and the row shows nothing.
     */
    private List<ArmorPiece> displayedArmor(List<ArmorPiece> shared) {
        if (shared.isEmpty()) {
            return List.of();
        }
        return switch (config.hudArmorDisplay) {
            case OFF -> List.of();
            case ALL -> shared;
            case LOWEST -> {
                ArmorPiece worst = null;
                for (ArmorPiece piece : shared) {
                    if (!piece.hasDurability()) {
                        continue;
                    }
                    if (worst == null || piece.durabilityFraction() < worst.durabilityFraction()) {
                        worst = piece;
                    }
                }
                yield worst == null ? List.of() : List.of(worst);
            }
        };
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
            // Trailing spaces only pad toward what follows; without coords the name would
            // otherwise carry a dangling gap before the armor icons.
            boolean showCoords = config.hudShowCoords;
            segments.add(new Segment(showCoords ? info.getProfile().name() + "  "
                    : info.getProfile().name(), pri));
            if (showCoords) {
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
            }
            int textWidth = 0;
            for (Segment seg : segments) {
                textWidth += font.width(seg.text());
            }
            // Only what the teammate actually shares; an empty list costs no width.
            List<ArmorPiece> armor = displayedArmor(e.armor());
            int armorWidth = armor.isEmpty()
                    ? 0 : ARMOR_GAP + ArmorRenderer.width(armor, ARMOR_ICON_SCALE);
            int rowWidth = FACE_SIZE + 3 + textWidth + armorWidth;
            rows.add(new Row(e, info.getSkin(), segments, rowWidth, armor, textWidth));
            maxWidth = Math.max(maxWidth, rowWidth);
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
        if (config.hudGrowUp) {
            // The anchor is the block's bottom edge: the list expands upward as teammates join.
            baseY -= scaledHeight;
        }
        baseX = Math.max(EDGE_MARGIN, Math.min(baseX, graphics.guiWidth() - scaledWidth - EDGE_MARGIN));
        baseY = Math.max(EDGE_MARGIN, Math.min(baseY, graphics.guiHeight() - scaledHeight - EDGE_MARGIN));

        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(baseX, baseY);
        pose.scale(scale, scale);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            // When growing upward, row 0 sits at the bottom of the block (closest to the anchor).
            int slot = config.hudGrowUp ? rows.size() - 1 - i : i;
            int y = slot * (ROW_HEIGHT + GAP);
            // Align the whole row unit (head + text) within the widest row's width.
            int rowX = switch (config.hudAlign) {
                case CENTER -> (maxWidth - row.width()) / 2;
                case RIGHT -> maxWidth - row.width();
                default -> 0;
            };
            // Center the face and the text against the same vertical band so the head sits inline
            // with the name instead of riding high above it. The head is nudged 1px up from true
            // center, which reads better against the font baseline.
            int contentHeight = Math.max(FACE_SIZE, mc.font.lineHeight);
            PlayerFaceExtractor.extractRenderState(graphics, row.skin(), rowX,
                    y + (contentHeight - FACE_SIZE) / 2 - 1, FACE_SIZE);
            int textY = y + (contentHeight - mc.font.lineHeight) / 2;
            int x = rowX + FACE_SIZE + 3;
            for (Segment seg : row.segments()) {
                graphics.text(mc.font, seg.text(), x, textY, seg.color(), true);
                x += mc.font.width(seg.text());
            }
            if (!row.armor().isEmpty()) {
                // Centered on the same band as the head and text, nudged 1px up like the head —
                // it reads better against the font baseline.
                ArmorRenderer.render(graphics, row.armor(), x + ARMOR_GAP,
                        y + contentHeight / 2 - 1, ARMOR_ICON_SCALE);
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
