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
import net.minecraft.network.chat.CommonComponents;
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

    /**
     * How far the page is scrolled, in content pixels. Widgets are laid out at their natural Y and
     * then shifted up by this, so a window too short for the settings (or a long trust list) can
     * still reach everything — previously anything past the window's height was simply unreachable.
     */
    private int scroll;
    /** Total content height, measured during the last {@link #init()}; drives the scroll clamp. */
    private int contentHeight;
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
    private static final int LIST_TOP = 332;
    /** Height of the pinned bottom bar (relay address + Done), which the page scrolls under. */
    private static final int BOTTOM_BAR_H = 36;
    /** Wheel notch distance, in content pixels. */
    private static final int SCROLL_STEP = 20;

    public TeamLocatorConfigScreen(Screen parent, TeamConfig config) {
        super(Component.translatable("relay.config.title"));
        this.parent = parent;
        this.config = config;
        this.initialRelayUrl = config.relayUrl;
    }

    /** Content Y -> screen Y. Everything that scrolls goes through this. */
    private int sy(int contentY) {
        return contentY - scroll;
    }

    /**
     * Add a scrolled widget, unless it has left the viewport. Culling rather than clipping: widgets
     * draw themselves after this screen's own rendering, so an off-screen one would otherwise paint
     * over the pinned bottom bar (and still take clicks) — dropping it is both simpler and correct.
     * The 20px slack keeps a partially-visible row present as it slides past an edge.
     *
     * @param screenY the widget's screen-space Y, i.e. already through {@link #sy(int)}
     */
    private <T extends net.minecraft.client.gui.components.events.GuiEventListener
            & net.minecraft.client.gui.components.Renderable
            & net.minecraft.client.gui.narration.NarratableEntry> void addScrolled(int screenY, T widget) {
        if (screenY + 20 < 0 || screenY > this.height - BOTTOM_BAR_H) {
            return;
        }
        addRenderableWidget(widget);
    }

    /** The furthest the page can scroll: 0 when everything already fits. */
    private int maxScroll() {
        return Math.max(0, contentHeight - (this.height - BOTTOM_BAR_H));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // --- Sharing section: what we send to trusted players. Location is the master coordinate
        // toggle; armor is opt-in and rides the same trust gate relay-side.
        addScrolled(sy(36), CycleButton.onOffBuilder(config.globalShareEnabled)
                .create(cx - 205, sy(36), 200, 20, Component.translatable("relay.config.share_location"),
                        (btn, value) -> {
                            config.globalShareEnabled = value;
                            config.save();
                            TeamLocatorClient.syncToServer();
                        }));
        addScrolled(sy(36), CycleButton.onOffBuilder(config.shareArmor)
                .create(cx + 5, sy(36), 200, 20, Component.translatable("relay.config.share_armor"),
                        (btn, value) -> {
                            config.shareArmor = value;
                            config.save();
                            // The reporter retracts or re-sends on its next tick; nothing to push here.
                        }));

        // --- HUD section: position sliders side by side, size slider below ---
        addScrolled(sy(80), new HudPositionSlider(cx - 205, sy(80), 200, 20, "HUD X", config.hudX, v -> {
            config.hudX = v;
            config.save();
        }));
        addScrolled(sy(80), new HudPositionSlider(cx + 5, sy(80), 200, 20, "HUD Y", config.hudY, v -> {
            config.hudY = v;
            config.save();
        }));
        addScrolled(sy(104), new HudPositionSlider(cx - 205, sy(104), 200, 20, "HUD Size", 0.5, 2.0,
                config.hudScale, v -> {
            config.hudScale = v;
            config.save();
        }));

        // --- HUD text colors: hex inputs with live swatches (drawn in extractRenderState) ---
        primaryColorInput = new EditBox(this.font, cx + 5, sy(104), 70, 20,
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
        addScrolled(sy(104), primaryColorInput);
        secondaryColorInput = new EditBox(this.font, cx + 107, sy(104), 70, 20,
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
        addScrolled(sy(104), secondaryColorInput);

        // HUD row 3: row text alignment | list growth direction (down vs. up from the anchor).
        addScrolled(sy(128), CycleButton.<TeamConfig.HudAlign>builder(this::hudAlignLabel, config.hudAlign)
                .withValues(TeamConfig.HudAlign.LEFT, TeamConfig.HudAlign.CENTER, TeamConfig.HudAlign.RIGHT)
                .create(cx - 205, sy(128), 200, 20, Component.translatable("relay.config.hud_align"),
                        (btn, value) -> {
                            config.hudAlign = value;
                            config.save();
                        }));
        addScrolled(sy(128), CycleButton.onOffBuilder(config.hudGrowUp)
                .create(cx + 5, sy(128), 200, 20, Component.translatable("relay.config.hud_grow_up"),
                        (btn, value) -> {
                            config.hudGrowUp = value;
                            config.save();
                        }));

        // HUD row 4: what each row displays. Armor only ever shows what a teammate shares.
        addScrolled(sy(152), CycleButton.onOffBuilder(config.hudShowCoords)
                .create(cx - 205, sy(152), 200, 20, Component.translatable("relay.config.hud_show_coords"),
                        (btn, value) -> {
                            config.hudShowCoords = value;
                            config.save();
                        }));
        addScrolled(sy(152), CycleButton
                .<TeamConfig.ArmorDisplay>builder(this::armorDisplayLabel, config.hudArmorDisplay)
                .withValues(TeamConfig.ArmorDisplay.OFF, TeamConfig.ArmorDisplay.ALL,
                        TeamConfig.ArmorDisplay.LOWEST)
                .create(cx + 5, sy(152), 200, 20, Component.translatable("relay.config.hud_show_armor"),
                        (btn, value) -> {
                            config.hudArmorDisplay = value;
                            config.save();
                        }));

        // --- Pings section: cross-server pings toggle | ping display cooldown ---
        addScrolled(sy(196), CycleButton.onOffBuilder(config.crossServerPings)
                .create(cx - 205, sy(196), 200, 20, Component.translatable("relay.config.cross_server_pings"),
                        (btn, value) -> {
                            config.crossServerPings = value;
                            config.save();
                        }));
        addScrolled(sy(196), new SecondsSlider(cx + 5, sy(196), 200, 20, "Alert Cooldown", 0, 60,
                config.pingCooldownSeconds, v -> {
            config.pingCooldownSeconds = v;
            config.save();
        }));
        // Alerts row 2: choose the alert sound (new alarm vs. the old 3-noteblock chord).
        addScrolled(sy(220), CycleButton.<TeamConfig.AlertSound>builder(this::alertSoundLabel, config.alertSound)
                .withValues(TeamConfig.AlertSound.ALARM, TeamConfig.AlertSound.NOTEBLOCKS)
                .create(cx - 205, sy(220), 200, 20, Component.translatable("relay.config.alert_sound"),
                        (btn, value) -> {
                            config.alertSound = value;
                            config.save();
                        }));

        // --- Xaero's section: map icons | in-world icons (read live by the Xaero trackers) ---
        addScrolled(sy(264), CycleButton.onOffBuilder(config.xaeroMapIcons)
                .create(cx - 205, sy(264), 200, 20, Component.translatable("relay.config.xaero_map_icons"),
                        (btn, value) -> {
                            config.xaeroMapIcons = value;
                            config.save();
                        }));
        addScrolled(sy(264), CycleButton.onOffBuilder(config.xaeroInWorldIcons)
                .create(cx + 5, sy(264), 200, 20, Component.translatable("relay.config.xaero_world_icons"),
                        (btn, value) -> {
                            config.xaeroInWorldIcons = value;
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
                .create(cx - 205, sy(308), 200, 20, Component.translatable("relay.config.active_list"),
                        (btn, value) -> {
                            config.activeMode = value;
                            // Pin the choice to this server so rejoining restores it.
                            config.rememberActiveMode();
                            config.save();
                            TeamLocatorClient.syncToServer();
                            rebuild();
                        });
        modeButton.active = serverAvailable;
        addScrolled(sy(308), modeButton);

        // --- Add-player controls beside the mode button: name box | 5 gap | Add ---
        nameInput = new EditBox(this.font, cx + 5, sy(308), 130, 20,
                Component.translatable("relay.config.add_player"));
        nameInput.setHint(Component.translatable("relay.config.add_player"));
        nameInput.setMaxLength(16);
        addScrolled(sy(308), nameInput);
        addScrolled(sy(308), Button.builder(Component.translatable("relay.config.add"),
                b -> addTypedPlayer()).bounds(cx + 140, sy(308), 65, 20).build());

        // --- List rows ---
        // Every entry gets a widget at its natural Y; the page scroll decides what's on screen, so
        // the list no longer needs its own windowing.
        List<TrustEntry> entries = currentList();
        for (int i = 0; i < entries.size(); i++) {
            buildRow(cx, sy(LIST_TOP + i * ROW_H), entries.get(i), entries);
        }
        // Measure the page so the scroll clamp knows where the bottom is, then re-clamp: the
        // content may have shrunk (a removed entry, a switch to a shorter list) while scrolled past
        // the new end.
        contentHeight = LIST_TOP + Math.max(entries.size(), 1) * ROW_H;
        int clamped = Math.max(0, Math.min(scroll, maxScroll()));
        if (clamped != scroll) {
            scroll = clamped;
            rebuild();
            return;
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
        // optionStatus composes "<label>: <on|off>" from vanilla's own keys, so a pack restyling
        // options.on/off reaches these per-player buttons too.
        addScrolled(y, Button.builder(
                CommonComponents.optionStatus(
                        Component.translatable("relay.config.pings"), !entry.mutePings),
                b -> {
                    entry.mutePings = !entry.mutePings;
                    config.save();
                    rebuild();
                }).bounds(cx - 50, y, 100, 20).build());
        // Toggle whether this player sees us at all: hiding withholds the whole shared feed —
        // coordinates and armor both — since armor rides the same sharing set relay-side.
        addScrolled(y, Button.builder(
                CommonComponents.optionStatus(
                        Component.translatable("relay.config.visibility"), !entry.hidden),
                b -> {
                    entry.hidden = !entry.hidden;
                    config.save();
                    TeamLocatorClient.syncToServer();
                    rebuild();
                }).bounds(cx + 55, y, 90, 20).build());
        addScrolled(y, Button.builder(Component.translatable("relay.config.remove"),
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
        drawSwatch(graphics, cx + 78, sy(104), config.hudPrimaryArgb());
        drawSwatch(graphics, cx + 180, sy(104), config.hudSecondaryArgb());

        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        // Section headers for the Sharing, HUD, Alerts, and Xaero's groups. These scroll with the
        // widgets they label.
        graphics.text(this.font, Component.translatable("relay.config.section.sharing"),
                cx - 205, sy(24), 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.hud"),
                cx - 205, sy(68), 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.pings"),
                cx - 205, sy(184), 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.xaero"),
                cx - 205, sy(252), 0xFFFFFFFF, false);

        // Section header for the list, plus lookup feedback on the right.
        Component header = Component.translatable(config.activeMode == TeamConfig.Mode.GLOBAL
                ? "relay.config.global_list" : "relay.config.server_list");
        graphics.text(this.font, header, cx - 205, sy(296), 0xFFFFFFFF, false);
        if (addStatus != null) {
            graphics.text(this.font, addStatus, cx + 205 - this.font.width(addStatus),
                    sy(296), addStatusColor, false);
        }

        if (config.activeMode == TeamConfig.Mode.SERVER && TeamConfig.currentServerKey() == null) {
            graphics.text(this.font, Component.translatable("relay.config.no_server"),
                    cx - 205, sy(LIST_TOP + 4), 0xFFFF5555, false);
        } else {
            // Draw a face + name label beside each row (labels aren't widgets). Skip rows scrolled
            // out of view so they can't paint over the pinned bottom bar — the widget culling in
            // addScrolled dropped their buttons for the same reason.
            List<TrustEntry> entries = currentList();
            for (int i = 0; i < entries.size(); i++) {
                int rowY = sy(LIST_TOP + i * ROW_H);
                if (rowY + 20 < 0 || rowY > this.height - BOTTOM_BAR_H) {
                    continue;
                }
                drawFaceAndName(graphics, cx - 205, rowY, entries.get(i));
            }
        }
        drawScrollbar(graphics);
    }

    /** A slim scrollbar down the right edge, so the page's length and position are visible. */
    private void drawScrollbar(GuiGraphicsExtractor graphics) {
        int max = maxScroll();
        if (max == 0) {
            return; // everything fits; nothing to indicate
        }
        int viewport = this.height - BOTTOM_BAR_H;
        int x = this.width / 2 + 209;
        int thumbH = Math.max(16, viewport * viewport / contentHeight);
        int thumbY = (viewport - thumbH) * scroll / max;
        graphics.fill(x, 0, x + 4, viewport, 0xFF101010);
        graphics.fill(x, thumbY, x + 4, thumbY + thumbH, 0xFF808080);
    }

    /** 20x20 color preview: a white 1px border around the configured color. */
    private static void drawSwatch(GuiGraphicsExtractor graphics, int x, int y, int argb) {
        graphics.fill(x, y, x + 20, y + 20, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 19, y + 19, argb);
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

    private Component hudAlignLabel(TeamConfig.HudAlign align) {
        // CycleButton already renders "<name>: <value>", so return only the value here.
        String key = switch (align) {
            case CENTER -> "relay.config.hud_align.center";
            case RIGHT -> "relay.config.hud_align.right";
            default -> "relay.config.hud_align.left";
        };
        return Component.translatable(key);
    }

    /**
     * On/Off reuse vanilla's own components rather than mod-local copies, so a resource pack that
     * restyles {@code options.on}/{@code options.off} (into check/X glyphs, say) restyles these
     * too — an "Off" of our own would silently opt out of that. Only LOWEST, which vanilla has no
     * word for, is ours.
     */
    private Component armorDisplayLabel(TeamConfig.ArmorDisplay display) {
        return switch (display) {
            case OFF -> CommonComponents.OPTION_OFF;
            case LOWEST -> Component.translatable("relay.config.hud_show_armor.lowest");
            default -> CommonComponents.OPTION_ON;
        };
    }

    private Component alertSoundLabel(TeamConfig.AlertSound sound) {
        String key = sound == TeamConfig.AlertSound.ALARM
                ? "relay.config.alert_sound.alarm" : "relay.config.alert_sound.noteblocks";
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

    /**
     * Scroll the whole page. Anywhere above the pinned bottom bar scrolls — the settings are as
     * likely to be off-screen as the trust list, so restricting the wheel to the list (the old
     * behavior) left the rest unreachable on a short window.
     *
     * <p>Sliders swallow the wheel to change their value, so this only sees notches not consumed by
     * a widget under the cursor; that is vanilla's own convention.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (maxScroll() == 0 || mouseY > this.height - BOTTOM_BAR_H) {
            return false;
        }
        int next = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY) * SCROLL_STEP,
                maxScroll()));
        if (next != scroll) {
            scroll = next;
            rebuild();
        }
        return true;
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
