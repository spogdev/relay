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
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Draws one row per tracked player: {@code <face> <name> <x>, <y>, <z>}, with the dimension
 * appended only when that player is in a different dimension than the local viewer. A player
 * flashes red while they are signalling that they are under attack.
 */
@Environment(EnvType.CLIENT)
public class TeamHud implements HudElement {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("teamlocator", "hud");

    private static final int FACE_SIZE = 8;
    private static final int ROW_HEIGHT = 10;
    private static final int GAP = 2;
    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_ATTACKED = 0xFFFF5555;

    private final TeamConfig config;

    public TeamHud(TeamConfig config) {
        this.config = config;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.options.hideGui) {
            return;
        }
        var entries = ClientState.latest();
        if (entries.isEmpty()) {
            return;
        }

        Font font = mc.font;
        Identifier viewerDim = mc.player.level().dimension().identifier();
        int baseX = (int) Math.round(config.hudX * graphics.guiWidth());
        int baseY = (int) Math.round(config.hudY * graphics.guiHeight());

        int row = 0;
        for (TrackedPos e : entries) {
            int y = baseY + row * (ROW_HEIGHT + GAP);

            // Face from the tab-list skin, falling back to a default skin when the player isn't
            // in the client's player info (e.g. filtered tab list on some proxies).
            PlayerInfo info = mc.getConnection().getPlayerInfo(e.id());
            PlayerSkin skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(e.id());
            PlayerFaceExtractor.extractRenderState(graphics, skin, baseX, y, FACE_SIZE);

            int color = ClientState.isUnderAttack(e.id()) ? COLOR_ATTACKED : COLOR_NORMAL;
            String name = info != null ? info.getProfile().name() : shortId(e.id());
            String coords = "%s  %d, %d, %d".formatted(
                    name, (int) Math.floor(e.x()), (int) Math.floor(e.y()), (int) Math.floor(e.z()));
            if (!e.dimension().equals(viewerDim)) {
                coords += " [" + e.dimension().getPath() + "]";
            }

            int textX = baseX + FACE_SIZE + 3;
            int textY = y + (FACE_SIZE - font.lineHeight / 2) / 2;
            graphics.text(font, coords, textX, textY, color, true);
            row++;
        }
    }

    private static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}
