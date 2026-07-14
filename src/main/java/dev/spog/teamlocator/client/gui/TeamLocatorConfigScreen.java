package dev.spog.teamlocator.client.gui;

import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.config.TrustEntry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.List;
import java.util.UUID;

/**
 * Hand-rolled config screen (no cloth-config). Two tabs: the trust list for the active mode
 * (Global / This Server) with per-player hide toggles, and the block list. Global controls sit at
 * the top: active-mode cycle, the "share my coordinates" toggle, and the two HUD position sliders.
 * Any change is written to disk and pushed to the server immediately via {@link TeamLocatorClient#syncToServer()}.
 */
@Environment(EnvType.CLIENT)
public class TeamLocatorConfigScreen extends Screen {
    private enum Tab { TRUST, BLOCK }

    private final Screen parent;
    private final TeamConfig config;

    private Tab tab = Tab.TRUST;
    private int scroll;
    private EditBox nameInput;

    private static final int ROW_H = 24;
    private static final int LIST_TOP = 96;
    private static final int LIST_BOTTOM_MARGIN = 40;

    public TeamLocatorConfigScreen(Screen parent, TeamConfig config) {
        super(Component.translatable("teamlocator.config.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // --- Active-list mode cycle (Global / This Server) ---
        boolean serverAvailable = TeamConfig.currentServerKey() != null;
        CycleButton<TeamConfig.Mode> modeButton = CycleButton
                .<TeamConfig.Mode>builder(this::modeLabel, config.activeMode)
                .withValues(TeamConfig.Mode.GLOBAL, TeamConfig.Mode.SERVER)
                .create(cx - 205, 24, 200, 20, Component.translatable("teamlocator.config.active_list"),
                        (btn, value) -> {
                            config.activeMode = value;
                            config.save();
                            TeamLocatorClient.syncToServer();
                            rebuild();
                        });
        modeButton.active = serverAvailable || config.activeMode == TeamConfig.Mode.GLOBAL;
        addRenderableWidget(modeButton);

        // --- Global master toggle: stop sharing my coordinates with everyone at once ---
        addRenderableWidget(CycleButton.onOffBuilder(config.globalShareEnabled)
                .create(cx + 5, 24, 200, 20, Component.translatable("teamlocator.config.sharing_enabled"),
                        (btn, value) -> {
                            config.globalShareEnabled = value;
                            config.save();
                            TeamLocatorClient.syncToServer();
                        }));

        // --- HUD position sliders ---
        addRenderableWidget(new HudPositionSlider(cx - 205, 48, 200, 20, "HUD X", config.hudX, v -> {
            config.hudX = v;
            config.save();
        }));
        addRenderableWidget(new HudPositionSlider(cx + 5, 48, 200, 20, "HUD Y", config.hudY, v -> {
            config.hudY = v;
            config.save();
        }));

        // --- Tab selectors ---
        addRenderableWidget(Button.builder(Component.translatable("teamlocator.config.trust_lists"),
                b -> { tab = Tab.TRUST; scroll = 0; rebuild(); }).bounds(cx - 205, 72, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("teamlocator.config.block_list"),
                b -> { tab = Tab.BLOCK; scroll = 0; rebuild(); }).bounds(cx - 100, 72, 100, 20).build());

        // --- Add-player row ---
        nameInput = new EditBox(this.font, cx + 10, 72, 140, 20,
                Component.translatable("teamlocator.config.add_player"));
        nameInput.setHint(Component.translatable("teamlocator.config.add_player"));
        nameInput.setMaxLength(16);
        addRenderableWidget(nameInput);
        addRenderableWidget(Button.builder(Component.translatable("teamlocator.config.add_player"),
                b -> addTypedPlayer()).bounds(cx + 155, 72, 50, 20).build());

        // --- List rows for the current tab ---
        List<TrustEntry> entries = currentList();
        int visibleRows = Math.max(1, (this.height - LIST_TOP - LIST_BOTTOM_MARGIN) / ROW_H);
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        scroll = Math.min(scroll, maxScroll);

        for (int i = 0; i < visibleRows && (i + scroll) < entries.size(); i++) {
            TrustEntry entry = entries.get(i + scroll);
            int y = LIST_TOP + i * ROW_H;
            buildRow(cx, y, entry, entries);
        }

        // --- Done ---
        addRenderableWidget(Button.builder(Component.translatable("teamlocator.config.done"),
                b -> onClose()).bounds(cx - 100, this.height - 28, 200, 20).build());
    }

    private void buildRow(int cx, int y, TrustEntry entry, List<TrustEntry> backing) {
        if (tab == Tab.TRUST) {
            // Toggle hidden/visible for this player.
            boolean hidden = entry.hidden;
            addRenderableWidget(Button.builder(
                    Component.translatable(hidden ? "teamlocator.config.hidden" : "teamlocator.config.visible"),
                    b -> {
                        entry.hidden = !entry.hidden;
                        config.save();
                        TeamLocatorClient.syncToServer();
                        rebuild();
                    }).bounds(cx + 55, y, 90, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("teamlocator.config.remove"),
                b -> {
                    backing.remove(entry);
                    config.save();
                    TeamLocatorClient.syncToServer();
                    rebuild();
                }).bounds(cx + 150, y, 55, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        int cx = this.width / 2;
        // Section header for the list, plus a hint when the server list is unavailable.
        Component header = tab == Tab.TRUST
                ? Component.translatable(config.activeMode == TeamConfig.Mode.GLOBAL
                        ? "teamlocator.config.global_list" : "teamlocator.config.server_list")
                : Component.translatable("teamlocator.config.block_list");
        graphics.text(this.font, header, cx - 205, LIST_TOP - 10, 0xFFA0A0A0, false);

        if (tab == Tab.TRUST && config.activeMode == TeamConfig.Mode.SERVER
                && TeamConfig.currentServerKey() == null) {
            graphics.text(this.font, Component.translatable("teamlocator.config.no_server"),
                    cx - 205, LIST_TOP + 4, 0xFFFF5555, false);
            return;
        }

        // Draw a face + name label beside each row (labels aren't widgets).
        List<TrustEntry> entries = currentList();
        int visibleRows = Math.max(1, (this.height - LIST_TOP - LIST_BOTTOM_MARGIN) / ROW_H);
        for (int i = 0; i < visibleRows && (i + scroll) < entries.size(); i++) {
            TrustEntry entry = entries.get(i + scroll);
            int y = LIST_TOP + i * ROW_H;
            drawFaceAndName(graphics, cx - 205, y, entry);
        }
    }

    private void drawFaceAndName(GuiGraphicsExtractor graphics, int x, int y, TrustEntry entry) {
        UUID id = safeUuid(entry);
        PlayerSkin skin;
        String name = entry.name != null ? entry.name : (id != null ? id.toString().substring(0, 8) : "?");
        if (id != null && Minecraft.getInstance().getConnection() != null) {
            PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(id);
            skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(id);
            if (info != null) {
                name = info.getProfile().name();
            }
        } else {
            skin = DefaultPlayerSkin.getDefaultSkin();
        }
        PlayerFaceExtractor.extractRenderState(graphics, skin, x, y + 2, 16);
        graphics.text(this.font, name, x + 22, y + 6, 0xFFFFFFFF, false);
    }

    /** Resolve the typed name against the tab list and add it to the current tab's list. */
    private void addTypedPlayer() {
        if (nameInput == null) {
            return;
        }
        String typed = nameInput.getValue().trim();
        if (typed.isEmpty() || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(typed);
        if (info == null) {
            return; // unknown name — must be someone currently visible in the tab list
        }
        UUID id = info.getProfile().id();
        List<TrustEntry> list = currentList();
        boolean present = list.stream().anyMatch(e -> {
            UUID u = safeUuid(e);
            return u != null && u.equals(id);
        });
        if (!present) {
            list.add(new TrustEntry(id, info.getProfile().name()));
            config.save();
            TeamLocatorClient.syncToServer();
        }
        nameInput.setValue("");
        rebuild();
    }

    private List<TrustEntry> currentList() {
        if (tab == Tab.BLOCK) {
            return config.blocked;
        }
        TeamConfig.TrustList list = config.activeList();
        // Return a mutable list even when disconnected in SERVER mode, so add/remove callbacks that
        // slip past their guards can't throw UnsupportedOperationException.
        return list != null ? list.trusted : new java.util.ArrayList<>();
    }

    private Component modeLabel(TeamConfig.Mode mode) {
        String key = mode == TeamConfig.Mode.GLOBAL
                ? "teamlocator.config.active_list.global" : "teamlocator.config.active_list.server";
        return Component.translatable("teamlocator.config.active_list")
                .append(": ").append(Component.translatable(key));
    }

    private static UUID safeUuid(TrustEntry entry) {
        try {
            return entry.uuid();
        } catch (Exception e) {
            return null;
        }
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= LIST_TOP && mouseY <= this.height - LIST_BOTTOM_MARGIN) {
            List<TrustEntry> entries = currentList();
            int visibleRows = Math.max(1, (this.height - LIST_TOP - LIST_BOTTOM_MARGIN) / ROW_H);
            int maxScroll = Math.max(0, entries.size() - visibleRows);
            int next = scroll - (int) Math.signum(scrollY);
            next = Math.max(0, Math.min(next, maxScroll));
            if (next != scroll) {
                scroll = next;
                rebuild();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        config.save();
        this.minecraft.setScreen(parent);
    }
}
