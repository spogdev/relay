package dev.spog.teamlocator.client.gui;

import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.compat.xaero.XaeroCompat;
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
 * Hand-rolled config screen (no cloth-config), split across three tabs: General (sharing, HUD,
 * alerts, pings, advanced), Integrations (other mods this one talks to) and Trust List (the active
 * mode's players, with per-player alert and visibility toggles). Any change is written to disk and
 * pushed to the relay immediately via {@link TeamLocatorClient#syncToServer()}.
 *
 * <p>The tab bar and the bottom bar (Done) are fixed; only the area between them scrolls, and in
 * practice only the trust list is long enough to need it.
 */
@Environment(EnvType.CLIENT)
public class TeamLocatorConfigScreen extends Screen {
    /**
     * Which page is showing. Each tab keeps its own scroll offset. Integrations sits between General
     * and Trust so the tabs run from "this mod's own settings" out to "who you share with".
     */
    private enum Tab { GENERAL, INTEGRATIONS, TRUST }

    private final Screen parent;
    private final TeamConfig config;
    private Tab tab = Tab.GENERAL;

    private static final String SCHEME_LABEL = "wss://";
    /** Width of the relay address frame in the Advanced section, centred on the page. */
    private static final int RELAY_BOX_W = 200;
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
    /** Parked scroll offsets, so switching tabs and back returns to where you were. */
    private int settingsScroll;
    private int integrationsScroll;
    private int trustScroll;
    /** Total content height, measured during the last {@link #init()}; drives the scroll clamp. */
    private int contentHeight;
    private EditBox nameInput;
    private EditBox relayUrlInput;
    private EditBox chatPrefixInput;
    private EditBox primaryColorInput;
    private EditBox secondaryColorInput;
    /** URL as it was when the screen opened, to detect a change on close and reconnect. */
    private final String initialRelayUrl;
    /** Feedback for an in-flight or failed Mojang name lookup; null when idle. */
    private Component addStatus;
    private int addStatusColor;

    private static final int ROW_H = 24;
    /** Standard button height, and the unit both bars are padded against. */
    private static final int BUTTON_H = 20;
    /** Breathing room inside each bar: the same above and below its contents, top bar and bottom. */
    private static final int BAR_PAD = 8;
    /**
     * Gap between a page's first pixel of content and the bar above it, and the gap the title
     * leaves under the top border. Applied by {@link #sy(int)} to every page, so content Y 0 is the
     * top of a page's content rather than a coordinate flush against the tab bar.
     */
    private static final int TOP_ROW_Y = 12;

    /**
     * Title's top edge. Clears the screen's top border by {@link #TOP_ROW_Y} — the same gap the
     * first row of either page leaves under the tab bar — so the title sits off the border by the
     * same distance as the content below it, rather than crowding the edge.
     */
    private static final int TITLE_Y = TOP_ROW_Y;
    /** Tab buttons sit a pad below the title text (9px tall at default scale). */
    private static final int TAB_BUTTON_Y = TITLE_Y + 9 + BAR_PAD;
    /**
     * Height of the pinned tab bar, which the page scrolls under: the tab buttons plus a matching
     * pad beneath them, so the gap under the tabs equals the gap over them and the gap around the
     * Done button on the opposite bar.
     */
    private static final int TAB_BAR_H = TAB_BUTTON_Y + BUTTON_H + BAR_PAD;
    /** Height of the pinned bottom bar (relay address + Done), padded to match the top. */
    private static final int BOTTOM_BAR_H = BUTTON_H + BAR_PAD * 2;
    /** Wheel notch distance, in content pixels. */
    private static final int SCROLL_STEP = 20;

    /**
     * The General page's vertical layout, as named constants rather than literals scattered through
     * the builder and the header draw.
     *
     * <p>Rows are {@link #BUTTON_H} tall and stack {@link #ROW_STRIDE} apart; a section's header sits
     * {@link #HEADER_GAP} above its first row, and a section starts {@link #SECTION_GAP} after the
     * previous one's last row. Deriving each section from the one above means inserting a row shifts
     * everything below it automatically — adding the health row as a bare literal is exactly how the
     * Alerts header ended up overlapped.
     *
     * <p>{@link #SECTION_GAP} is kept comfortably larger than {@link #HEADER_GAP} so a header still
     * reads as belonging to the section below it rather than drifting toward the rows above. That
     * relationship, not the absolute value, is what keeps the page legible when spacing is
     * tightened.
     */
    private static final int SECTION_GAP = 26;
    private static final int ROW_STRIDE = 24;
    private static final int HEADER_GAP = 12;

    /** Sharing: header, then two rows — location/armor, then health. */
    private static final int SHARING_HEADER_Y = 0;
    private static final int SHARING_ROW_Y = SHARING_HEADER_Y + HEADER_GAP;
    private static final int SHARING_ROW_2_Y = SHARING_ROW_Y + ROW_STRIDE;

    /** HUD: five rows — position, size/colour, alignment, contents, health. */
    private static final int HUD_HEADER_Y = SHARING_ROW_2_Y + BUTTON_H + SECTION_GAP - HEADER_GAP;
    private static final int HUD_ROW_1_Y = HUD_HEADER_Y + HEADER_GAP;
    private static final int HUD_ROW_2_Y = HUD_ROW_1_Y + ROW_STRIDE;
    private static final int HUD_ROW_3_Y = HUD_ROW_2_Y + ROW_STRIDE;
    private static final int HUD_ROW_4_Y = HUD_ROW_3_Y + ROW_STRIDE;
    private static final int HUD_ROW_5_Y = HUD_ROW_4_Y + ROW_STRIDE;

    /** Alerts: two rows. */
    private static final int ALERTS_HEADER_Y = HUD_ROW_5_Y + BUTTON_H + SECTION_GAP - HEADER_GAP;
    private static final int ALERTS_ROW_1_Y = ALERTS_HEADER_Y + HEADER_GAP;
    private static final int ALERTS_ROW_2_Y = ALERTS_ROW_1_Y + ROW_STRIDE;

    /** Pings: three rows. */
    private static final int PINGS_ROW_Y = ALERTS_ROW_2_Y + BUTTON_H + SECTION_GAP;
    /**
     * First (and last) row of the Advanced section, which took the slot Xaero's used to occupy when
     * those moved to Integrations. Holds the relay address, which was previously pinned in the
     * bottom bar on every tab — it is a set-once setting, so it belongs behind an "Advanced" heading
     * rather than permanently on screen.
     */
    /** Chat: one row, a section gap below the Pings block's last row. */
    private static final int CHAT_ROW_Y = PINGS_ROW_Y + ROW_STRIDE * 2 + BUTTON_H + SECTION_GAP;
    /** Advanced: one row (the relay address). */
    private static final int ADVANCED_ROW_Y = CHAT_ROW_Y + BUTTON_H + SECTION_GAP;
    private static final int SETTINGS_LAST_ROW_Y = ADVANCED_ROW_Y;
    /**
     * The Integrations page's only section so far: Xaero's two toggles. Offset by the header gap so
     * the "Xaero's" heading above them has room to sit at content Y 0 rather than being clipped off
     * the top of the page.
     */
    private static final int INTEGRATIONS_ROW_Y = 12; // == COL_HEADER_OFFSET, declared below
    /** How far the column header's text floats above the first table row. */
    private static final int COL_HEADER_OFFSET = 12;
    /**
     * First row of the trust table: a section's gap below the mode/add row at content Y 0, matching
     * the settings page's rhythm. That gap is what gives the column header room to sit
     * {@link #COL_HEADER_OFFSET} above the first row without clipping into the buttons overhead.
     */
    private static final int LIST_TOP = SECTION_GAP;
    /**
     * Column x-offsets from the screen centre, shared by the header labels and the row widgets.
     * The toggles only carry ON/OFF now that the header names them, so they need far less width
     * than the old inline labels did; the name column absorbs what they gave up.
     *
     * <p>Mute and Sharing are one width: they hold the same ON/OFF, so any difference between
     * them reads as meaning something it doesn't. 45 clears the wider heading — "Sharing" is
     * 38px ({@code i} is only 2px wide); "Mute" is narrower still, so both fit comfortably.
     */
    private static final int TOGGLE_W = 45;
    /** Gap between adjacent columns, even across the row. */
    private static final int COL_GAP = 10;
    private static final int COL_REMOVE_W = 50;
    /** Right-aligned to the panel edge; the two toggles then step back from it by even gaps. */
    private static final int COL_REMOVE_X = 205 - COL_REMOVE_W;
    private static final int COL_VISIBILITY_W = TOGGLE_W;
    private static final int COL_VISIBILITY_X = COL_REMOVE_X - COL_GAP - COL_VISIBILITY_W;
    private static final int COL_ALERTS_W = TOGGLE_W;
    private static final int COL_ALERTS_X = COL_VISIBILITY_X - COL_GAP - COL_ALERTS_W;

    public TeamLocatorConfigScreen(Screen parent, TeamConfig config) {
        super(Component.translatable("relay.config.title"));
        this.parent = parent;
        this.config = config;
        this.initialRelayUrl = config.relayUrl;
    }

    /**
     * Content Y -> screen Y. Everything that scrolls goes through this.
     *
     * <p>Content Y 0 is a page's first pixel, and lands TOP_ROW_Y below the tab bar — the margin
     * belongs here rather than in each page's coordinates, so both pages clear the bar by the same
     * gap and neither can sit flush against it.
     */
    private int sy(int contentY) {
        return TAB_BAR_H + TOP_ROW_Y + contentY - scroll;
    }

    /** Bottom edge of the scrolling area: the pinned bottom bar starts here. */
    private int viewportBottom() {
        return this.height - BOTTOM_BAR_H;
    }

    /** Height of the scrolling area, between the two pinned bars. */
    private int viewportHeight() {
        return Math.max(0, viewportBottom() - TAB_BAR_H);
    }

    /**
     * Add a scrolled widget, unless it would cross a pinned bar. Culling rather than clipping:
     * widgets draw themselves after this screen's own rendering, so no backdrop can cover one — a
     * widget overlapping a bar would paint across it and still take clicks there. So a row is kept
     * only while it is *entirely* between the bars, and rows appear and disappear whole.
     *
     * @param screenY the widget's screen-space Y, i.e. already through {@link #sy(int)}
     */
    private <T extends net.minecraft.client.gui.components.events.GuiEventListener
            & net.minecraft.client.gui.components.Renderable
            & net.minecraft.client.gui.narration.NarratableEntry> void addScrolled(int screenY, T widget) {
        if (!visible(screenY)) {
            return;
        }
        addRenderableWidget(widget);
    }

    /** True if a 20px-tall row at this screen Y fits entirely between the pinned bars. */
    private boolean visible(int screenY) {
        return screenY >= TAB_BAR_H && screenY + 20 <= viewportBottom();
    }

    /**
     * The page's full length: its content plus the top margin {@link #sy(int)} adds, which occupies
     * viewport space just as the content does.
     */
    private int scrollableHeight() {
        return contentHeight + TOP_ROW_Y;
    }

    /**
     * The furthest the page can scroll: 0 when everything already fits. Measured against
     * {@link #scrollableHeight()} — leaving the margin out strands the last row just below the
     * bottom bar, culled and unreachable.
     */
    private int maxScroll() {
        return Math.max(0, scrollableHeight() - viewportHeight());
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // Page widgets are rebuilt from scratch on every init; clear the references to the ones that
        // only some pages create, so a stale widget from the previous tab can't be drawn or focused.
        relayUrlInput = null;
        chatPrefixInput = null;

        // --- Tab bar: pinned above the scrolling area, so switching pages is always reachable.
        // The tabs abut and together span the page's full width, so the strip reads as one unit
        // sitting on top of the page rather than as more buttons among the settings. 410px split
        // three ways leaves a remainder; the last tab absorbs it so the strip ends flush right
        // rather than a pixel or two short.
        int tabW = 410 / 3;
        int lastTabX = cx - 205 + tabW * 2;
        addRenderableWidget(new TabButton(cx - 205, TAB_BUTTON_Y, tabW, BUTTON_H,
                Component.translatable("relay.config.tab.general"),
                tab == Tab.GENERAL, () -> switchTab(Tab.GENERAL)));
        addRenderableWidget(new TabButton(cx - 205 + tabW, TAB_BUTTON_Y, tabW, BUTTON_H,
                Component.translatable("relay.config.tab.integrations"),
                tab == Tab.INTEGRATIONS, () -> switchTab(Tab.INTEGRATIONS)));
        addRenderableWidget(new TabButton(lastTabX, TAB_BUTTON_Y, cx + 205 - lastTabX, BUTTON_H,
                Component.translatable("relay.config.tab.trust"),
                tab == Tab.TRUST, () -> switchTab(Tab.TRUST)));

        switch (tab) {
            case GENERAL -> initSettingsPage(cx);
            case INTEGRATIONS -> initIntegrationsPage(cx);
            case TRUST -> initTrustPage(cx);
        }

        // Re-clamp after the page has measured itself: the content may have shrunk (a removed entry,
        // a switch to a shorter list) while scrolled past the new end.
        int clamped = Math.max(0, Math.min(scroll, maxScroll()));
        if (clamped != scroll) {
            scroll = clamped;
            rebuild();
            return;
        }

        initBottomBar(cx);
    }

    /** Swap pages, parking the outgoing tab's scroll so returning to it restores the position. */
    private void switchTab(Tab next) {
        if (tab == next) {
            return;
        }
        switch (tab) {
            case GENERAL -> settingsScroll = scroll;
            case INTEGRATIONS -> integrationsScroll = scroll;
            case TRUST -> trustScroll = scroll;
        }
        tab = next;
        scroll = switch (next) {
            case GENERAL -> settingsScroll;
            case INTEGRATIONS -> integrationsScroll;
            case TRUST -> trustScroll;
        };
        addStatus = null; // lookup feedback belongs to the trust page's Add box
        rebuild();
    }

    /**
     * Attaches a hover description to a control and returns it, so a widget can be described inline
     * at its construction site rather than needing a local just to call setTooltip on it.
     *
     * <p>{@code setTooltip} is declared on AbstractWidget, so this works uniformly for cycle buttons,
     * sliders and plain buttons alike — unlike CycleButton's own builder-level tooltip, which only
     * exists on that one type and is keyed by value rather than being a fixed description.
     */
    private static <T extends net.minecraft.client.gui.components.AbstractWidget> T described(
            T widget, String descriptionKey) {
        widget.setTooltip(Tooltip.create(Component.translatable(descriptionKey)));
        return widget;
    }

    private void initSettingsPage(int cx) {
        // --- Sharing section: what we send to trusted players. Location is the master coordinate
        // toggle; armor is opt-in and rides the same trust gate relay-side.
        addScrolled(sy(SHARING_ROW_Y), described(CycleButton.onOffBuilder(config.globalShareEnabled)
                .create(cx - 205, sy(SHARING_ROW_Y), 200, 20, Component.translatable("relay.config.share_location"),
                        (btn, value) -> {
                            config.globalShareEnabled = value;
                            config.save();
                            TeamLocatorClient.syncToServer();
                        }), "relay.config.share_location.desc"));
        addScrolled(sy(SHARING_ROW_Y), described(CycleButton.onOffBuilder(config.shareArmor)
                .create(cx + 5, sy(SHARING_ROW_Y), 200, 20, Component.translatable("relay.config.share_armor"),
                        (btn, value) -> {
                            config.shareArmor = value;
                            config.save();
                            // The reporter retracts or re-sends on its next tick; nothing to push here.
                        }), "relay.config.share_armor.desc"));
        addScrolled(sy(SHARING_ROW_2_Y), described(CycleButton.onOffBuilder(config.shareHealth)
                .create(cx - 205, sy(SHARING_ROW_2_Y), 200, 20,
                        Component.translatable("relay.config.share_health"),
                        (btn, value) -> {
                            config.shareHealth = value;
                            config.save();
                            // Takes effect on the next position update, which is every 4 ticks.
                        }), "relay.config.share_health.desc"));

        // --- HUD section: position sliders side by side, size slider below ---
        addScrolled(sy(HUD_ROW_1_Y), new HudPositionSlider(cx - 205, sy(HUD_ROW_1_Y), 200, 20, "HUD X", config.hudX, v -> {
            config.hudX = v;
            config.save();
        }));
        addScrolled(sy(HUD_ROW_1_Y), new HudPositionSlider(cx + 5, sy(HUD_ROW_1_Y), 200, 20, "HUD Y", config.hudY, v -> {
            config.hudY = v;
            config.save();
        }));
        addScrolled(sy(HUD_ROW_2_Y), new HudPositionSlider(cx - 205, sy(HUD_ROW_2_Y), 200, 20, "HUD Size", 0.5, 2.0,
                config.hudScale, v -> {
            config.hudScale = v;
            config.save();
        }));

        // --- HUD text colors: hex inputs with live swatches (drawn in extractRenderState) ---
        primaryColorInput = new EditBox(this.font, cx + 5, sy(HUD_ROW_2_Y), 70, 20,
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
        addScrolled(sy(HUD_ROW_2_Y), primaryColorInput);
        secondaryColorInput = new EditBox(this.font, cx + 107, sy(HUD_ROW_2_Y), 70, 20,
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
        addScrolled(sy(HUD_ROW_2_Y), secondaryColorInput);

        // HUD row 3: row text alignment | list growth direction (down vs. up from the anchor).
        addScrolled(sy(HUD_ROW_3_Y), CycleButton.<TeamConfig.HudAlign>builder(this::hudAlignLabel, config.hudAlign)
                .withValues(TeamConfig.HudAlign.LEFT, TeamConfig.HudAlign.CENTER,
                        TeamConfig.HudAlign.RIGHT, TeamConfig.HudAlign.TABLE)
                .create(cx - 205, sy(HUD_ROW_3_Y), 200, 20, Component.translatable("relay.config.hud_align"),
                        (btn, value) -> {
                            config.hudAlign = value;
                            config.save();
                        }));
        addScrolled(sy(HUD_ROW_3_Y), described(CycleButton.onOffBuilder(config.hudGrowUp)
                .create(cx + 5, sy(HUD_ROW_3_Y), 200, 20, Component.translatable("relay.config.hud_grow_up"),
                        (btn, value) -> {
                            config.hudGrowUp = value;
                            config.save();
                        }), "relay.config.hud_grow_up.desc"));

        // HUD row 4: what each row displays. Armor only ever shows what a teammate shares.
        addScrolled(sy(HUD_ROW_4_Y), CycleButton.onOffBuilder(config.hudShowCoords)
                .create(cx - 205, sy(HUD_ROW_4_Y), 200, 20, Component.translatable("relay.config.hud_show_coords"),
                        (btn, value) -> {
                            config.hudShowCoords = value;
                            config.save();
                        }));
        addScrolled(sy(HUD_ROW_4_Y), CycleButton
                .<TeamConfig.ArmorDisplay>builder(this::armorDisplayLabel, config.hudArmorDisplay)
                .withValues(TeamConfig.ArmorDisplay.OFF, TeamConfig.ArmorDisplay.ALL,
                        TeamConfig.ArmorDisplay.LOWEST)
                .create(cx + 5, sy(HUD_ROW_4_Y), 200, 20, Component.translatable("relay.config.hud_show_armor"),
                        (btn, value) -> {
                            config.hudArmorDisplay = value;
                            config.save();
                        }));

        // HUD row 5: teammate health.
        addScrolled(sy(HUD_ROW_5_Y), described(CycleButton.onOffBuilder(config.hudShowHealth)
                .create(cx - 205, sy(HUD_ROW_5_Y), 200, 20,
                        Component.translatable("relay.config.hud_show_health"),
                        (btn, value) -> {
                            config.hudShowHealth = value;
                            config.save();
                        }), "relay.config.hud_show_health.desc"));

        // --- Alerts section ---
        // Row 1: the master switch first, since it gates everything below it, with the cross-server
        // toggle beside it. Stored inverted (hideAllAlerts) but shown as "Enable Alerts", so the
        // label and the ON/OFF it carries agree — a switch reading "Hide All Alerts: OFF" for the
        // normal case is a double negative.
        addScrolled(sy(ALERTS_ROW_1_Y), CycleButton.onOffBuilder(!config.hideAllAlerts)
                .create(cx - 205, sy(ALERTS_ROW_1_Y), 200, 20, Component.translatable("relay.config.enable_alerts"),
                        (btn, value) -> {
                            config.hideAllAlerts = !value;
                            config.save();
                        }));
        addScrolled(sy(ALERTS_ROW_1_Y), described(CycleButton.onOffBuilder(config.crossServerPings)
                .create(cx + 5, sy(ALERTS_ROW_1_Y), 200, 20, Component.translatable("relay.config.cross_server_pings"),
                        (btn, value) -> {
                            config.crossServerPings = value;
                            config.save();
                        }), "relay.config.cross_server_pings.desc"));
        // Row 2: alert sound (left) | cooldown (right).
        addScrolled(sy(ALERTS_ROW_2_Y), CycleButton.<TeamConfig.AlertSound>builder(this::alertSoundLabel, config.alertSound)
                .withValues(TeamConfig.AlertSound.ALARM, TeamConfig.AlertSound.NOTEBLOCKS)
                .create(cx - 205, sy(ALERTS_ROW_2_Y), 200, 20, Component.translatable("relay.config.alert_sound"),
                        (btn, value) -> {
                            config.alertSound = value;
                            config.save();
                        }));
        addScrolled(sy(ALERTS_ROW_2_Y), described(new SecondsSlider(cx + 5, sy(ALERTS_ROW_2_Y), 200, 20, "Alert Cooldown",
                SecondsSlider.COOLDOWN_STEPS, config.pingCooldownSeconds, v -> {
            config.pingCooldownSeconds = v;
            config.save();
        }), "relay.config.alert_cooldown.desc"));

        // --- Pings section ---
        // Row 1: the master switch first (it gates the rest), colour picker beside it — the same
        // "enable, then configure" order the Alerts section above uses.
        addScrolled(sy(PINGS_ROW_Y), CycleButton.onOffBuilder(config.showPings)
                .create(cx - 205, sy(PINGS_ROW_Y), 200, 20,
                        Component.translatable("relay.config.show_pings"),
                        (btn, value) -> {
                            config.showPings = value;
                            config.save();
                        }));
        addScrolled(sy(PINGS_ROW_Y), described(
                new PingColorButton(cx + 5, sy(PINGS_ROW_Y), 200, 20, config),
                "relay.config.ping_color.desc"));
        // Row 2: how long THIS player sees pings for — theirs and teammates' alike — and whether
        // they punch through terrain.
        addScrolled(sy(PINGS_ROW_Y + 24), new SecondsSlider(cx - 205, sy(PINGS_ROW_Y + 24), 200, 20,
                "Ping Duration", SecondsSlider.PING_DURATION_STEPS, config.pingDisplaySeconds, v -> {
            config.pingDisplaySeconds = v;
            config.save();
        }));
        addScrolled(sy(PINGS_ROW_Y + 24), described(CycleButton.onOffBuilder(config.pingsThroughWalls)
                .create(cx + 5, sy(PINGS_ROW_Y + 24), 200, 20,
                        Component.translatable("relay.config.pings_through_walls"),
                        (btn, value) -> {
                            config.pingsThroughWalls = value;
                            config.save();
                        }), "relay.config.pings_through_walls.desc"));
        // Row 3: rate limit on how often any one teammate's pings are accepted.
        addScrolled(sy(PINGS_ROW_Y + 48), described(new SecondsSlider(
                cx - 205, sy(PINGS_ROW_Y + 48), 200, 20,
                "Ping Cooldown", SecondsSlider.PING_COOLDOWN_STEPS, config.mapPingCooldownSeconds, v -> {
            config.mapPingCooldownSeconds = v;
            config.save();
        }), "relay.config.ping_cooldown.desc"));

        // --- Chat section: master switch | the prefix character that triggers relay chat ---
        addScrolled(sy(CHAT_ROW_Y), described(CycleButton.onOffBuilder(config.chatEnabled)
                .create(cx - 205, sy(CHAT_ROW_Y), 200, 20,
                        Component.translatable("relay.config.chat_enabled"),
                        (btn, value) -> {
                            config.chatEnabled = value;
                            config.save();
                        }), "relay.config.chat_enabled.desc"));
        chatPrefixInput = new EditBox(this.font, cx + 5, sy(CHAT_ROW_Y), 200, 20,
                Component.translatable("relay.config.chat_prefix"));
        chatPrefixInput.setMaxLength(1);
        chatPrefixInput.setValue(config.chatPrefix);
        chatPrefixInput.setHint(Component.translatable("relay.config.chat_prefix"));
        chatPrefixInput.setResponder(value -> {
            // Ignore an empty box mid-edit rather than resetting to the default under the user's
            // cursor; the config's own validation catches anything still invalid on load.
            if (value != null && value.length() == 1 && !Character.isWhitespace(value.charAt(0))) {
                config.chatPrefix = value;
                config.save();
            }
        });
        chatPrefixInput.setTooltip(Tooltip.create(
                Component.translatable("relay.config.chat_prefix.desc")));
        addScrolled(sy(CHAT_ROW_Y), chatPrefixInput);

        // --- Advanced section: the relay address, centred ---
        // Previously pinned in the bottom bar on every tab. It is a set-once setting, so it lives
        // behind an Advanced heading instead of occupying permanent screen space. The frame and the
        // fixed wss:// label are drawn in render() at the same content Y, so all three scroll
        // together; only the EditBox is a real widget.
        int boxY = sy(ADVANCED_ROW_Y);
        int textStart = cx - 205 + 4 + this.font.width(SCHEME_LABEL);
        relayUrlInput = new EditBox(this.font, textStart, boxY + 6,
                cx - 205 + RELAY_BOX_W - 4 - textStart, 12,
                Component.translatable("relay.config.relay_url"));
        relayUrlInput.setBordered(false);
        relayUrlInput.setMaxLength(256);
        relayUrlInput.setValue(config.relayUrl);
        relayUrlInput.setResponder(value -> {
            config.relayUrl = TeamConfig.normalizeRelayAddress(value);
            config.save();
        });
        // The box is borderless and inset inside the drawn frame, so this only triggers over the
        // editable text itself rather than the whole frame — that is where anyone hovering to ask
        // "what is this?" will actually be pointing.
        relayUrlInput.setTooltip(Tooltip.create(
                Component.translatable("relay.config.relay_url.desc")));
        addScrolled(boxY, relayUrlInput);

        contentHeight = SETTINGS_LAST_ROW_Y + 20 + 4; // last row, its height, and a little padding
    }

    /**
     * Integrations: settings for other mods this one talks to. Only Xaero's so far; the page exists
     * as its own tab so future cross-mod options have an obvious home rather than being wedged into
     * General.
     */
    private void initIntegrationsPage(int cx) {
        // --- Xaero's section: map icons | in-world icons (read live by the Xaero trackers) ---
        // Each control is disabled when the mod that acts on it isn't installed, so a setting that
        // could not possibly do anything reads as unavailable rather than as broken. The two have
        // different requirements: the map-icon flag is honoured by both Xaero trackers, while the
        // in-world icon is enforced by a mixin into the minimap's renderer specifically.
        boolean minimap = XaeroCompat.isMinimapInstalled();
        boolean worldMap = XaeroCompat.isWorldMapInstalled();

        var mapIcons = CycleButton.onOffBuilder(config.xaeroMapIcons)
                .create(cx - 205, sy(INTEGRATIONS_ROW_Y), 200, 20,
                        Component.translatable("relay.config.xaero_map_icons"),
                        (btn, value) -> {
                            config.xaeroMapIcons = value;
                            config.save();
                        });
        mapIcons.active = minimap || worldMap;
        if (!mapIcons.active) {
            mapIcons.setTooltip(Tooltip.create(
                    Component.translatable("relay.config.integration.missing")));
        }
        addScrolled(sy(INTEGRATIONS_ROW_Y), mapIcons);

        var inWorldIcons = CycleButton.onOffBuilder(config.xaeroInWorldIcons)
                .create(cx + 5, sy(INTEGRATIONS_ROW_Y), 200, 20,
                        Component.translatable("relay.config.xaero_world_icons"),
                        (btn, value) -> {
                            config.xaeroInWorldIcons = value;
                            config.save();
                        });
        inWorldIcons.active = minimap;
        if (!inWorldIcons.active) {
            inWorldIcons.setTooltip(Tooltip.create(
                    Component.translatable("relay.config.integration.missing")));
        }
        addScrolled(sy(INTEGRATIONS_ROW_Y), inWorldIcons);

        contentHeight = INTEGRATIONS_ROW_Y + 20 + 4;
    }

    /** The trust list page: mode cycle and add-player controls up top, then the player table. */
    private void initTrustPage(int cx) {
        // --- Active-list mode cycle (Global / This Server) ---
        // Sits at TOP_ROW_Y like the settings page's first row, rather than flush against the tab
        // bar, so both pages open with the same gap under the tabs.
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
                .create(cx - 205, sy(0), 200, BUTTON_H,
                        Component.translatable("relay.config.active_list"),
                        (btn, value) -> {
                            config.activeMode = value;
                            // Pin the choice to this server so rejoining restores it.
                            config.rememberActiveMode();
                            config.save();
                            TeamLocatorClient.syncToServer();
                            rebuild();
                        });
        modeButton.active = serverAvailable;
        addScrolled(sy(0), modeButton);

        // --- Add-player controls beside the mode button: name box | 5 gap | Add ---
        nameInput = new EditBox(this.font, cx + 5, sy(0), 130, BUTTON_H,
                Component.translatable("relay.config.add_player"));
        nameInput.setHint(Component.translatable("relay.config.add_player"));
        nameInput.setMaxLength(16);
        addScrolled(sy(0), nameInput);
        addScrolled(sy(0), Button.builder(Component.translatable("relay.config.add"),
                b -> addTypedPlayer()).bounds(cx + 140, sy(0), 65, BUTTON_H).build());

        // --- Table rows ---
        // Every entry gets a widget at its natural Y; the page scroll decides what's on screen, so
        // the list needs no windowing of its own.
        List<TrustEntry> entries = currentList();
        for (int i = 0; i < entries.size(); i++) {
            buildRow(cx, sy(LIST_TOP + i * ROW_H), entries.get(i), entries);
        }
        contentHeight = LIST_TOP + Math.max(entries.size(), 1) * ROW_H;
    }

    private void initBottomBar(int cx) {
        // --- Done, centred ---
        // The relay address used to share this bar; with it moved to General > Advanced, Done is the
        // bar's only occupant and sits centred rather than stranded against the right edge.
        addRenderableWidget(Button.builder(Component.translatable("relay.config.done"),
                b -> onClose()).bounds(cx - 100, this.height - 28, 200, 20).build());
    }

    private void buildRow(int cx, int y, TrustEntry entry, List<TrustEntry> backing) {
        // Bare ON/OFF, not optionStatus's "<label>: <on|off>" — the column header names the toggle
        // once for the whole table, so repeating it on every row is noise. Still vanilla's own
        // components, so a pack restyling options.on/off (into check/X glyphs, say) reaches these.
        //
        // This is the Mute column: ON means "muted" (you won't hear this player's alerts). It is
        // inbound and local only — muting someone never affects whether they receive YOUR alerts.
        // Default is unmuted (mutePings = false), so ON here is an opt-in silence.
        addScrolled(y, Button.builder(onOff(entry.mutePings),
                b -> {
                    entry.mutePings = !entry.mutePings;
                    config.save();
                    rebuild();
                }).bounds(cx + COL_ALERTS_X, y, COL_ALERTS_W, 20).build());
        // Toggle whether this player sees us at all: hiding withholds the whole shared feed —
        // coordinates and armor both — since armor rides the same sharing set relay-side.
        addScrolled(y, Button.builder(onOff(!entry.hidden),
                b -> {
                    entry.hidden = !entry.hidden;
                    config.save();
                    TeamLocatorClient.syncToServer();
                    rebuild();
                }).bounds(cx + COL_VISIBILITY_X, y, COL_VISIBILITY_W, 20).build());
        addScrolled(y, Button.builder(Component.translatable("relay.config.remove"),
                b -> {
                    backing.remove(entry);
                    config.save();
                    TeamLocatorClient.syncToServer();
                    rebuild();
                }).bounds(cx + COL_REMOVE_X, y, COL_REMOVE_W, 20).build());
    }

    /** Vanilla's own On/Off components, so a resource pack restyling options.on/off reaches us. */
    private static Component onOff(boolean on) {
        return on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;

        // Scrolling labels first, then the bars' backdrops over them, then super's widgets on top:
        // that ordering lets the backdrops hide a label that has scrolled into a bar, while the tab
        // buttons and the borderless address EditBox still land above their own backdrop.
        switch (tab) {
            case GENERAL -> drawSettingsLabels(graphics, cx);
            case INTEGRATIONS -> drawIntegrationsLabels(graphics, cx);
            case TRUST -> drawTrustLabels(graphics, cx);
        }
        drawBarBackdrops(graphics, cx);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.centeredText(this.font, this.title, cx, TITLE_Y, 0xFFFFFFFF);
        drawScrollbar(graphics);
    }

    /**
     * The two pinned bars' backdrops. Drawn before {@code super} so the widgets on the bars land on
     * top of them; the backdrops cover scrolled *labels*, while scrolled widgets are culled instead
     * (see {@link #addScrolled}), since nothing this screen draws can cover them.
     */
    private void drawBarBackdrops(GuiGraphicsExtractor graphics, int cx) {
        // No divider under the tab strip: the selected tab's open bottom edge is what joins it to
        // the page, and a rule across there would cut that connection. The strip's own borders
        // already separate it from the content.
        graphics.fill(0, 0, this.width, TAB_BAR_H, 0xFF101010);

        int top = this.height - BOTTOM_BAR_H;
        graphics.fill(0, top, this.width, this.height, 0xFF101010);
        graphics.fill(0, top, this.width, top + 1, 0xFF000000);
    }

    /**
     * The relay-address frame and its fixed {@code wss://} label, which the borderless EditBox sits
     * inside. Part of the General page's Advanced section, so it scrolls with the rest — and is
     * skipped entirely when scrolled behind a bar, matching how {@link #addScrolled} culls widgets.
     */
    private void drawRelayFrame(GuiGraphicsExtractor graphics, int cx) {
        int y = sy(ADVANCED_ROW_Y);
        if (y < TAB_BAR_H || y + BUTTON_H > viewportBottom()) {
            return;
        }
        int x = cx - 205;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                relayUrlInput != null && relayUrlInput.isFocused()
                        ? TEXT_FIELD_HIGHLIGHTED_SPRITE : TEXT_FIELD_SPRITE,
                x, y, RELAY_BOX_W, BUTTON_H);
        graphics.text(this.font, SCHEME_LABEL, x + 4, y + 6, 0xFFA0A0A0, false);
    }

    private void drawSettingsLabels(GuiGraphicsExtractor graphics, int cx) {
        // Live color swatches beside the HUD hex inputs: white border, current color inside.
        drawSwatch(graphics, cx + 78, sy(HUD_ROW_2_Y), config.hudPrimaryArgb());
        drawSwatch(graphics, cx + 180, sy(HUD_ROW_2_Y), config.hudSecondaryArgb());

        // Section headers, scrolling with the widgets they label.
        graphics.text(this.font, Component.translatable("relay.config.section.sharing"),
                cx - 205, sy(SHARING_HEADER_Y), 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.hud"),
                cx - 205, sy(HUD_HEADER_Y), 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.pings"),
                cx - 205, sy(ALERTS_HEADER_Y), 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.map_pings"),
                cx - 205, sy(PINGS_ROW_Y - 12), 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.chat"),
                cx - 205, sy(CHAT_ROW_Y - 12), 0xFFFFFFFF, false);
        graphics.text(this.font, Component.translatable("relay.config.section.advanced"),
                cx - 205, sy(ADVANCED_ROW_Y - 12), 0xFFFFFFFF, false);
        // The address frame belongs to this page now, so it is drawn here — before super, so the
        // borderless EditBox still lands on top of it.
        drawRelayFrame(graphics, cx);
    }

    private void drawIntegrationsLabels(GuiGraphicsExtractor graphics, int cx) {
        // Dim the heading too when neither Xaero mod is present, so the whole block reads as
        // unavailable rather than a live section that happens to hold two dead controls.
        boolean anyXaero = XaeroCompat.isMinimapInstalled() || XaeroCompat.isWorldMapInstalled();
        graphics.text(this.font, Component.translatable("relay.config.section.xaero"),
                cx - 205, sy(INTEGRATIONS_ROW_Y - 12), anyXaero ? 0xFFFFFFFF : 0xFF808080, false);
    }

    private void drawTrustLabels(GuiGraphicsExtractor graphics, int cx) {
        if (config.activeMode == TeamConfig.Mode.SERVER && TeamConfig.currentServerKey() == null) {
            graphics.text(this.font, Component.translatable("relay.config.no_server"),
                    cx - 205, sy(LIST_TOP + 4), 0xFFFF5555, false);
            return;
        }

        // Column headers, naming each toggle once for the whole table instead of on every row.
        // Each is centered over its column so it reads as a heading for the buttons beneath it.
        int headerY = sy(LIST_TOP - COL_HEADER_OFFSET);
        boolean headerVisible = headerY >= TAB_BAR_H && headerY + this.font.lineHeight <= viewportBottom();
        if (headerVisible) {
            graphics.text(this.font, Component.translatable("relay.config.column.player"),
                    cx - 205, headerY, 0xFFFFFFFF, false);
            drawColumnHeader(graphics, "relay.config.column.alerts",
                    cx + COL_ALERTS_X, COL_ALERTS_W, headerY);
            // Lookup feedback shares this row, right-aligned where the Remove column sits. It
            // replaces the Visibility heading rather than overlapping it — the status is transient
            // and the columns beneath it stay self-evident for the few seconds it shows.
            if (addStatus != null) {
                graphics.text(this.font, addStatus, cx + 205 - this.font.width(addStatus),
                        headerY, addStatusColor, false);
            } else {
                drawColumnHeader(graphics, "relay.config.column.visibility",
                        cx + COL_VISIBILITY_X, COL_VISIBILITY_W, headerY);
            }
        }

        // Draw a face + name label beside each row (labels aren't widgets). Skip rows scrolled out
        // of view, the same way addScrolled drops their buttons.
        List<TrustEntry> entries = currentList();
        for (int i = 0; i < entries.size(); i++) {
            int rowY = sy(LIST_TOP + i * ROW_H);
            if (!visible(rowY)) {
                continue;
            }
            drawFaceAndName(graphics, cx - 205, rowY, entries.get(i));
        }
    }

    /**
     * One column heading, centered over a column of the given x/width. Drawn white and shadowless
     * to match the left-aligned "Player" heading — {@code centeredText} has no shadowless overload
     * and would draw a shadow, so this centers by hand and uses the shadowless {@code text}.
     */
    private void drawColumnHeader(GuiGraphicsExtractor graphics, String key, int colX, int colW,
                                  int y) {
        Component label = Component.translatable(key);
        int x = colX + colW / 2 - this.font.width(label) / 2;
        graphics.text(this.font, label, x, y, 0xFFFFFFFF, false);
    }

    /** A slim scrollbar down the right edge, so the page's length and position are visible. */
    private void drawScrollbar(GuiGraphicsExtractor graphics) {
        int max = maxScroll();
        if (max == 0) {
            return; // everything fits; nothing to indicate
        }
        int viewport = viewportHeight();
        int x = this.width / 2 + 209;
        int thumbH = Math.max(16, viewport * viewport / scrollableHeight());
        int thumbY = TAB_BAR_H + (viewport - thumbH) * scroll / max;
        graphics.fill(x, TAB_BAR_H, x + 4, viewportBottom(), 0xFF101010);
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
            case TABLE -> "relay.config.hud_align.table";
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
        // still focus the address field — the whole frame reads as one text box. The frame now
        // scrolls with the General page's Advanced section, so the hit test follows it, and only
        // counts while it is actually within the scrolling viewport.
        if (tab != Tab.GENERAL || relayUrlInput == null) {
            return false;
        }
        int cx = this.width / 2;
        int frameY = sy(ADVANCED_ROW_Y);
        if (frameY < TAB_BAR_H || frameY + BUTTON_H > viewportBottom()) {
            return false;
        }
        if (event.x() >= cx - 205 && event.x() < cx - 205 + RELAY_BOX_W
                && event.y() >= frameY && event.y() < frameY + BUTTON_H) {
            this.setFocused(relayUrlInput);
            return true;
        }
        return false;
    }

    /**
     * Scroll the current page. Only the area between the pinned bars scrolls.
     *
     * <p>The page takes the wheel <b>before</b> any widget under the cursor. Deferring to
     * {@code super} first — vanilla's convention, and what this used to do — meant scrolling past a
     * toggle or slider fed the notch to that widget instead: the cursor happened to be over a
     * control, so scrolling the page silently changed a setting. On a page that is mostly controls
     * there is nowhere safe to put the cursor, which makes the wheel actively dangerous.
     *
     * <p>Widgets still get the wheel when the page cannot use it (already at an end, or the cursor
     * is outside the scrolling area), so a slider is still adjustable by wheel where that cannot be
     * confused with scrolling.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() == 0 || mouseY < TAB_BAR_H || mouseY > viewportBottom()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
