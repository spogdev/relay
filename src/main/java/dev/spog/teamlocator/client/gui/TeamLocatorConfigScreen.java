package dev.spog.teamlocator.client.gui;

import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.config.TrustEntry;
import dev.spog.teamlocator.client.net.NameLookup;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.Click;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.entity.player.SkinTextures;

import java.util.List;
import java.util.UUID;

/**
 * Hand-rolled config screen (no cloth-config), split across two tabs: Settings (sharing, HUD,
 * alerts, Xaero's) and Trust List (the active mode's players, with per-player alert and visibility
 * toggles). Any change is written to disk and pushed to the relay immediately via
 * {@link TeamLocatorClient#syncToServer()}.
 *
 * <p>The tab bar and the bottom bar (relay address + Done) are fixed; only the area between them
 * scrolls, and in practice only the trust list is long enough to need it.
 */
@Environment(EnvType.CLIENT)
public class TeamLocatorConfigScreen extends Screen {
    /** Which page is showing. Each tab keeps its own scroll offset. */
    private enum Tab { SETTINGS, TRUST }

    private final Screen parent;
    private final TeamConfig config;
    private Tab tab = Tab.SETTINGS;

    private static final String SCHEME_LABEL = "wss://";
    private static final Identifier TEXT_FIELD_SPRITE =
            Identifier.of("minecraft:widget/text_field");
    private static final Identifier TEXT_FIELD_HIGHLIGHTED_SPRITE =
            Identifier.of("minecraft:widget/text_field_highlighted");

    /**
     * How far the page is scrolled, in content pixels. Widgets are laid out at their natural Y and
     * then shifted up by this, so a window too short for the settings (or a long trust list) can
     * still reach everything — previously anything past the window's height was simply unreachable.
     */
    private int scroll;
    /** Parked scroll offsets, so switching tabs and back returns to where you were. */
    private int settingsScroll;
    private int trustScroll;
    /** Total content height, measured during the last {@link #init()}; drives the scroll clamp. */
    private int contentHeight;
    private TextFieldWidget nameInput;
    private TextFieldWidget relayUrlInput;
    private TextFieldWidget primaryColorInput;
    private TextFieldWidget secondaryColorInput;
    /** URL as it was when the screen opened, to detect a change on close and reconnect. */
    private final String initialRelayUrl;
    /** Feedback for an in-flight or failed Mojang name lookup; null when idle. */
    private Text addStatus;
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

    /** Content Y of the settings page's last row (Xaero's); drives that page's scroll extent. */
    private static final int SETTINGS_LAST_ROW_Y = 240;
    /** How far the column header's text floats above the first table row. */
    private static final int COL_HEADER_OFFSET = 12;
    /** Gap from one section's last row to the next section's header, on the settings page. */
    private static final int SECTION_GAP = 44;
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
     * <p>Alerts and Visibility are one width: they hold the same ON/OFF, so any difference between
     * them reads as meaning something it doesn't. 45 clears the wider heading — "Visibility" is
     * 39px, narrower than it looks, since four of its ten letters are a 2px {@code i}.
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
        super(Text.translatable("relay.config.title"));
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
    private <T extends net.minecraft.client.gui.Element
            & net.minecraft.client.gui.Drawable
            & net.minecraft.client.gui.Selectable> void addScrolled(int screenY, T widget) {
        if (!visible(screenY)) {
            return;
        }
        addDrawableChild(widget);
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

        // --- Tab bar: pinned above the scrolling area, so switching pages is always reachable.
        // The two tabs meet in the middle and span the page's full width, so the strip reads as one
        // unit sitting on top of the page rather than as two more buttons among the settings.
        addDrawableChild(new TabButton(cx - 205, TAB_BUTTON_Y, 205, BUTTON_H,
                Text.translatable("relay.config.tab.settings"),
                tab == Tab.SETTINGS, () -> switchTab(Tab.SETTINGS)));
        addDrawableChild(new TabButton(cx, TAB_BUTTON_Y, 205, BUTTON_H,
                Text.translatable("relay.config.tab.trust"),
                tab == Tab.TRUST, () -> switchTab(Tab.TRUST)));

        if (tab == Tab.SETTINGS) {
            initSettingsPage(cx);
        } else {
            initTrustPage(cx);
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
        if (tab == Tab.SETTINGS) {
            settingsScroll = scroll;
        } else {
            trustScroll = scroll;
        }
        tab = next;
        scroll = next == Tab.SETTINGS ? settingsScroll : trustScroll;
        addStatus = null; // lookup feedback belongs to the trust page's Add box
        rebuild();
    }

    private void initSettingsPage(int cx) {
        // --- Sharing section: what we send to trusted players. Location is the master coordinate
        // toggle; armor is opt-in and rides the same trust gate relay-side.
        addScrolled(sy(12), CyclingButtonWidget.onOffBuilder(config.globalShareEnabled)
                .build(cx - 205, sy(12), 200, 20, Text.translatable("relay.config.share_location"),
                        (btn, value) -> {
                            config.globalShareEnabled = value;
                            config.save();
                            TeamLocatorClient.syncToServer();
                        }));
        addScrolled(sy(12), CyclingButtonWidget.onOffBuilder(config.shareArmor)
                .build(cx + 5, sy(12), 200, 20, Text.translatable("relay.config.share_armor"),
                        (btn, value) -> {
                            config.shareArmor = value;
                            config.save();
                            // The reporter retracts or re-sends on its next tick; nothing to push here.
                        }));

        // --- HUD section: position sliders side by side, size slider below ---
        addScrolled(sy(56), new HudPositionSlider(cx - 205, sy(56), 200, 20, "HUD X", config.hudX, v -> {
            config.hudX = v;
            config.save();
        }));
        addScrolled(sy(56), new HudPositionSlider(cx + 5, sy(56), 200, 20, "HUD Y", config.hudY, v -> {
            config.hudY = v;
            config.save();
        }));
        addScrolled(sy(80), new HudPositionSlider(cx - 205, sy(80), 200, 20, "HUD Size", 0.5, 2.0,
                config.hudScale, v -> {
            config.hudScale = v;
            config.save();
        }));

        // --- HUD text colors: hex inputs with live swatches (drawn in extractRenderState) ---
        primaryColorInput = new TextFieldWidget(this.textRenderer, cx + 5, sy(80), 70, 20,
                Text.translatable("relay.config.hud_primary_color"));
        primaryColorInput.setTooltip(Tooltip.of(
                Text.translatable("relay.config.hud_primary_color")));
        primaryColorInput.setMaxLength(7);
        primaryColorInput.setText(config.hudPrimaryColor);
        primaryColorInput.setChangedListener(value -> {
            if (TeamConfig.isValidHex(value)) {
                config.hudPrimaryColor = value;
                config.save();
                primaryColorInput.setEditableColor(0xFFFFFFFF);
            } else {
                primaryColorInput.setEditableColor(0xFFFF5555);
            }
        });
        addScrolled(sy(80), primaryColorInput);
        secondaryColorInput = new TextFieldWidget(this.textRenderer, cx + 107, sy(80), 70, 20,
                Text.translatable("relay.config.hud_secondary_color"));
        secondaryColorInput.setTooltip(Tooltip.of(
                Text.translatable("relay.config.hud_secondary_color")));
        secondaryColorInput.setMaxLength(7);
        secondaryColorInput.setText(config.hudSecondaryColor);
        secondaryColorInput.setChangedListener(value -> {
            if (TeamConfig.isValidHex(value)) {
                config.hudSecondaryColor = value;
                config.save();
                secondaryColorInput.setEditableColor(0xFFFFFFFF);
            } else {
                secondaryColorInput.setEditableColor(0xFFFF5555);
            }
        });
        addScrolled(sy(80), secondaryColorInput);

        // HUD row 3: row text alignment | list growth direction (down vs. up from the anchor).
        addScrolled(sy(104), CyclingButtonWidget.<TeamConfig.HudAlign>builder(this::hudAlignLabel, config.hudAlign)
                .values(TeamConfig.HudAlign.LEFT, TeamConfig.HudAlign.CENTER, TeamConfig.HudAlign.RIGHT)
                .build(cx - 205, sy(104), 200, 20, Text.translatable("relay.config.hud_align"),
                        (btn, value) -> {
                            config.hudAlign = value;
                            config.save();
                        }));
        addScrolled(sy(104), CyclingButtonWidget.onOffBuilder(config.hudGrowUp)
                .build(cx + 5, sy(104), 200, 20, Text.translatable("relay.config.hud_grow_up"),
                        (btn, value) -> {
                            config.hudGrowUp = value;
                            config.save();
                        }));

        // HUD row 4: what each row displays. Armor only ever shows what a teammate shares.
        addScrolled(sy(128), CyclingButtonWidget.onOffBuilder(config.hudShowCoords)
                .build(cx - 205, sy(128), 200, 20, Text.translatable("relay.config.hud_show_coords"),
                        (btn, value) -> {
                            config.hudShowCoords = value;
                            config.save();
                        }));
        addScrolled(sy(128), CyclingButtonWidget
                .<TeamConfig.ArmorDisplay>builder(this::armorDisplayLabel, config.hudArmorDisplay)
                .values(TeamConfig.ArmorDisplay.OFF, TeamConfig.ArmorDisplay.ALL,
                        TeamConfig.ArmorDisplay.LOWEST)
                .build(cx + 5, sy(128), 200, 20, Text.translatable("relay.config.hud_show_armor"),
                        (btn, value) -> {
                            config.hudArmorDisplay = value;
                            config.save();
                        }));

        // --- Pings section: cross-server pings toggle | ping display cooldown ---
        addScrolled(sy(172), CyclingButtonWidget.onOffBuilder(config.crossServerPings)
                .build(cx - 205, sy(172), 200, 20, Text.translatable("relay.config.cross_server_pings"),
                        (btn, value) -> {
                            config.crossServerPings = value;
                            config.save();
                        }));
        addScrolled(sy(172), new SecondsSlider(cx + 5, sy(172), 200, 20, "Alert Cooldown", 0, 60,
                config.pingCooldownSeconds, v -> {
            config.pingCooldownSeconds = v;
            config.save();
        }));
        // Alerts row 2: choose the alert sound (new alarm vs. the old 3-noteblock chord).
        addScrolled(sy(196), CyclingButtonWidget.<TeamConfig.AlertSound>builder(this::alertSoundLabel, config.alertSound)
                .values(TeamConfig.AlertSound.ALARM, TeamConfig.AlertSound.NOTEBLOCKS)
                .build(cx - 205, sy(196), 200, 20, Text.translatable("relay.config.alert_sound"),
                        (btn, value) -> {
                            config.alertSound = value;
                            config.save();
                        }));

        // --- Xaero's section: map icons | in-world icons (read live by the Xaero trackers) ---
        addScrolled(sy(SETTINGS_LAST_ROW_Y), CyclingButtonWidget.onOffBuilder(config.xaeroMapIcons)
                .build(cx - 205, sy(SETTINGS_LAST_ROW_Y), 200, 20, Text.translatable("relay.config.xaero_map_icons"),
                        (btn, value) -> {
                            config.xaeroMapIcons = value;
                            config.save();
                        }));
        addScrolled(sy(SETTINGS_LAST_ROW_Y), CyclingButtonWidget.onOffBuilder(config.xaeroInWorldIcons)
                .build(cx + 5, sy(SETTINGS_LAST_ROW_Y), 200, 20, Text.translatable("relay.config.xaero_world_icons"),
                        (btn, value) -> {
                            config.xaeroInWorldIcons = value;
                            config.save();
                        }));

        contentHeight = SETTINGS_LAST_ROW_Y + 20 + 4; // last row, its height, and a little padding
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
        CyclingButtonWidget<TeamConfig.Mode> modeButton = CyclingButtonWidget
                .<TeamConfig.Mode>builder(this::modeLabel, config.activeMode)
                .values(TeamConfig.Mode.GLOBAL, TeamConfig.Mode.SERVER)
                .build(cx - 205, sy(0), 200, BUTTON_H,
                        Text.translatable("relay.config.active_list"),
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
        nameInput = new TextFieldWidget(this.textRenderer, cx + 5, sy(0), 130, BUTTON_H,
                Text.translatable("relay.config.add_player"));
        nameInput.setPlaceholder(Text.translatable("relay.config.add_player"));
        nameInput.setMaxLength(16);
        addScrolled(sy(0), nameInput);
        addScrolled(sy(0), ButtonWidget.builder(Text.translatable("relay.config.add"),
                b -> addTypedPlayer()).dimensions(cx + 140, sy(0), 65, BUTTON_H).build());

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
        // --- Relay address: one Done-sized frame with the fixed wss:// scheme drawn inside ---
        // The TextFieldWidget itself is borderless and sits inside the frame, after the scheme label.
        int frameX = cx - 205;
        int frameY = this.height - 28;
        int textStart = frameX + 4 + this.textRenderer.getWidth(SCHEME_LABEL);
        relayUrlInput = new TextFieldWidget(this.textRenderer, textStart, frameY + 6,
                frameX + 200 - 4 - textStart, 12, Text.translatable("relay.config.relay_url"));
        relayUrlInput.setDrawsBackground(false);
        relayUrlInput.setMaxLength(256);
        relayUrlInput.setText(config.relayUrl);
        relayUrlInput.setChangedListener(value -> {
            config.relayUrl = TeamConfig.normalizeRelayAddress(value);
            config.save();
        });
        addDrawableChild(relayUrlInput);

        // --- Done ---
        addDrawableChild(ButtonWidget.builder(Text.translatable("relay.config.done"),
                b -> close()).dimensions(cx + 5, this.height - 28, 200, 20).build());
    }

    private void buildRow(int cx, int y, TrustEntry entry, List<TrustEntry> backing) {
        // Bare ON/OFF, not composeToggleText's "<label>: <on|off>" — the column header names the toggle
        // once for the whole table, so repeating it on every row is noise. Still vanilla's own
        // components, so a pack restyling options.on/off (into check/X glyphs, say) reaches these.
        addScrolled(y, ButtonWidget.builder(onOff(!entry.mutePings),
                b -> {
                    entry.mutePings = !entry.mutePings;
                    config.save();
                    rebuild();
                }).dimensions(cx + COL_ALERTS_X, y, COL_ALERTS_W, 20).build());
        // Toggle whether this player sees us at all: hiding withholds the whole shared feed —
        // coordinates and armor both — since armor rides the same sharing set relay-side.
        addScrolled(y, ButtonWidget.builder(onOff(!entry.hidden),
                b -> {
                    entry.hidden = !entry.hidden;
                    config.save();
                    TeamLocatorClient.syncToServer();
                    rebuild();
                }).dimensions(cx + COL_VISIBILITY_X, y, COL_VISIBILITY_W, 20).build());
        addScrolled(y, ButtonWidget.builder(Text.translatable("relay.config.remove"),
                b -> {
                    backing.remove(entry);
                    config.save();
                    TeamLocatorClient.syncToServer();
                    rebuild();
                }).dimensions(cx + COL_REMOVE_X, y, COL_REMOVE_W, 20).build());
    }

    /** Vanilla's own On/Off components, so a resource pack restyling options.on/off reaches us. */
    private static Text onOff(boolean on) {
        return on ? ScreenTexts.ON : ScreenTexts.OFF;
    }

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;

        // Scrolling labels first, then the bars' backdrops over them, then super's widgets on top:
        // that ordering lets the backdrops hide a label that has scrolled into a bar, while the tab
        // buttons and the borderless address TextFieldWidget still land above their own backdrop.
        if (tab == Tab.SETTINGS) {
            drawSettingsLabels(graphics, cx);
        } else {
            drawTrustLabels(graphics, cx);
        }
        drawBarBackdrops(graphics, cx);

        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, TITLE_Y, 0xFFFFFFFF);
        drawScrollbar(graphics);
    }

    /**
     * The two pinned bars' backdrops, plus the relay-address frame that the borderless TextFieldWidget sits
     * inside. Drawn before {@code super} so the widgets on the bars land on top of them; the
     * backdrops cover scrolled *labels*, while scrolled widgets are culled instead (see
     * {@link #addScrolled}), since nothing this screen draws can cover them.
     */
    private void drawBarBackdrops(DrawContext graphics, int cx) {
        // No divider under the tab strip: the selected tab's open bottom edge is what joins it to
        // the page, and a rule across there would cut that connection. The strip's own borders
        // already separate it from the content.
        graphics.fill(0, 0, this.width, TAB_BAR_H, 0xFF101010);

        int top = this.height - BOTTOM_BAR_H;
        graphics.fill(0, top, this.width, this.height, 0xFF101010);
        graphics.fill(0, top, this.width, top + 1, 0xFF000000);
        graphics.drawGuiTexture(RenderPipelines.GUI_TEXTURED,
                relayUrlInput != null && relayUrlInput.isFocused()
                        ? TEXT_FIELD_HIGHLIGHTED_SPRITE : TEXT_FIELD_SPRITE,
                cx - 205, this.height - 28, 200, 20);
        graphics.drawText(this.textRenderer, SCHEME_LABEL, cx - 205 + 4, this.height - 28 + 6, 0xFFA0A0A0, false);
    }

    private void drawSettingsLabels(DrawContext graphics, int cx) {
        // Live color swatches beside the HUD hex inputs: white border, current color inside.
        drawSwatch(graphics, cx + 78, sy(80), config.hudPrimaryArgb());
        drawSwatch(graphics, cx + 180, sy(80), config.hudSecondaryArgb());

        // Section headers, scrolling with the widgets they label.
        graphics.drawText(this.textRenderer, Text.translatable("relay.config.section.sharing"),
                cx - 205, sy(0), 0xFFFFFFFF, false);
        graphics.drawText(this.textRenderer, Text.translatable("relay.config.section.hud"),
                cx - 205, sy(44), 0xFFFFFFFF, false);
        graphics.drawText(this.textRenderer, Text.translatable("relay.config.section.pings"),
                cx - 205, sy(160), 0xFFFFFFFF, false);
        graphics.drawText(this.textRenderer, Text.translatable("relay.config.section.xaero"),
                cx - 205, sy(228), 0xFFFFFFFF, false);
    }

    private void drawTrustLabels(DrawContext graphics, int cx) {
        if (config.activeMode == TeamConfig.Mode.SERVER && TeamConfig.currentServerKey() == null) {
            graphics.drawText(this.textRenderer, Text.translatable("relay.config.no_server"),
                    cx - 205, sy(LIST_TOP + 4), 0xFFFF5555, false);
            return;
        }

        // Column headers, naming each toggle once for the whole table instead of on every row.
        // Each is centered over its column so it reads as a heading for the buttons beneath it.
        int headerY = sy(LIST_TOP - COL_HEADER_OFFSET);
        boolean headerVisible = headerY >= TAB_BAR_H && headerY + this.textRenderer.fontHeight <= viewportBottom();
        if (headerVisible) {
            graphics.drawText(this.textRenderer, Text.translatable("relay.config.column.player"),
                    cx - 205, headerY, 0xFFA0A0A0, false);
            drawColumnHeader(graphics, "relay.config.column.alerts",
                    cx + COL_ALERTS_X, COL_ALERTS_W, headerY);
            // Lookup feedback shares this row, right-aligned where the Remove column sits. It
            // replaces the Visibility heading rather than overlapping it — the status is transient
            // and the columns beneath it stay self-evident for the few seconds it shows.
            if (addStatus != null) {
                graphics.drawText(this.textRenderer, addStatus, cx + 205 - this.textRenderer.getWidth(addStatus),
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

    /** One column heading, centered over a column of the given x/width. */
    private void drawColumnHeader(DrawContext graphics, String key, int colX, int colW,
                                  int y) {
        graphics.drawCenteredTextWithShadow(this.textRenderer, Text.translatable(key), colX + colW / 2, y, 0xFFA0A0A0);
    }

    /** A slim scrollbar down the right edge, so the page's length and position are visible. */
    private void drawScrollbar(DrawContext graphics) {
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
    private static void drawSwatch(DrawContext graphics, int x, int y, int argb) {
        graphics.fill(x, y, x + 20, y + 20, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 19, y + 19, argb);
    }

    private void drawFaceAndName(DrawContext graphics, int x, int y, TrustEntry entry) {
        UUID id = safeUuid(entry);
        SkinTextures skin;
        String name = entry.name != null ? entry.name : (id != null ? id.toString().substring(0, 8) : "?");
        if (id != null && MinecraftClient.getInstance().getNetworkHandler() != null) {
            PlayerListEntry info = MinecraftClient.getInstance().getNetworkHandler().getPlayerListEntry(id);
            skin = info != null ? info.getSkinTextures() : DefaultSkinHelper.getSkinTextures(id);
            if (info != null) {
                name = info.getProfile().name();
            }
        } else {
            skin = DefaultSkinHelper.getSteve();
        }
        PlayerSkinDrawer.draw(graphics, skin, x, y + 2, 16);
        graphics.drawText(this.textRenderer, name, x + 22, y + 6, 0xFFFFFFFF, false);
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
        String typed = nameInput.getText().trim();
        if (typed.isEmpty()) {
            return;
        }
        nameInput.setText("");

        PlayerListEntry info = MinecraftClient.getInstance().getNetworkHandler() != null
                ? MinecraftClient.getInstance().getNetworkHandler().getPlayerListEntry(typed) : null;
        if (info != null) {
            addEntry(info.getProfile().id(), info.getProfile().name());
            rebuild();
            return;
        }

        addStatus = Text.translatable("relay.config.lookup_pending", typed);
        addStatusColor = 0xFFA0A0A0;
        NameLookup.resolve(typed).whenComplete((resolved, err) ->
                MinecraftClient.getInstance().execute(() -> {
                    if (resolved == null || err != null) {
                        addStatus = Text.translatable("relay.config.lookup_failed", typed);
                        addStatusColor = 0xFFFF5555;
                        return;
                    }
                    addStatus = null;
                    addEntry(resolved.id(), resolved.name());
                    if (MinecraftClient.getInstance().currentScreen == this) {
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

    private Text modeLabel(TeamConfig.Mode mode) {
        // CyclingButtonWidget already renders "<name>: <value>", so return only the value here.
        String key = mode == TeamConfig.Mode.GLOBAL
                ? "relay.config.active_list.global" : "relay.config.active_list.server";
        return Text.translatable(key);
    }

    private Text hudAlignLabel(TeamConfig.HudAlign align) {
        // CyclingButtonWidget already renders "<name>: <value>", so return only the value here.
        String key = switch (align) {
            case CENTER -> "relay.config.hud_align.center";
            case RIGHT -> "relay.config.hud_align.right";
            default -> "relay.config.hud_align.left";
        };
        return Text.translatable(key);
    }

    /**
     * On/Off reuse vanilla's own components rather than mod-local copies, so a resource pack that
     * restyles {@code options.on}/{@code options.off} (into check/X glyphs, say) restyles these
     * too — an "Off" of our own would silently opt out of that. Only LOWEST, which vanilla has no
     * word for, is ours.
     */
    private Text armorDisplayLabel(TeamConfig.ArmorDisplay display) {
        return switch (display) {
            case OFF -> ScreenTexts.OFF;
            case LOWEST -> Text.translatable("relay.config.hud_show_armor.lowest");
            default -> ScreenTexts.ON;
        };
    }

    private Text alertSoundLabel(TeamConfig.AlertSound sound) {
        String key = sound == TeamConfig.AlertSound.ALARM
                ? "relay.config.alert_sound.alarm" : "relay.config.alert_sound.noteblocks";
        return Text.translatable(key);
    }

    private static UUID safeUuid(TrustEntry entry) {
        try {
            return entry.uuid();
        } catch (Exception e) {
            return null;
        }
    }

    private void rebuild() {
        this.clearChildren();
        this.init();
    }

    @Override
    public boolean mouseClicked(Click event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        // Clicking the wss:// prefix (inside the frame but left of the borderless TextFieldWidget) should
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
     * Scroll the current page. Only the area between the pinned bars scrolls.
     *
     * <p>Sliders swallow the wheel to change their value, so this only sees notches not consumed by
     * a widget under the cursor; that is vanilla's own convention.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (maxScroll() == 0 || mouseY < TAB_BAR_H || mouseY > viewportBottom()) {
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
    public void close() {
        config.save();
        if (!config.relayUrl.equals(initialRelayUrl)) {
            TeamLocatorClient.connectRelay();
        }
        this.client.setScreen(parent);
    }
}
