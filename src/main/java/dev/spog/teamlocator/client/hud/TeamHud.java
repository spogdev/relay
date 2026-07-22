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
    /** Space between the name column and the coordinate block in TABLE alignment. */
    private static final int TABLE_COL_GAP = 6;

    private final TeamConfig config;

    private record Segment(String text, int color) {
    }

    /**
     * The coordinate fields of one row, kept separate from the flat {@link Segment} list so TABLE
     * alignment can lay them out as right-aligned columns. Null when coords are hidden. {@code dim}
     * is the parenthesised cross-dimension suffix, or null when the teammate shares our dimension.
     */
    private record Coords(String x, String y, String z, String dim) {
    }

    private record Row(TrackedPos entry, PlayerSkin skin, List<Segment> segments, int width,
                       List<ArmorPiece> armor, int textWidth, String name, Coords coords,
                       int pri, int sec) {
    }

    /**
     * Column widths for TABLE alignment, in unscaled pixels. {@code nameColW} reserves the widest
     * name so every coordinate block starts at the same x; {@code x/y/zColW} are each column's
     * widest value, so the numbers right-align and every row's Z ends together.
     */
    private record TableLayout(int nameColW, int xColW, int yColW, int zColW, int totalWidth) {
    }

    public TeamHud(TeamConfig config) {
        this.config = config;
    }

    /**
     * The pieces to draw for one teammate, per the armor-display setting.
     *
     * <p>LOWEST ranks by absolute durability remaining ({@code maxDamage - damage}) — the raw
     * number of hits a piece has left, regardless of what it's made of. Pieces with no durability
     * bar (a carved pumpkin) can never be "lowest" — they never break — so they are only
     * candidates when nothing else is worn, in which case there is no wear to report and the row
     * shows nothing.
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
                    if (worst == null || piece.durabilityLeft() < worst.durabilityLeft()) {
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
            String name = info.getProfile().name();
            Coords coords = null;
            segments.add(new Segment(showCoords ? name + "  " : name, pri));
            if (showCoords) {
                String xs = Integer.toString((int) Math.floor(e.x()));
                String ys = Integer.toString((int) Math.floor(e.y()));
                String zs = Integer.toString((int) Math.floor(e.z()));
                String dim = e.dimension().equals(viewerDim) ? null : prettyDimension(e.dimension());
                coords = new Coords(xs, ys, zs, dim);
                segments.add(new Segment(xs, sec));
                segments.add(new Segment(", ", pri));
                segments.add(new Segment(ys, sec));
                segments.add(new Segment(", ", pri));
                segments.add(new Segment(zs, sec));
                if (dim != null) {
                    segments.add(new Segment(" (", pri));
                    segments.add(new Segment(dim, sec));
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
            // Health sits with the armor icons: same right-hand cluster, same alignment behaviour.
            int healthWidth = config.hudShowHealth ? HealthRenderer.width(font, e) : 0;
            if (healthWidth > 0) {
                healthWidth += ARMOR_GAP;
            }
            int rowWidth = FACE_SIZE + 3 + textWidth + healthWidth + armorWidth;
            rows.add(new Row(e, info.getSkin(), segments, rowWidth, armor, textWidth, name, coords,
                    pri, sec));
            maxWidth = Math.max(maxWidth, rowWidth);
        }
        if (rows.isEmpty()) {
            return;
        }

        // TABLE alignment lays coords out in right-aligned columns; that changes each row's width,
        // so compute the layout (and the block width it implies) before anchoring/clamping.
        TableLayout table = config.hudAlign == TeamConfig.HudAlign.TABLE
                ? buildTableLayout(font, rows) : null;
        if (table != null) {
            maxWidth = table.totalWidth();
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
            // Rows always stack in list order. "Grow Upward" moves the whole block's anchor to its
            // bottom edge (see baseY above), which is the entire effect — reversing the rows here as
            // well double-applied it, leaving the block correctly placed but its contents upside
            // down, so a teammate changed position on screen just from toggling the option.
            int y = i * (ROW_HEIGHT + GAP);
            // Center the face and the text against the same vertical band so the head sits inline
            // with the name instead of riding high above it. The head is nudged 1px up from true
            // center, which reads better against the font baseline.
            int contentHeight = Math.max(FACE_SIZE, mc.font.lineHeight);
            int textY = y + (contentHeight - mc.font.lineHeight) / 2;

            if (table != null) {
                renderTableRow(graphics, mc.font, row, table, y, textY, contentHeight);
                continue;
            }

            // Align the whole row unit (head + text) within the widest row's width.
            int rowX = switch (config.hudAlign) {
                case CENTER -> (maxWidth - row.width()) / 2;
                case RIGHT -> maxWidth - row.width();
                default -> 0;
            };
            PlayerFaceExtractor.extractRenderState(graphics, row.skin(), rowX,
                    y + (contentHeight - FACE_SIZE) / 2 - 1, FACE_SIZE);
            int x = rowX + FACE_SIZE + 3;
            for (Segment seg : row.segments()) {
                graphics.text(mc.font, seg.text(), x, textY, seg.color(), true);
                x += mc.font.width(seg.text());
            }
            // Health draws whether or not armor does: a teammate may share one and not the other.
            if (config.hudShowHealth && HealthRenderer.has(row.entry())) {
                x += ARMOR_GAP + HealthRenderer.render(graphics, mc.font, row.entry(),
                        x + ARMOR_GAP, y, contentHeight, row.sec());
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

    /**
     * Measure the TABLE columns across every row. Rows with coords hidden ({@code coords == null})
     * contribute only their name to the name column. The total width is the face, the name column,
     * the gap, the three right-aligned coordinate columns with their separators, and the widest
     * armor — enough that anchoring/clamping reserves space for the whole grid.
     */
    private TableLayout buildTableLayout(Font font, List<Row> rows) {
        int sepW = font.width(", ");
        int nameColW = 0;
        int xColW = 0;
        int yColW = 0;
        int zColW = 0;
        int maxArmorW = 0;
        boolean anyCoords = false;
        for (Row row : rows) {
            nameColW = Math.max(nameColW, font.width(row.name()));
            Coords c = row.coords();
            if (c != null) {
                anyCoords = true;
                xColW = Math.max(xColW, font.width(c.x()));
                yColW = Math.max(yColW, font.width(c.y()));
                zColW = Math.max(zColW, font.width(c.z()));
            }
            if (!row.armor().isEmpty()) {
                maxArmorW = Math.max(maxArmorW,
                        ARMOR_GAP + ArmorRenderer.width(row.armor(), ARMOR_ICON_SCALE)
                                + (config.hudShowHealth
                                        ? ARMOR_GAP + HealthRenderer.width(font, row.entry()) : 0));
            }
        }
        int total = FACE_SIZE + 3 + nameColW;
        if (anyCoords) {
            total += TABLE_COL_GAP + xColW + sepW + yColW + sepW + zColW;
        }
        total += maxArmorW;
        return new TableLayout(nameColW, xColW, yColW, zColW, total);
    }

    /**
     * Render one row in TABLE alignment: face, then the left-aligned name, then X/Y/Z each
     * right-aligned within its column so the numbers line up and the row ends at a shared x. A
     * cross-dimension suffix (rare, and per-row) trails after Z; armor follows that.
     */
    private void renderTableRow(GuiGraphicsExtractor graphics, Font font, Row row, TableLayout t,
                                int y, int textY, int contentHeight) {
        int sepW = font.width(", ");
        PlayerFaceExtractor.extractRenderState(graphics, row.skin(), 0,
                y + (contentHeight - FACE_SIZE) / 2 - 1, FACE_SIZE);
        int nameX = FACE_SIZE + 3;
        graphics.text(font, row.name(), nameX, textY, row.pri(), true);

        Coords c = row.coords();
        if (c == null) {
            return; // coords hidden: name-only row, nothing more to lay out
        }
        int blockX = nameX + t.nameColW() + TABLE_COL_GAP;
        // Each value right-aligned within its column, separators drawn at the column edges in the
        // primary colour, matching the packed layout's "x, y, z".
        int xRight = blockX + t.xColW();
        graphics.text(font, c.x(), xRight - font.width(c.x()), textY, row.sec(), true);
        graphics.text(font, ", ", xRight, textY, row.pri(), true);
        int yLeft = xRight + sepW;
        int yRight = yLeft + t.yColW();
        graphics.text(font, c.y(), yRight - font.width(c.y()), textY, row.sec(), true);
        graphics.text(font, ", ", yRight, textY, row.pri(), true);
        int zLeft = yRight + sepW;
        int zRight = zLeft + t.zColW();
        graphics.text(font, c.z(), zRight - font.width(c.z()), textY, row.sec(), true);

        int end = zRight;
        if (c.dim() != null) {
            graphics.text(font, " (", end, textY, row.pri(), true);
            end += font.width(" (");
            graphics.text(font, c.dim(), end, textY, row.sec(), true);
            end += font.width(c.dim());
            graphics.text(font, ")", end, textY, row.pri(), true);
            end += font.width(")");
        }
        // Health is drawn independently of armor: a teammate sharing health but no armor must
        // still show it, so this sits outside the armor guard below.
        if (config.hudShowHealth && HealthRenderer.has(row.entry())) {
            end += ARMOR_GAP + HealthRenderer.render(graphics, font, row.entry(),
                    end + ARMOR_GAP, y, contentHeight, row.sec());
        }
        if (!row.armor().isEmpty()) {
            ArmorRenderer.render(graphics, row.armor(), end + ARMOR_GAP,
                    y + contentHeight / 2 - 1, ARMOR_ICON_SCALE);
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
