package dev.spog.teamlocator.client.gui;

import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.config.TrustEntry;
import dev.spog.teamlocator.client.net.NameLookup;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.List;
import java.util.UUID;

/**
 * Hand-rolled config screen (no cloth-config). Grouped into HUD, Pings, and Trust List sections,
 * with the trust list at the bottom: the trust list shows the active mode (Global / This Server)
 * with per-player hide and mute-pings toggles.
 * Any change is written to disk and pushed to the relay immediately via
 * {@link TeamLocatorClient#syncToServer()}.
 */
@Environment(EnvType.CLIENT)
public class TeamLocatorConfigScreen extends Screen {
    private final Screen parent;
    private final TeamConfig config;

    private static final String SCHEME_LABEL = "wss://";
    private static final Identifier TEXT_FIELD_SPRITE =
            Identifier.parse("minecraft:widget/text_field");
    private static final Identifier TEXT_FIELD_HIGHLIGHTED_SPRITE =
            Identifier.parse("minecraft:widget/text_field_highlighted");

    private int scroll;
    private EditBox nameInput;
    private EditBox relayUrlInput;
    private EditBox primaryColorInput;
    private EditBox secondaryColorInput;
    /** URL as it was when the screen opened, to detect a change on close and reconnect. */
    private final String initialRelayUrl;
    /** Feedback for an in-flight or failed Mojang name lookup; null when idle. */
    private Component addStatus;
    private int addStatusColor;

    private static final int ROW_H = 24;
    private static final int LIST_TOP = 216;
    private static final int LIST_BOTTOM_MARGIN = 40;

    public TeamLocatorConfigScreen(Screen parent, TeamConfig config) {
        super(Component.translatable("relay.config.title"));
        this.parent = parent;
        this.config = config;
        this.initialRelayUrl = config.relayUrl;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // --- Location section: global master toggle — stop sharing my coordinates with everyone at once ---
        addRenderableWidget(CycleButton.onOffBuilder(config.globalShareEnabled)
                .create(cx - 205, 36, 200, 20, Component.translatable("relay.config.sharing_enabled"),
                        (btn, value) -> {
                            config.globalShareEnabled = value;
                            config.save();
                            TeamLocatorClient.syncToServer();
                        }));

        // --- HUD section: position sliders side by side, size slider below ---
        addRenderableWidget(new HudPositionSlider(cx - 205, 80, 200, 20, "HUD X", config.hudX, v -> {
            config.hudX = v;
            config.save();
        }));
        addRenderableWidget(new HudPositionSlider(cx + 5, 80, 200, 20, "HUD Y", config.hudY, v -> {
            config.hudY = v;
            config.save();
        }));
        addRenderableWidget(new HudPositionSlider(cx - 205, 104, 200, 20, "HUD Size", 0.5, 2.0,
                config.hudScale, v -> {
            config.hudScale = v;
            config.save();
        }));

        // --- HUD text colors: hex inputs with live swatches (drawn in extractRenderState) ---
        primaryColorInput = new EditBox(this.font, cx + 5, 104, 70, 20,
                Component.translatable("relay.config.hud_primary_color"));
        primaryColorInput.setTooltip(Tooltip.create(
                Component.translatable("relay.config.hud_primary_color")));
        primaryColorInput.setMaxLength(7);
        primaryColorInput.setValue(config.hudPrimaryColor);
        primaryColorInput.setResponder(value -> {
            if (TeamConfig.isValidHex(value)) {
                config.hudPrimaryColor = value;
                config.save();
                primaryColorInput.setTextColor(0xFFFFFFFF);
            } else {
                primaryColorInput.setTextColor(0xFFFF5555);
            }
        });
        addRenderableWidget(primaryColorInput);
        secondaryColorInput = new EditBox(this.font, cx + 107, 104, 70, 20,
                Component.translatable("relay.config.hud_secondary_color"));
        secondaryColorInput.setTooltip(Tooltip.create(
                Component.translatable("relay.config.hud_secondary_color")));
        secondaryColorInput.setMaxLength(7);
        secondaryColorInput.setValue(config.hudSecondaryColor);
        secondaryColorInput.setResponder(value -> {
            if (TeamConfig.isValidHex(value)) {
                config.hudSecondaryColor = value;
                config.save();
                secondaryColorInput.setTextColor(0xFFFFFFFF);
            } else {
                secondaryColorInput.setTextColor(0xFFFF5555);
            }
        });
        addRenderableWidget(secondaryColorInput);

        // --- Pings section: cross-server pings toggle | ping display cooldown ---
        addRenderableWidget(CycleButton.onOffBuilder(config.crossServerPings)
                .create(cx - 205, 148, 200, 20, Component.translatable("relay.config.cross_server_pings"),
                        (btn, value) -> {
                            config.crossServerPings = value;
                            config.save();
                        }));
        addRenderableWidget(new SecondsSlider(cx + 5, 148, 200, 20, "Alert Cooldown", 0, 60,
                config.pingCooldownSeconds, v -> {
            config.pingCooldownSeconds = v;
            config.save();
        }));

        // --- Trust List section: active-list mode cycle (Global / This Server) ---
        // With no server there is no server list to edit: pin the mode to GLOBAL and grey the
        // button out entirely instead of letting it cycle into an unusable state.
        boolean serverAvailable = TeamConfig.currentServerKey() != null;
        if (!serverAvailable && config.activeMode == TeamConfig.Mode.SERVER) {
            config.activeMode = TeamConfig.Mode.GLOBAL;
            config.save();
        }
        CycleButton<TeamConfig.Mode> modeButton = CycleButton
                .<TeamConfig.Mode>builder(this::modeLabel, config.activeMode)
                .withValues(TeamConfig.Mode.GLOBAL, TeamConfig.Mode.SERVER)
                .create(cx - 205, 192, 200, 20, Component.translatable("relay.config.active_list"),
                        (btn, value) -> {
                            config.activeMode = value;
                            config.save();
                            TeamLocatorClient.syncToServer();
                            rebuild();
                        });
        modeButton.active = serverAvailable;
        addRenderableWidget(modeButton);

        // --- Add-player controls beside the mode button: name box | 5 gap | Add ---
        nameInput = new EditBox(this.font, cx + 5, 192, 130, 20,
                Component.translatable("relay.config.add_player"));
        nameInput.setHint(Component.translatable("relay.config.add_player"));
        nameInput.setMaxLength(16);
        addRenderableWidget(nameInput);
        addRenderableWidget(Button.builder(Component.translatable("relay.config.add"),
                b -> addTypedPlayer()).bounds(cx + 140, 192, 65, 20).build());

        // --- List rows ---
        List<TrustEntry> entries = currentList();
        int visibleRows = Math.max(1, (this.height - LIST_TOP - LIST_BOTTOM_MARGIN) / ROW_H);
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        scroll = Math.min(scroll, maxScroll);

        for (int i = 0; i < visibleRows && (i + scroll) < entries.size(); i++) {
            TrustEntry entry = entries.get(i + scroll);
            int y = LIST_TOP + i * ROW_H;
            buildRow(cx, y, entry, entries);
        }

        // --- Relay address: one Done-sized frame with the fixed wss:// scheme drawn inside ---
        // The EditBox itself is borderless and sits inside the frame, after the scheme label.
        int frameX = cx - 205;
        int frameY = this.height - 28;
        int textStart = frameX + 4 + this.font.width(SCHEME_LABEL);
        relayUrlInput = new EditBox(this.font, textStart, frameY + 6,
                frameX + 200 - 4 - textStart, 12, Component.translatable("relay.config.relay_url"));
        relayUrlInput.setBordered(false);
        relayUrlInput.setMaxLength(256);
        relayUrlInput.setValue(config.relayUrl);
        relayUrlInput.setResponder(value -> {
            config.relayUrl = TeamConfig.normalizeRelayAddress(value);
            config.save();
        });
        addRenderableWidget(relayUrlInput);

        // --- Done ---
        addRenderableWidget(Button.builder(Component.translatable("relay.config.done"),
                b -> onClose()).bounds(cx + 5, this.height - 28, 200, 20).build());
    }

    private void buildRow(int cx, int y, TrustEntry entry, List<TrustEntry> backing) {
        // Silence this player's attack pings while still sharing coordinates with them.
        addRenderableWidget(Button.builder(
                Component.translatable(entry.mutePings ? "relay.config.pings_off" : "relay.config.pings_on"),
                b -> {
                    entry.mutePings = !entry.mutePings;
                    config.save();
                    rebuild();
                }).bounds(cx - 50, y, 100, 20).build());
        // Toggle whether this player receives our coordinates.
        addRenderableWidget(Button.builder(
                Component.translatable(entry.hidden ? "relay.config.coords_off" : "relay.config.coords_on"),
                b -> {
                    entry.hidden = !entry.hidden;
                    config.save();
                    TeamLocatorClient.syncToServer();
                    rebuild();
                }).bounds(cx + 55, y, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("relay.config.remove"),
                b -> {
                    backing.remove(entry);
                    config.save();
                    TeamLocatorClient.syncToServer();
                    rebuild();
                }).bounds(cx + 150, y, 55, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // The relay-address frame goes under the borderless EditBox that super renders.
        int cx = this.width / 2;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                relayUrlInput != null && relayUrlInput.isFocused()
                        ? TEXT_FIELD_HIGHLIGHTED_SPRITE : TEXT_FIELD_SPRITE,
                cx - 205, this.height - 28, 200, 20);
        graphics.text(this.font, SCHEME_LABEL, cx - 205 + 4, this.height - 28 + 6, 0xFFA0A0A0, false);

        // Live color swatches beside the HUD hex inputs: white border, current color inside.
        drawSwatch(graphics, cx + 78, config.hudPrimaryArgb());
        drawSwatch(graphics, cx + 180, config.hudSecondaryArgb());

        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        // Section headers for the Location, HUD, and Pings groups.
        graphics.text(this.font, Component.translatable("relay.config.section.location"),
                cx - 205, 24, 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.hud"),
                cx - 205, 68, 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.pings"),
                cx - 205, 136, 0xFFFFFFFF, false);

        // Section header for the list, plus lookup feedback on the right.
        Component header = Component.translatable(config.activeMode == TeamConfig.Mode.GLOBAL
                ? "relay.config.global_list" : "relay.config.server_list");
        graphics.text(this.font, header, cx - 205, 180, 0xFFFFFFFF, false);
        if (addStatus != null) {
            graphics.text(this.font, addStatus, cx + 205 - this.font.width(addStatus),
                    180, addStatusColor, false);
        }

        if (config.activeMode == TeamConfig.Mode.SERVER && TeamConfig.currentServerKey() == null) {
            graphics.text(this.font, Component.translatable("relay.config.no_server"),
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

    /** 20x20 color preview at (x, 104): a white 1px border around the configured color. */
    private static void drawSwatch(GuiGraphicsExtractor graphics, int x, int argb) {
        graphics.fill(x, 104, x + 20, 124, 0xFFFFFFFF);
        graphics.fill(x + 1, 105, x + 19, 123, argb);
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

    /**
     * Add the typed name to the active list. Players currently on this server resolve instantly
     * via the tab list; anyone else is resolved through Mojang's name-to-UUID API so teammates can
     * be added while offline.
     */
    private void addTypedPlayer() {
        if (nameInput == null) {
            return;
        }
        String typed = nameInput.getValue().trim();
        if (typed.isEmpty()) {
            return;
        }
        nameInput.setValue("");

        PlayerInfo info = Minecraft.getInstance().getConnection() != null
                ? Minecraft.getInstance().getConnection().getPlayerInfo(typed) : null;
        if (info != null) {
            addEntry(info.getProfile().id(), info.getProfile().name());
            rebuild();
            return;
        }

        addStatus = Component.translatable("relay.config.lookup_pending", typed);
        addStatusColor = 0xFFA0A0A0;
        NameLookup.resolve(typed).whenComplete((resolved, err) ->
                Minecraft.getInstance().execute(() -> {
                    if (resolved == null || err != null) {
                        addStatus = Component.translatable("relay.config.lookup_failed", typed);
                        addStatusColor = 0xFFFF5555;
                        return;
                    }
                    addStatus = null;
                    addEntry(resolved.id(), resolved.name());
                    if (Minecraft.getInstance().screen == this) {
                        rebuild();
                    }
                }));
    }

    /** Add to the active list (deduplicated by UUID), persist, and push to the relay. */
    private void addEntry(UUID id, String name) {
        List<TrustEntry> list = currentList();
        boolean present = list.stream().anyMatch(e -> {
            UUID u = safeUuid(e);
            return u != null && u.equals(id);
        });
        if (!present) {
            list.add(new TrustEntry(id, name));
            config.save();
            TeamLocatorClient.syncToServer();
        }
    }

    private List<TrustEntry> currentList() {
        TeamConfig.TrustList list = config.activeList();
        // Return a mutable list even when disconnected in SERVER mode, so add/remove callbacks that
        // slip past their guards can't throw UnsupportedOperationException.
        return list != null ? list.trusted : new java.util.ArrayList<>();
    }

    private Component modeLabel(TeamConfig.Mode mode) {
        // CycleButton already renders "<name>: <value>", so return only the value here.
        String key = mode == TeamConfig.Mode.GLOBAL
                ? "relay.config.active_list.global" : "relay.config.active_list.server";
        return Component.translatable(key);
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        // Clicking the wss:// prefix (inside the frame but left of the borderless EditBox) should
        // still focus the address field — the whole frame reads as one text box.
        int cx = this.width / 2;
        if (relayUrlInput != null
                && event.x() >= cx - 205 && event.x() < cx - 5
                && event.y() >= this.height - 28 && event.y() < this.height - 8) {
            this.setFocused(relayUrlInput);
            return true;
        }
        return false;
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
        if (!config.relayUrl.equals(initialRelayUrl)) {
            TeamLocatorClient.connectRelay();
        }
        this.minecraft.setScreen(parent);
    }
}
