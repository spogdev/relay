package dev.spog.teamlocator.client.gui;

import dev.spog.teamlocator.client.PingPalette;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.compat.xaero.XaeroCompat;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.config.TrustEntry;
import dev.spog.teamlocator.client.net.NameLookup;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Relay's settings, drawn in the panel style: a dimmed backdrop, a row of tabs and one bordered,
 * scrollable card per tab.
 *
 * <p>Rows are painted immediate-mode rather than built from widgets, with a {@link Zone} list
 * rebuilt every frame so painting and hit-testing cannot drift apart. The exceptions are the text
 * fields and the Done button, which stay real widgets because vanilla's own focus, caret and
 * narration handling is not worth reimplementing.
 *
 * <p>Enum settings are {@link Dropdown}s rather than cycle buttons: a cycle button shows one option
 * at a time, so choosing means clicking through the values you do not want, while a dropdown shows
 * the whole set and marks the current one.
 */
@Environment(EnvType.CLIENT)
public class TeamLocatorConfigScreen extends Screen {
    /** Which page is showing. Each tab keeps its own scroll offset. */
    private enum Tab {
        GENERAL("relay.config.tab.general"),
        HUD("relay.config.tab.hud"),
        INTEGRATIONS("relay.config.tab.integrations"),
        TRUST("relay.config.tab.trust");

        private final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private static final int MARGIN = 10;
    private static final int CARD_PADDING = 12;
    private static final int ROW_HEIGHT = 22;
    private static final int TAB_HEIGHT = 22;
    private static final int LABEL_COLOR = 0xFFB9C4D0;
    private static final int MUTED_COLOR = 0xFF6C7683;
    private static final int CARD_FILL = 0x50161B22;
    private static final int CARD_BORDER = 0x70323B47;
    private static final int ACCENT = 0xFF6FC3E8;
    private static final int TITLE_COLOR = 0xFFFFFFFF;

    /** Left edge of every control column, measured from the card's inner left edge. */
    private static final int CONTROL_X = 150;
    private static final int CONTROL_W = 130;
    private static final int TOGGLE_W = 52;
    /** Height reserved under the bottom bar for the Done button. */
    private static final int BOTTOM_BAR = 40;
    private static final int SCROLL_STEP = 12;
    private static final int TOOLTIP_PADDING = 6;
    private static final int TOOLTIP_MAX_W = 220;

    private static final String SCHEME_LABEL = "wss://";
    private static final int RELAY_BOX_W = 200;

    private final Screen parent;
    private final TeamConfig config;
    private Tab active = Tab.GENERAL;

    /** Click targets rebuilt every frame, so painting and hit-testing agree. */
    private final List<Zone> zones = new ArrayList<>();
    /** Hover text for the row under the cursor this frame, or null. */
    private Component hoverTooltip;
    /**
     * The slider being dragged, held from press to release.
     *
     * <p>Tracking it is what lets a drag run past the bar's ends: testing {@code contains} on every
     * drag event (the old behaviour) dropped the drag the moment the cursor left the bar, so the
     * last few pixels at either end were unreachable -- the pointer slipped off before the value
     * could get there. The zone keeps receiving the cursor's x and clamps it itself.
     */
    private java.util.function.IntConsumer draggingSlider;

    private int scroll;
    private int contentHeight;
    /** The scrolling area's bounds this frame, so field rows can clip themselves to it. */
    private int viewTop;
    private int viewBottom;
    /** Largest valid scroll offset, refreshed each frame for the input handler. */
    private int maxScroll;

    private Dropdown<TeamConfig.HudAlign> hudAlign;
    private Dropdown<TeamConfig.ArmorDisplay> armorDisplay;
    private Dropdown<TeamConfig.DurabilityDisplay> durabilityDisplay;
    private Dropdown<TeamConfig.AlertSound> alertSound;
    private Dropdown<TeamConfig.Mode> activeMode;
    private Dropdown<Integer> pingColor;

    private EditBox nameInput;
    private EditBox relayUrlInput;
    private EditBox chatPrefixInput;
    private EditBox primaryColorInput;
    private EditBox secondaryColorInput;
    private PanelButton doneButton;
    private PanelButton addButton;

    /** URL as it was when the screen opened, to detect a change on close and reconnect. */
    private final String initialRelayUrl;
    /** Feedback for an in-flight or failed Mojang name lookup; null when idle. */
    private Component addStatus;
    private int addStatusColor;

    public TeamLocatorConfigScreen(Screen parent, TeamConfig config) {
        super(Component.translatable("relay.config.title"));
        this.parent = parent;
        this.config = config;
        this.initialRelayUrl = config.relayUrl;
    }

    @Override
    protected void init() {
        zones.clear();
        buildDropdowns();

        int right = this.width - MARGIN;
        int bottom = this.height - MARGIN;

        doneButton = addRenderableWidget(new PanelButton(
                right - CARD_PADDING - 90, bottom - CARD_PADDING - 20, 90, 20,
                Component.translatable("relay.config.done"), b -> onClose()));

        // Text fields are real widgets: vanilla's caret, selection and clipboard handling is not
        // worth reimplementing immediate-mode. They are positioned per-frame in the draw pass, which
        // is what lets them scroll with the rows around them.
        primaryColorInput = colorField(config.hudPrimaryColor, "relay.config.hud_primary_color",
                value -> {
                    config.hudPrimaryColor = value;
                    config.save();
                });
        secondaryColorInput = colorField(config.hudSecondaryColor, "relay.config.hud_secondary_color",
                value -> {
                    config.hudSecondaryColor = value;
                    config.save();
                });

        chatPrefixInput = new EditBox(this.font, 0, -100, 60, 20,
                Component.translatable("relay.config.chat_prefix"));
        chatPrefixInput.setBordered(false);
        chatPrefixInput.setMaxLength(1);
        chatPrefixInput.setValue(config.chatPrefix);
        chatPrefixInput.setResponder(value -> {
            config.chatPrefix = value;
            config.save();
        });
        addRenderableWidget(chatPrefixInput);

        int textStart = 4 + this.font.width(SCHEME_LABEL);
        relayUrlInput = new EditBox(this.font, textStart, -100, RELAY_BOX_W - 4 - textStart, 12,
                Component.translatable("relay.config.relay_url"));
        relayUrlInput.setBordered(false);
        relayUrlInput.setMaxLength(256);
        relayUrlInput.setValue(config.relayUrl);
        relayUrlInput.setResponder(value -> {
            config.relayUrl = TeamConfig.normalizeRelayAddress(value);
            config.save();
        });
        addRenderableWidget(relayUrlInput);

        nameInput = new EditBox(this.font, 0, -100, 130, 20,
                Component.translatable("relay.config.add_player"));
        nameInput.setBordered(false);
        nameInput.setHint(Component.translatable("relay.config.add_player"));
        nameInput.setMaxLength(16);
        addRenderableWidget(nameInput);

        addButton = addRenderableWidget(new PanelButton(0, -100, 65, 20,
                Component.translatable("relay.config.add"), b -> addTypedPlayer()));
    }

    private EditBox colorField(String initial, String key, Consumer<String> onChange) {
        EditBox box = new EditBox(this.font, 0, -100, 70, 20, Component.translatable(key));
        // Borderless: the frame is drawn by fieldFrame() inside the scissored pass, so it matches
        // the panel and cannot escape the card while scrolling.
        box.setBordered(false);
        box.setMaxLength(7);
        box.setValue(initial);
        box.setResponder(onChange);
        addRenderableWidget(box);
        return box;
    }

    private void buildDropdowns() {
        hudAlign = new Dropdown<>(value -> {
            config.hudAlign = value;
            config.save();
            click();
        });
        armorDisplay = new Dropdown<>(value -> {
            config.hudArmorDisplay = value;
            config.save();
            click();
        });
        durabilityDisplay = new Dropdown<>(value -> {
            config.hudDurabilityDisplay = value;
            config.save();
            click();
        });
        alertSound = new Dropdown<>(value -> {
            config.alertSound = value;
            config.save();
            click();
        });
        activeMode = new Dropdown<>(value -> {
            config.activeMode = value;
            config.rememberActiveMode();
            config.save();
            TeamLocatorClient.syncToServer();
            click();
        });
        pingColor = new Dropdown<>(value -> {
            config.pingColorIndex = value;
            config.save();
            click();
        });
    }

    /** Vanilla's UI click, so the panel feels like the rest of the game. */
    private void click() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0xC00B0E13);
        zones.clear();
        hoverTooltip = null;
        // Parked off-screen each frame; the page that owns one moves it into place as it draws. A
        // field belonging to another tab therefore stays invisible and unclickable.
        parkFields();

        int left = MARGIN;
        int top = MARGIN;
        int right = this.width - MARGIN;
        int bottom = this.height - MARGIN;

        drawFrame(graphics, left, top, right, top + TAB_HEIGHT + CARD_PADDING);
        drawTabs(graphics, left, top, mouseX, mouseY);

        int bodyTop = top + TAB_HEIGHT + CARD_PADDING + 6;
        drawFrame(graphics, left, bodyTop, right, bottom);

        // Scroll the body, then clamp so a short page cannot drift off.
        int viewHeight = bottom - bodyTop - BOTTOM_BAR;
        int originY = bodyTop - scroll;
        viewTop = bodyTop + 1;
        viewBottom = bodyTop + viewHeight;

        graphics.enableScissor(left + 1, bodyTop + 1, right - 1, bodyTop + viewHeight);
        contentHeight = switch (active) {
            case GENERAL -> drawGeneral(graphics, left, originY, right, mouseX, mouseY);
            case HUD -> drawHud(graphics, left, originY, right, mouseX, mouseY);
            case INTEGRATIONS -> drawIntegrations(graphics, left, originY, right, mouseX, mouseY);
            case TRUST -> drawTrust(graphics, left, originY, right, mouseX, mouseY);
        };
        graphics.disableScissor();

        // Clamping here as well as on input keeps a resize or a tab switch from leaving the view
        // scrolled past the end.
        maxScroll = Math.max(0, contentHeight - viewHeight);
        scroll = Math.clamp(scroll, 0, maxScroll);

        if (contentHeight > viewHeight) {
            drawScrollbar(graphics, right - 5, bodyTop + 4, viewHeight, contentHeight);
        }

        // The text fields are real widgets, so vanilla paints them here -- after the rows' scissor
        // was released. Without re-clipping, a field scrolled past the card kept drawing over the tab
        // bar and the Done button. The bottom bar's own widgets are drawn outside this pass, below.
        // Done sits on the bottom bar, outside the scrolling area, so it is held out of the clipped
        // pass and drawn on its own afterwards rather than being painted twice.
        if (doneButton != null) {
            doneButton.visible = false;
        }
        graphics.enableScissor(left + 1, viewTop, right - 1, viewBottom);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.disableScissor();
        if (doneButton != null) {
            doneButton.visible = true;
            doneButton.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        // Open dropdowns paint last so they overlap the rows beneath them.
        drawOpenDropdowns(graphics, mouseX, mouseY);

        // Zones are rebuilt by the page draw above, so the hovered one is known by now. Dropdowns
        // take precedence: an open list covers the rows beneath it.
        applyCursor(graphics, mouseX, mouseY);

        if (hoverTooltip != null) {
            drawPanelTooltip(graphics, hoverTooltip, mouseX, mouseY);
        }
    }

    /**
     * Move every text field off-screen. Each page repositions the ones it owns as it draws, so a
     * field from another tab is neither drawn nor clickable — the fields are real widgets and would
     * otherwise persist across tab switches.
     */
    private void parkFields() {
        for (EditBox box : new EditBox[]{primaryColorInput, secondaryColorInput, chatPrefixInput,
                relayUrlInput, nameInput}) {
            if (box != null) {
                box.setY(-100);
                // Hidden until the page that owns it places it. A field left visible on another tab
                // would still take the caret and swallow typing.
                box.visible = false;
            }
        }
        if (addButton != null) {
            addButton.setY(-100);
            addButton.visible = false;
        }
    }

    /**
     * Show a field only while its row is inside the scrolling viewport, and drop focus when it
     * leaves. The scissor stops a scrolled-away field being drawn over the bars, but on its own it
     * would leave an invisible field holding the caret.
     */
    private void placeField(EditBox box, int x, int y, int width) {
        boolean onScreen = y + 20 > viewTop && y < viewBottom;
        box.visible = onScreen;
        if (!onScreen) {
            box.setY(-100);
            if (getFocused() == box) {
                setFocused(null);
            }
            return;
        }
        box.setX(x);
        box.setY(y);
        box.setWidth(width);
    }

    private void drawOpenDropdowns(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        switch (active) {
            case GENERAL -> {
                alertSound.drawOverlay(graphics, this.font, config.alertSound, mouseX, mouseY);
                pingColor.drawOverlay(graphics, this.font, config.pingColorIndex, mouseX, mouseY);
            }
            case HUD -> {
                hudAlign.drawOverlay(graphics, this.font, config.hudAlign, mouseX, mouseY);
                armorDisplay.drawOverlay(graphics, this.font, config.hudArmorDisplay, mouseX, mouseY);
                durabilityDisplay.drawOverlay(graphics, this.font, config.hudDurabilityDisplay,
                        mouseX, mouseY);
            }
            case TRUST -> activeMode.drawOverlay(graphics, this.font, config.activeMode, mouseX, mouseY);
            default -> {
            }
        }
    }


    /**
     * The cursor for whatever is under the pointer, requested once per frame.
     *
     * <p>Widgets do this themselves in {@code AbstractWidget.handleCursor}, but the rows here are
     * painted rather than built, so the zone list stands in for that. Checked back-to-front so the
     * most recently drawn zone -- the one painted on top -- wins an overlap.
     */
    private void applyCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (Dropdown<?> dropdown : visibleDropdowns()) {
            if (dropdown.isOpen()) {
                // An open list covers the rows under it; the whole control area is clickable.
                graphics.requestCursor(CursorTypes.POINTING_HAND);
                return;
            }
            if (dropdown.isActive() && dropdown.isHovered(this.font, mouseX, mouseY)) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
                return;
            }
        }
        // Only the scrolling area and the tab strip carry zones; a pointer over the card background
        // keeps the plain arrow.
        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone zone = zones.get(i);
            if (zone.contains(mouseX, mouseY)) {
                graphics.requestCursor(zone.cursor());
                return;
            }
        }
    }

    /**
     * A tooltip in the panel's own style rather than vanilla's purple-bordered box, so hover text
     * reads as part of this screen. Same treatment as the tier menu: card frame, near-opaque fill,
     * and clamped to stay on screen instead of running off an edge.
     */
    private void drawPanelTooltip(GuiGraphicsExtractor graphics, Component text, int mouseX, int mouseY) {
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(text, TOOLTIP_MAX_W);
        if (lines.isEmpty()) {
            return;
        }
        int textWidth = 0;
        for (var line : lines) {
            textWidth = Math.max(textWidth, this.font.width(line));
        }
        int boxWidth = textWidth + TOOLTIP_PADDING * 2;
        int boxHeight = TOOLTIP_PADDING * 2 + lines.size() * (this.font.lineHeight + 2) - 2;

        int boxX = Math.min(mouseX + 12, this.width - boxWidth - 4);
        int boxY = Math.clamp(mouseY - 8, 4, this.height - boxHeight - 4);

        drawFrame(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
        graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);

        int lineY = boxY + TOOLTIP_PADDING;
        for (var line : lines) {
            graphics.text(this.font, line, boxX + TOOLTIP_PADDING, lineY, 0xFFE4EAF2, false);
            lineY += this.font.lineHeight + 2;
        }
    }

    /**
     * The frame a borderless text field sits inside, drawn in the scrolled pass so it clips with its
     * row. Lit while focused, the way the dropdowns light while open.
     */
    private void fieldFrame(GuiGraphicsExtractor graphics, EditBox box, int x, int y, int w) {
        boolean focused = box.isFocused();
        graphics.fill(x, y, x + w, y + 20, focused ? 0xF00E1219 : 0x50161B22);
        int border = focused ? 0xA05B6B7D : CARD_BORDER;
        graphics.fill(x, y, x + w, y + 1, border);
        graphics.fill(x, y + 19, x + w, y + 20, border);
        graphics.fill(x, y, x + 1, y + 20, border);
        graphics.fill(x + w - 1, y, x + w, y + 20, border);
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics, int x, int top, int viewHeight, int total) {
        int thumbHeight = Math.max(16, viewHeight * viewHeight / total);
        int thumbY = top + (viewHeight - thumbHeight) * scroll / Math.max(1, total - viewHeight);
        graphics.fill(x, top, x + 3, top + viewHeight, 0x40202A38);
        graphics.fill(x, thumbY, x + 3, thumbY + thumbHeight, 0x90727F8F);
    }

    private void drawFrame(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        graphics.fill(left, top, right, bottom, CARD_FILL);
        graphics.fill(left, top, right, top + 1, CARD_BORDER);
        graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
        graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
        graphics.fill(right - 1, top, right, bottom, CARD_BORDER);
    }

    private void drawTabs(GuiGraphicsExtractor graphics, int left, int top, int mouseX, int mouseY) {
        int x = left + CARD_PADDING;
        int y = top + CARD_PADDING / 2;

        for (Tab tab : Tab.values()) {
            Component label = Component.translatable(tab.key);
            int tabWidth = this.font.width(label) + 20;
            boolean selected = tab == active;
            boolean hovered = mouseX >= x && mouseX <= x + tabWidth
                    && mouseY >= y && mouseY <= y + TAB_HEIGHT;

            if (selected) {
                graphics.fill(x, y, x + tabWidth, y + TAB_HEIGHT, 0x6022303F);
                graphics.fill(x, y + TAB_HEIGHT - 2, x + tabWidth, y + TAB_HEIGHT, ACCENT);
            } else if (hovered) {
                graphics.fill(x, y, x + tabWidth, y + TAB_HEIGHT, 0x3016202B);
            }

            graphics.text(this.font, label,
                    x + (tabWidth - this.font.width(label)) / 2,
                    y + (TAB_HEIGHT - this.font.lineHeight) / 2,
                    selected ? TITLE_COLOR : (hovered ? LABEL_COLOR : MUTED_COLOR), false);

            zones.add(new Zone(x, y, x + tabWidth, y + TAB_HEIGHT, () -> {
                active = tab;
                scroll = 0;
                closeDropdowns();
                setFocused(null);
                click();
            }));
            x += tabWidth + 4;
        }
    }

    // ------------------------------------------------------------------- pages

    /** Sharing, alerts, pings, chat and the relay address. */
    private int drawGeneral(GuiGraphicsExtractor graphics, int left, int top, int right,
                            int mouseX, int mouseY) {
        int x = left + CARD_PADDING;
        int y = top + CARD_PADDING;

        y = header(graphics, "relay.config.section.sharing",
                "relay.config.section.sharing.desc", x, y);
        y = toggle(graphics, "relay.config.share_location", "relay.config.share_location.desc",
                config.globalShareEnabled, x, y, mouseX, mouseY, () -> {
                    config.globalShareEnabled = !config.globalShareEnabled;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });
        y = toggle(graphics, "relay.config.share_armor", "relay.config.share_armor.desc",
                config.shareArmor, x, y, mouseX, mouseY, () -> {
                    config.shareArmor = !config.shareArmor;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });
        y = toggle(graphics, "relay.config.share_health", "relay.config.share_health.desc",
                config.shareHealth, x, y, mouseX, mouseY, () -> {
                    config.shareHealth = !config.shareHealth;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });

        y += 10;
        y = header(graphics, "relay.config.section.pings", "relay.config.section.pings.desc", x, y);
        y = toggle(graphics, "relay.config.enable_alerts", null, !config.hideAllAlerts,
                x, y, mouseX, mouseY, () -> {
                    config.hideAllAlerts = !config.hideAllAlerts;
                    config.save();
                });
        y = toggle(graphics, "relay.config.cross_server_pings", "relay.config.cross_server_pings.desc",
                config.crossServerPings, x, y, mouseX, mouseY, () -> {
                    config.crossServerPings = !config.crossServerPings;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });

        List<Dropdown.Entry<TeamConfig.AlertSound>> sounds = new ArrayList<>();
        for (TeamConfig.AlertSound value : TeamConfig.AlertSound.values()) {
            sounds.add(new Dropdown.Entry<>(value, alertSoundLabel(value).getString()));
        }
        y = dropdownRow(graphics, "relay.config.alert_sound", null, alertSound, sounds,
                config.alertSound, x, y, mouseX, mouseY);

        y = sliderRow(graphics, "relay.config.alert_cooldown", "relay.config.alert_cooldown.desc",
                config.pingCooldownSeconds, SecondsSlider.COOLDOWN_STEPS,
                x, y, mouseX, mouseY, v -> {
                    config.pingCooldownSeconds = v;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });

        y += 10;
        y = header(graphics, "relay.config.section.map_pings",
                "relay.config.section.map_pings.desc", x, y);
        y = toggle(graphics, "relay.config.show_pings", null, config.showPings,
                x, y, mouseX, mouseY, () -> {
                    config.showPings = !config.showPings;
                    config.save();
                });
        y = toggle(graphics, "relay.config.pings_through_walls",
                "relay.config.pings_through_walls.desc", config.pingsThroughWalls,
                x, y, mouseX, mouseY, () -> {
                    config.pingsThroughWalls = !config.pingsThroughWalls;
                    config.save();
                });
        y = toggle(graphics, "relay.config.ping_sound", "relay.config.ping_sound.desc",
                config.mapPingSound, x, y, mouseX, mouseY, () -> {
                    config.mapPingSound = !config.mapPingSound;
                    config.save();
                });
        y = sliderRow(graphics, "relay.config.ping_duration", null,
                config.pingDisplaySeconds, SecondsSlider.PING_DURATION_STEPS,
                x, y, mouseX, mouseY, v -> {
                    config.pingDisplaySeconds = v;
                    config.save();
                });
        y = sliderRow(graphics, "relay.config.ping_cooldown", "relay.config.ping_cooldown.desc",
                config.mapPingCooldownSeconds, SecondsSlider.PING_COOLDOWN_STEPS,
                x, y, mouseX, mouseY, v -> {
                    config.mapPingCooldownSeconds = v;
                    config.save();
                });

        // Ping colour: the five the account's UUID produces, each shown by name with a swatch.
        List<String> palette = pingPalette();
        if (!palette.isEmpty()) {
            // The colours have no names, so each row is the colour itself rather than an index --
            // the old "2 / 5" told you nothing about what you were picking.
            List<Dropdown.Entry<Integer>> colors = new ArrayList<>();
            for (int i = 0; i < palette.size(); i++) {
                colors.add(new Dropdown.Entry<>(i, "", PingPalette.argb(palette.get(i))));
            }
            y = dropdownRow(graphics, "relay.config.ping_color", "relay.config.ping_color.desc",
                    pingColor, colors, Math.floorMod(config.pingColorIndex, palette.size()),
                    x, y, mouseX, mouseY);
        }

        y += 10;
        y = header(graphics, "relay.config.section.chat", null, x, y);
        y = toggle(graphics, "relay.config.chat_enabled", "relay.config.chat_enabled.desc",
                config.chatEnabled, x, y, mouseX, mouseY, () -> {
                    config.chatEnabled = !config.chatEnabled;
                    config.save();
                });
        y = fieldRow(graphics, "relay.config.chat_prefix", "relay.config.chat_prefix.desc",
                chatPrefixInput, 60, x, y, mouseX, mouseY);

        y += 10;
        y = drawMarkerSection(graphics, x, y, mouseX, mouseY);

        y += 10;
        y = header(graphics, "relay.config.section.advanced", null, x, y);
        y = relayAddressRow(graphics, x, y, mouseX, mouseY);

        return y + CARD_PADDING - top;
    }

    /** Everything the on-screen teammate list shows. */
    private int drawHud(GuiGraphicsExtractor graphics, int left, int top, int right,
                        int mouseX, int mouseY) {
        int x = left + CARD_PADDING;
        int y = top + CARD_PADDING;

        y = header(graphics, "relay.config.section.hud", null, x, y);
        y = toggle(graphics, "relay.config.hud_enabled", "relay.config.hud_enabled.desc",
                config.hudEnabled, x, y, mouseX, mouseY, () -> {
                    config.hudEnabled = !config.hudEnabled;
                    config.save();
                });

        y = doubleSlider(graphics, "relay.config.hud_position", "relay.config.hud_position.desc",
                config.hudX, config.hudY, x, y, mouseX, mouseY,
                v -> {
                    config.hudX = v;
                    config.save();
                },
                v -> {
                    config.hudY = v;
                    config.save();
                });
        y = scaleRow(graphics, "relay.config.hud_scale", null, x, y, mouseX, mouseY);

        List<Dropdown.Entry<TeamConfig.HudAlign>> aligns = new ArrayList<>();
        for (TeamConfig.HudAlign value : TeamConfig.HudAlign.values()) {
            aligns.add(new Dropdown.Entry<>(value, hudAlignLabel(value).getString()));
        }
        y = dropdownRow(graphics, "relay.config.hud_align", null, hudAlign, aligns,
                config.hudAlign, x, y, mouseX, mouseY);

        y = toggle(graphics, "relay.config.hud_grow_up", "relay.config.hud_grow_up.desc",
                config.hudGrowUp, x, y, mouseX, mouseY, () -> {
                    config.hudGrowUp = !config.hudGrowUp;
                    config.save();
                });
        y = toggle(graphics, "relay.config.hud_show_coords", null, config.hudShowCoords,
                x, y, mouseX, mouseY, () -> {
                    config.hudShowCoords = !config.hudShowCoords;
                    config.save();
                });
        y = toggle(graphics, "relay.config.hud_shorten_coords",
                "relay.config.hud_shorten_coords.desc", config.hudShortenCoords,
                x, y, mouseX, mouseY, () -> {
                    config.hudShortenCoords = !config.hudShortenCoords;
                    config.save();
                });
        y = toggle(graphics, "relay.config.hud_show_health", "relay.config.hud_show_health.desc",
                config.hudShowHealth, x, y, mouseX, mouseY, () -> {
                    config.hudShowHealth = !config.hudShowHealth;
                    config.save();
                });

        List<Dropdown.Entry<TeamConfig.ArmorDisplay>> armors = new ArrayList<>();
        for (TeamConfig.ArmorDisplay value : TeamConfig.ArmorDisplay.values()) {
            armors.add(new Dropdown.Entry<>(value, armorDisplayLabel(value).getString()));
        }
        y = dropdownRow(graphics, "relay.config.hud_show_armor", null, armorDisplay, armors,
                config.hudArmorDisplay, x, y, mouseX, mouseY);

        List<Dropdown.Entry<TeamConfig.DurabilityDisplay>> durabilities = new ArrayList<>();
        for (TeamConfig.DurabilityDisplay value : TeamConfig.DurabilityDisplay.values()) {
            durabilities.add(new Dropdown.Entry<>(value, durabilityDisplayLabel(value).getString()));
        }
        // Durability only means anything while armor is being shown at all.
        durabilityDisplay.setActive(config.hudArmorDisplay != TeamConfig.ArmorDisplay.OFF);
        y = dropdownRow(graphics, "relay.config.hud_durability", "relay.config.hud_durability.desc",
                durabilityDisplay, durabilities, config.hudDurabilityDisplay, x, y, mouseX, mouseY);

        y += 10;
        y = header(graphics, "relay.config.section.hud_colors",
                "relay.config.section.hud_colors.desc", x, y);
        y = colorRow(graphics, "relay.config.hud_primary_color", primaryColorInput,
                config.hudPrimaryArgb(), x, y, mouseX, mouseY);
        y = colorRow(graphics, "relay.config.hud_secondary_color", secondaryColorInput,
                config.hudSecondaryArgb(), x, y, mouseX, mouseY);

        return y + CARD_PADDING - top;
    }

    /**
     * The in-world teammate markers. A section of the General page rather than a tab of its own:
     * five rows is not enough to justify one, and they sit naturally under the sharing settings that
     * decide whether there is anything to mark.
     */
    private int drawMarkerSection(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        y = header(graphics, "relay.config.section.player_marker",
                "relay.config.section.player_marker.desc", x, y);
        y = toggle(graphics, "relay.config.player_markers_enabled",
                "relay.config.player_markers_enabled.desc", config.playerMarkersEnabled,
                x, y, mouseX, mouseY, () -> {
                    config.playerMarkersEnabled = !config.playerMarkersEnabled;
                    config.save();
                });

        // Xaero drawing the markers makes the mod's own sizing/opacity settings inert; say so on the
        // row rather than leaving controls that quietly do nothing.
        boolean xaeroDraws = config.useXaeroInWorldIcons && XaeroCompat.isMinimapInstalled();
        String note = xaeroDraws ? "relay.config.player_marker_size.xaero" : null;

        y = markerSlider(graphics, "relay.config.player_marker_size",
                note != null ? note : "relay.config.player_marker_size.desc",
                config.playerMarkerSize, TeamConfig.MARKER_SIZE_MIN, TeamConfig.MARKER_SIZE_MAX,
                "px", !xaeroDraws && config.playerMarkersEnabled, x, y, mouseX, mouseY, v -> {
                    config.playerMarkerSize = v;
                    config.save();
                });
        y = markerSlider(graphics, "relay.config.player_marker_opacity",
                note != null ? note : "relay.config.player_marker_opacity.desc",
                config.playerMarkerIdleOpacity, TeamConfig.MARKER_OPACITY_MIN,
                TeamConfig.MARKER_OPACITY_MAX, "%", !xaeroDraws && config.playerMarkersEnabled,
                x, y, mouseX, mouseY, v -> {
                    config.playerMarkerIdleOpacity = v;
                    config.save();
                });
        y = markerSlider(graphics, "relay.config.player_marker_hide",
                note != null ? note : "relay.config.player_marker_hide.desc",
                config.playerMarkerHideDistance, TeamConfig.MARKER_HIDE_MIN,
                TeamConfig.MARKER_HIDE_MAX, "m", !xaeroDraws && config.playerMarkersEnabled,
                x, y, mouseX, mouseY, v -> {
                    config.playerMarkerHideDistance = v;
                    config.save();
                });
        y = toggle(graphics, "relay.config.player_marker_show_distance",
                "relay.config.player_marker_show_distance.desc", config.playerMarkerShowDistance,
                x, y, mouseX, mouseY, () -> {
                    config.playerMarkerShowDistance = !config.playerMarkerShowDistance;
                    config.save();
                });

        return y;
    }

    /** Settings for other mods this one talks to. */
    private int drawIntegrations(GuiGraphicsExtractor graphics, int left, int top, int right,
                                 int mouseX, int mouseY) {
        int x = left + CARD_PADDING;
        int y = top + CARD_PADDING;

        boolean minimap = XaeroCompat.isMinimapInstalled();
        boolean worldMap = XaeroCompat.isWorldMapInstalled();

        y = header(graphics, "relay.config.section.xaero", "relay.config.section.xaero.desc", x, y);

        // Each control is disabled when the mod that acts on it is absent, so a setting that could
        // not possibly do anything reads as unavailable rather than broken. The two have different
        // requirements: the map-icon flag is honoured by both Xaero trackers, while the in-world
        // icon is enforced by a mixin into the minimap's renderer specifically.
        y = toggleGated(graphics, "relay.config.xaero_map_icons", minimap || worldMap
                        ? null : "relay.config.integration.missing",
                config.xaeroMapIcons, minimap || worldMap, x, y, mouseX, mouseY, () -> {
                    config.xaeroMapIcons = !config.xaeroMapIcons;
                    config.save();
                });
        y = toggleGated(graphics, "relay.config.xaero_markers",
                minimap ? "relay.config.xaero_markers.desc" : "relay.config.integration.missing",
                config.useXaeroInWorldIcons, minimap, x, y, mouseX, mouseY, () -> {
                    config.useXaeroInWorldIcons = !config.useXaeroInWorldIcons;
                    config.save();
                });

        return y + CARD_PADDING - top;
    }

    /** The active list's players, with per-player mute and sharing toggles. */
    private int drawTrust(GuiGraphicsExtractor graphics, int left, int top, int right,
                          int mouseX, int mouseY) {
        int x = left + CARD_PADDING;
        int y = top + CARD_PADDING;

        // With no server there is no server list to edit: pin the mode to GLOBAL and grey the
        // control out rather than letting it select an unusable state.
        boolean serverAvailable = TeamConfig.currentServerKey() != null;
        if (!serverAvailable && config.activeMode == TeamConfig.Mode.SERVER) {
            config.activeMode = TeamConfig.Mode.GLOBAL;
            config.save();
        }

        y = header(graphics, "relay.config.section.trust", "relay.config.section.trust.desc", x, y);

        List<Dropdown.Entry<TeamConfig.Mode>> modes = new ArrayList<>();
        modes.add(new Dropdown.Entry<>(TeamConfig.Mode.GLOBAL, modeLabel(TeamConfig.Mode.GLOBAL).getString()));
        modes.add(new Dropdown.Entry<>(TeamConfig.Mode.SERVER, modeLabel(TeamConfig.Mode.SERVER).getString()));
        activeMode.setActive(serverAvailable);
        activeMode.setTooltip(serverAvailable ? null : Component.translatable("relay.config.no_server"));
        y = dropdownRow(graphics, "relay.config.active_list", null, activeMode, modes,
                config.activeMode, x, y, mouseX, mouseY);

        // --- Add a player: name field then the Add button ---
        graphics.text(this.font, Component.translatable("relay.config.add_player"),
                x, y + 6, LABEL_COLOR, false);
        fieldFrame(graphics, nameInput, x + CONTROL_X, y, 130);
        placeField(nameInput, x + CONTROL_X + 5, y + 6, 120);
        zones.add(new Zone(x + CONTROL_X, y, x + CONTROL_X + 130, y + 20,
                () -> setFocused(nameInput), null, CursorTypes.IBEAM));
        boolean addOnScreen = y + 20 > viewTop && y < viewBottom;
        addButton.visible = addOnScreen;
        addButton.setX(x + CONTROL_X + 135);
        addButton.setY(addOnScreen ? y : -100);
        if (addStatus != null) {
            graphics.text(this.font, addStatus, x + CONTROL_X + 205, y + 6, addStatusColor, false);
        }
        y += ROW_HEIGHT + 8;

        // --- Table ---
        List<TrustEntry> entries = currentList();
        if (entries.isEmpty()) {
            graphics.text(this.font, Component.translatable("relay.config.trust_empty"),
                    x, y + 4, MUTED_COLOR, false);
            return y + this.font.lineHeight + CARD_PADDING - top;
        }

        int muteX = right - CARD_PADDING - 50 - 10 - TOGGLE_W - 10 - TOGGLE_W;
        int shareX = muteX + TOGGLE_W + 10;
        int removeX = shareX + TOGGLE_W + 10;

        graphics.text(this.font, Component.translatable("relay.config.column.player"),
                x, y, MUTED_COLOR, false);
        centered(graphics, Component.translatable("relay.config.column.alerts"), muteX, TOGGLE_W, y);
        centered(graphics, Component.translatable("relay.config.column.visibility"), shareX, TOGGLE_W, y);
        y += this.font.lineHeight + 6;

        for (TrustEntry entry : entries) {
            int rowTop = y - 3;
            int rowBottom = y + ROW_HEIGHT - 6;
            if (mouseY >= rowTop && mouseY <= rowBottom && mouseX >= x
                    && mouseX <= right - CARD_PADDING) {
                graphics.fill(x - 4, rowTop, right - CARD_PADDING, rowBottom, 0x8022303F);
            }

            drawFaceAndName(graphics, x, y - 3, entry);

            boolean muted = entry.mutePings;
            drawToggle(graphics, muteX, y - 3, TOGGLE_W, muted ? "MUTED" : "UNMUTED", !muted);
            zones.add(new Zone(muteX, y - 3, muteX + TOGGLE_W, y + 13, () -> {
                entry.mutePings = !entry.mutePings;
                config.save();
                click();
            }));

            boolean shared = !entry.hidden;
            drawToggle(graphics, shareX, y - 3, TOGGLE_W, shared ? "ON" : "OFF", shared);
            zones.add(new Zone(shareX, y - 3, shareX + TOGGLE_W, y + 13, () -> {
                entry.hidden = !entry.hidden;
                config.save();
                TeamLocatorClient.syncToServer();
                click();
            }));

            drawToggle(graphics, removeX, y - 3, 50,
                    Component.translatable("relay.config.remove").getString(), false);
            zones.add(new Zone(removeX, y - 3, removeX + 50, y + 13, () -> {
                entries.remove(entry);
                config.save();
                TeamLocatorClient.syncToServer();
                click();
            }));

            y += ROW_HEIGHT;
        }

        return y + CARD_PADDING - top;
    }

    // ---------------------------------------------------------------- row kit

    /** A section heading, with an optional line of explanation under it. */
    private int header(GuiGraphicsExtractor graphics, String titleKey, String descKey, int x, int y) {
        graphics.text(this.font, Component.translatable(titleKey), x, y, TITLE_COLOR, false);
        y += this.font.lineHeight + 4;
        if (descKey != null) {
            graphics.text(this.font, Component.translatable(descKey), x, y, MUTED_COLOR, false);
            y += this.font.lineHeight + 6;
        } else {
            y += 2;
        }
        return y;
    }

    private int toggle(GuiGraphicsExtractor graphics, String labelKey, String descKey, boolean on,
                       int x, int y, int mouseX, int mouseY, Runnable onClick) {
        return toggleGated(graphics, labelKey, descKey, on, true, x, y, mouseX, mouseY, onClick);
    }

    /** A labelled ON/OFF row. When {@code enabled} is false it paints greyed and ignores clicks. */
    private int toggleGated(GuiGraphicsExtractor graphics, String labelKey, String descKey,
                            boolean on, boolean enabled, int x, int y, int mouseX, int mouseY,
                            Runnable onClick) {
        graphics.text(this.font, Component.translatable(labelKey), x, y + 6,
                enabled ? LABEL_COLOR : MUTED_COLOR, false);
        int toggleX = x + CONTROL_X;
        drawToggle(graphics, toggleX, y, TOGGLE_W, on ? "ON" : "OFF", on && enabled);
        if (enabled) {
            zones.add(new Zone(toggleX, y, toggleX + TOGGLE_W, y + ROW_HEIGHT - 6, () -> {
                onClick.run();
                click();
            }));
        }
        noteTooltip(descKey, x, y, mouseX, mouseY);
        return y + ROW_HEIGHT;
    }

    private <T> int dropdownRow(GuiGraphicsExtractor graphics, String labelKey, String descKey,
                                Dropdown<T> dropdown, List<Dropdown.Entry<T>> entries, T current,
                                int x, int y, int mouseX, int mouseY) {
        graphics.text(this.font, Component.translatable(labelKey), x, y + 6,
                dropdown.isActive() ? LABEL_COLOR : MUTED_COLOR, false);
        dropdown.setEntries(entries);
        dropdown.setBounds(x + CONTROL_X, y, CONTROL_W);
        dropdown.draw(graphics, this.font, current, mouseX, mouseY);
        if (dropdown.getTooltip() != null && dropdown.isHovered(this.font, mouseX, mouseY)) {
            hoverTooltip = dropdown.getTooltip();
        } else {
            noteTooltip(descKey, x, y, mouseX, mouseY);
        }
        return y + ROW_HEIGHT;
    }

    /**
     * A stepped seconds slider drawn as a bar with the value on it. Clicking or dragging anywhere
     * along the bar picks the nearest step.
     */
    private int sliderRow(GuiGraphicsExtractor graphics, String labelKey, String descKey,
                          int value, int[] steps, int x, int y,
                          int mouseX, int mouseY, java.util.function.IntConsumer onChange) {
        graphics.text(this.font, Component.translatable(labelKey), x, y + 6, LABEL_COLOR, false);
        int barX = x + CONTROL_X;
        int index = nearestStep(steps, value);
        double fraction = steps.length <= 1 ? 0 : (double) index / (steps.length - 1);
        drawBar(graphics, barX, y, CONTROL_W, fraction, formatSeconds(steps[index]), mouseX, mouseY);
        zones.add(new Zone(barX, y, barX + CONTROL_W, y + ROW_HEIGHT - 6, () -> {
        }, px -> {
            int picked = Math.clamp(
                    (int) Math.round((double) (px - barX) / CONTROL_W * (steps.length - 1)),
                    0, steps.length - 1);
            onChange.accept(steps[picked]);
        }));
        noteTooltip(descKey, x, y, mouseX, mouseY);
        return y + ROW_HEIGHT;
    }

    /** A plain min..max integer slider, used by the marker settings. */
    private int markerSlider(GuiGraphicsExtractor graphics, String labelKey, String descKey,
                             int value, int min, int max, String unit, boolean enabled,
                             int x, int y, int mouseX, int mouseY,
                             java.util.function.IntConsumer onChange) {
        graphics.text(this.font, Component.translatable(labelKey), x, y + 6,
                enabled ? LABEL_COLOR : MUTED_COLOR, false);
        int barX = x + CONTROL_X;
        double fraction = (double) (value - min) / (max - min);
        drawBar(graphics, barX, y, CONTROL_W, enabled ? fraction : 0,
                label(value, unit), enabled ? mouseX : -1, mouseY);
        if (enabled) {
            zones.add(new Zone(barX, y, barX + CONTROL_W, y + ROW_HEIGHT - 6, () -> {
            }, px -> {
                double f = Math.clamp((double) (px - barX) / CONTROL_W, 0.0, 1.0);
                onChange.accept((int) Math.round(min + f * (max - min)));
            }));
        }
        noteTooltip(descKey, x, y, mouseX, mouseY);
        return y + ROW_HEIGHT;
    }

    /** HUD X and Y side by side, since they are one setting in two axes. */
    private int doubleSlider(GuiGraphicsExtractor graphics, String labelKey, String descKey,
                             double xValue, double yValue, int x, int y, int mouseX, int mouseY,
                             java.util.function.DoubleConsumer onX,
                             java.util.function.DoubleConsumer onY) {
        graphics.text(this.font, Component.translatable(labelKey), x, y + 6, LABEL_COLOR, false);
        int half = (CONTROL_W - 6) / 2;
        int leftX = x + CONTROL_X;
        int rightX = leftX + half + 6;

        drawBar(graphics, leftX, y, half, xValue, "X " + Math.round(xValue * 100) + "%", mouseX, mouseY);
        zones.add(new Zone(leftX, y, leftX + half, y + ROW_HEIGHT - 6, () -> {
        }, px -> onX.accept(Math.clamp((double) (px - leftX) / half, 0.0, 1.0))));

        drawBar(graphics, rightX, y, half, yValue, "Y " + Math.round(yValue * 100) + "%", mouseX, mouseY);
        zones.add(new Zone(rightX, y, rightX + half, y + ROW_HEIGHT - 6, () -> {
        }, px -> onY.accept(Math.clamp((double) (px - rightX) / half, 0.0, 1.0))));

        noteTooltip(descKey, x, y, mouseX, mouseY);
        return y + ROW_HEIGHT;
    }

    /** HUD scale, which runs 0.5..2.0 rather than 0..1. */
    private int scaleRow(GuiGraphicsExtractor graphics, String labelKey, String descKey,
                         int x, int y, int mouseX, int mouseY) {
        graphics.text(this.font, Component.translatable(labelKey), x, y + 6, LABEL_COLOR, false);
        int barX = x + CONTROL_X;
        double fraction = (config.hudScale - 0.5) / 1.5;
        drawBar(graphics, barX, y, CONTROL_W, fraction,
                String.format(java.util.Locale.ROOT, "%.2fx", config.hudScale), mouseX, mouseY);
        zones.add(new Zone(barX, y, barX + CONTROL_W, y + ROW_HEIGHT - 6, () -> {
        }, px -> {
            double f = Math.clamp((double) (px - barX) / CONTROL_W, 0.0, 1.0);
            // Quarter steps, so the value lands on round numbers instead of 1.37x.
            config.hudScale = Math.round((0.5 + f * 1.5) * 20.0) / 20.0;
            config.save();
        }));
        noteTooltip(descKey, x, y, mouseX, mouseY);
        return y + ROW_HEIGHT;
    }

    /** A labelled text field. */
    private int fieldRow(GuiGraphicsExtractor graphics, String labelKey, String descKey,
                         EditBox box, int fieldWidth, int x, int y, int mouseX, int mouseY) {
        graphics.text(this.font, Component.translatable(labelKey), x, y + 6, LABEL_COLOR, false);
        fieldFrame(graphics, box, x + CONTROL_X, y, fieldWidth);
        placeField(box, x + CONTROL_X + 5, y + 6, fieldWidth - 10);
        zones.add(new Zone(x + CONTROL_X, y, x + CONTROL_X + fieldWidth, y + 20,
                () -> setFocused(box), null, CursorTypes.IBEAM));
        noteTooltip(descKey, x, y, mouseX, mouseY);
        return y + ROW_HEIGHT;
    }

    /** A hex colour field with a live swatch beside it. */
    private int colorRow(GuiGraphicsExtractor graphics, String labelKey, EditBox box, int argb,
                         int x, int y, int mouseX, int mouseY) {
        // Short label, with what the colour actually covers in the tooltip. Spelling it out on the
        // row ("Primary color: player names & punctuation") overran the control column and ran the
        // text into the field beside it.
        graphics.text(this.font, Component.translatable(labelKey), x, y + 6, LABEL_COLOR, false);
        fieldFrame(graphics, box, x + CONTROL_X, y, 70);
        placeField(box, x + CONTROL_X + 5, y + 6, 60);
        zones.add(new Zone(x + CONTROL_X, y, x + CONTROL_X + 70, y + 20,
                () -> setFocused(box), null, CursorTypes.IBEAM));
        int swatchX = x + CONTROL_X + 78;
        graphics.fill(swatchX, y, swatchX + 20, y + 20, 0xFFFFFFFF);
        graphics.fill(swatchX + 1, y + 1, swatchX + 19, y + 19, argb);
        noteTooltip(labelKey + ".desc", x, y, mouseX, mouseY);
        return y + ROW_HEIGHT;
    }

    /**
     * The relay address: a framed box with a fixed {@code wss://} prefix and the editable host after
     * it, so the scheme reads as part of the field without being editable.
     */
    private int relayAddressRow(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        graphics.text(this.font, Component.translatable("relay.config.relay_url"),
                x, y + 6, LABEL_COLOR, false);
        int boxX = x + CONTROL_X;
        fieldFrame(graphics, relayUrlInput, boxX, y, RELAY_BOX_W);
        graphics.text(this.font, SCHEME_LABEL, boxX + 5, y + 6, MUTED_COLOR, false);
        placeField(relayUrlInput, boxX + 5 + this.font.width(SCHEME_LABEL), y + 6,
                RELAY_BOX_W - 10 - this.font.width(SCHEME_LABEL));
        // Clicking the prefix (inside the frame, left of the borderless field) still focuses it —
        // the whole frame reads as one text box.
        zones.add(new Zone(boxX, y, boxX + RELAY_BOX_W, y + 20,
                () -> setFocused(relayUrlInput), null, CursorTypes.IBEAM));
        noteTooltip("relay.config.relay_url.desc", x, y, mouseX, mouseY);
        return y + ROW_HEIGHT + 4;
    }

    /** Record the row's description as this frame's tooltip when the cursor is over its label. */
    private void noteTooltip(String descKey, int x, int y, int mouseX, int mouseY) {
        if (descKey == null) {
            return;
        }
        if (mouseX >= x && mouseX <= x + CONTROL_X + CONTROL_W
                && mouseY >= y && mouseY <= y + ROW_HEIGHT - 6) {
            hoverTooltip = Component.translatable(descKey);
        }
    }

    private static String label(int value, String unit) {
        return value + unit;
    }

    /**
     * How a seconds value reads on its slider: whole minutes as "5m", mixed as "1m 30s", and the two
     * sentinels by name. {@link SecondsSlider#INFINITE} is -1, so formatting it as a plain number
     * would put "-1s" on the bar.
     */
    private static String formatSeconds(int seconds) {
        if (seconds == SecondsSlider.INFINITE) {
            return "Infinite";
        }
        if (seconds == 0) {
            return "None";
        }
        if (seconds < 60) {
            return "%ds".formatted(seconds);
        }
        int minutes = seconds / 60;
        int rest = seconds % 60;
        return rest == 0 ? "%dm".formatted(minutes) : "%dm %ds".formatted(minutes, rest);
    }

    /**
     * Nearest index in a stepped slider's table, so an out-of-table saved value still shows.
     *
     * <p>{@link SecondsSlider#INFINITE} is matched exactly rather than numerically: comparing -1 by
     * distance lands on the smallest step, which would silently turn "Infinite" into "5s" the next
     * time the screen opened.
     */
    private static int nearestStep(int[] steps, int value) {
        if (value == SecondsSlider.INFINITE) {
            for (int i = 0; i < steps.length; i++) {
                if (steps[i] == SecondsSlider.INFINITE) {
                    return i;
                }
            }
        }
        int best = 0;
        for (int i = 1; i < steps.length; i++) {
            if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) {
                best = i;
            }
        }
        return best;
    }

    /** A slider bar: track, filled portion, and the value centred on it. */
    private void drawBar(GuiGraphicsExtractor graphics, int x, int y, int w, double fraction,
                         String text, int mouseX, int mouseY) {
        int h = 20;
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        frameBox(graphics, x, y, x + w, y + h);
        int fill = (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * (w - 2));
        if (fill > 0) {
            graphics.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, hovered ? 0x8025455A : 0x60203847);
        }
        graphics.text(this.font, text, x + (w - this.font.width(text)) / 2,
                y + (h - this.font.lineHeight) / 2, hovered ? 0xFFFFFFFF : 0xFFE4EAF2, false);
    }

    private void drawToggle(GuiGraphicsExtractor graphics, int x, int y, int boxWidth,
                            String text, boolean on) {
        int boxHeight = this.font.lineHeight + 8;
        int fill = on ? 0x5023351F : 0x50241A1D;
        int border = on ? 0xA05F9A56 : 0xA0955A5A;

        graphics.fill(x, y, x + boxWidth, y + boxHeight, fill);
        graphics.fill(x, y, x + boxWidth, y + 1, border);
        graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, border);
        graphics.fill(x, y, x + 1, y + boxHeight, border);
        graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, border);

        graphics.text(this.font, text, x + (boxWidth - this.font.width(text)) / 2, y + 4,
                on ? 0xFFA8E39B : 0xFFE0A0A0, false);
    }

    private static void frameBox(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        graphics.fill(left, top, right, bottom, 0x50161B22);
        graphics.fill(left, top, right, top + 1, CARD_BORDER);
        graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
        graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
        graphics.fill(right - 1, top, right, bottom, CARD_BORDER);
    }

    private void centered(GuiGraphicsExtractor graphics, Component label, int colX, int colW, int y) {
        graphics.text(this.font, label, colX + colW / 2 - this.font.width(label) / 2, y,
                MUTED_COLOR, false);
    }

    private void drawFaceAndName(GuiGraphicsExtractor graphics, int x, int y, TrustEntry entry) {
        UUID id = safeUuid(entry);
        PlayerSkin skin;
        String name = entry.name != null ? entry.name
                : (id != null ? id.toString().substring(0, 8) : "?");
        if (id != null && Minecraft.getInstance().getConnection() != null) {
            PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(id);
            skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(id);
            if (info != null) {
                name = info.getProfile().name();
            }
        } else {
            skin = DefaultPlayerSkin.getDefaultSkin();
        }
        PlayerFaceExtractor.extractRenderState(graphics, skin, x, y + 1, 14);
        graphics.text(this.font, name, x + 20, y + 5, 0xFFFFFFFF, false);
    }

    // ------------------------------------------------------------------ labels

    private Component modeLabel(TeamConfig.Mode mode) {
        return Component.translatable(mode == TeamConfig.Mode.GLOBAL
                ? "relay.config.active_list.global" : "relay.config.active_list.server");
    }

    private Component hudAlignLabel(TeamConfig.HudAlign align) {
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
     * restyles {@code options.on}/{@code options.off} restyles these too. Only LOWEST, which vanilla
     * has no word for, is ours.
     */
    private Component armorDisplayLabel(TeamConfig.ArmorDisplay display) {
        return switch (display) {
            case OFF -> CommonComponents.OPTION_OFF;
            case LOWEST -> Component.translatable("relay.config.hud_show_armor.lowest");
            default -> CommonComponents.OPTION_ON;
        };
    }

    private Component durabilityDisplayLabel(TeamConfig.DurabilityDisplay display) {
        String key = switch (display) {
            case BAR -> "bar";
            case NUMBER_ONLY -> "number_only";
            case BOTH -> "both";
        };
        return Component.translatable("relay.config.hud_durability." + key);
    }

    private Component alertSoundLabel(TeamConfig.AlertSound sound) {
        return Component.translatable(sound == TeamConfig.AlertSound.ALARM
                ? "relay.config.alert_sound.alarm" : "relay.config.alert_sound.noteblocks");
    }

    /** The five colours for the signed-in account, or empty if there is not one yet. */
    private static List<String> pingPalette() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getUser() == null || mc.getUser().getProfileId() == null) {
            return List.of();
        }
        return PingPalette.forPlayer(mc.getUser().getProfileId());
    }

    // ------------------------------------------------------------------- trust

    private List<TrustEntry> currentList() {
        TeamConfig.TrustList list = config.activeList();
        // A mutable list even when disconnected in SERVER mode, so add/remove callbacks that slip
        // past their guards cannot throw UnsupportedOperationException.
        return list != null ? list.trusted : new ArrayList<>();
    }

    /**
     * Add the typed name to the active list. Players on this server resolve instantly via the tab
     * list; anyone else goes through Mojang's name-to-UUID API so teammates can be added offline.
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
            addStatus = null;
            return;
        }

        addStatus = Component.translatable("relay.config.lookup_pending", typed);
        addStatusColor = 0xFFA0A0A0;
        // The rows are painted immediate-mode from the live list, so landing an entry is enough --
        // no rebuild is needed for it to appear on the next frame.
        NameLookup.resolve(typed).whenComplete((resolved, err) -> Minecraft.getInstance()
                .execute(() -> {
                    if (resolved == null || err != null) {
                        addStatus = Component.translatable("relay.config.lookup_failed", typed);
                        addStatusColor = 0xFFFF5555;
                        return;
                    }
                    addStatus = null;
                    addEntry(resolved.id(), resolved.name());
                }));
    }

    private void addEntry(UUID id, String name) {
        List<TrustEntry> list = currentList();
        for (TrustEntry existing : list) {
            if (id.equals(safeUuid(existing))) {
                return;
            }
        }
        list.add(new TrustEntry(id, name));
        config.save();
        TeamLocatorClient.syncToServer();
    }

    private static UUID safeUuid(TrustEntry entry) {
        try {
            return entry.uuid();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------- input

    private void closeDropdowns() {
        for (Dropdown<?> dropdown : allDropdowns()) {
            dropdown.close();
        }
    }

    private List<Dropdown<?>> allDropdowns() {
        return List.of(hudAlign, armorDisplay, durabilityDisplay, alertSound, activeMode, pingColor);
    }

    /** The dropdowns the active tab actually shows, so a hidden one cannot swallow a click. */
    private List<Dropdown<?>> visibleDropdowns() {
        return switch (active) {
            case GENERAL -> List.of(alertSound, pingColor);
            case HUD -> List.of(hudAlign, armorDisplay, durabilityDisplay);
            case TRUST -> List.of(activeMode);
            default -> List.of();
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Dropdowns first: an open list sits above everything else, including the zones it covers.
        // An open list may legitimately hang below the viewport, so it is not clipped here.
        for (Dropdown<?> dropdown : visibleDropdowns()) {
            if (dropdown.isOpen() && dropdown.click(this.font, event.x(), event.y())) {
                return true;
            }
        }
        // A click on a bar must never reach a scrolled control clipped underneath it. Everything
        // below is inside the scrolling area, which the drawing already clips; without the same test
        // here a field scrolled out of sight stayed clickable through the tab bar and the Done row.
        boolean inView = event.y() >= viewTop && event.y() < viewBottom;
        if (inView) {
            for (Dropdown<?> dropdown : visibleDropdowns()) {
                if (dropdown.click(this.font, event.x(), event.y())) {
                    click();
                    return true;
                }
            }
            if (super.mouseClicked(event, doubled)) {
                return true;
            }
        } else if (doneButton != null && doneButton.mouseClicked(event, doubled)) {
            // Done sits on the bottom bar, outside the scrolling area, and is dispatched on its own.
            return true;
        }

        for (Zone zone : zones) {
            // Tab-strip zones live above the viewport, so they are exempt from the clip test.
            if (!zone.contains(event.x(), event.y())) {
                continue;
            }
            if (inView || zone.top() < viewTop) {
                zone.press((int) Math.round(event.x()));
                // Latch sliders so the drag survives the cursor leaving the bar.
                draggingSlider = zone.drag();
                return true;
            }
        }
        return false;
    }

    /**
     * Feed the latched slider until the button comes up, wherever the cursor goes. Each slider
     * clamps the x it is handed, so dragging past either end pins the value at that end instead of
     * stopping short of it.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSlider != null) {
            draggingSlider.accept((int) Math.round(event.x()));
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingSlider != null) {
            draggingSlider = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (Dropdown<?> dropdown : visibleDropdowns()) {
            if (dropdown.scroll(scrollY)) {
                return true;
            }
        }
        scroll = Math.clamp(scroll - (int) (scrollY * SCROLL_STEP), 0, maxScroll);
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

    /**
     * One click target. {@code drag} is set for sliders, which also respond to the cursor moving
     * with the button held; plain rows leave it null and only fire on press.
     *
     * <p>Rows are painted rather than being widgets, so they get none of {@code AbstractWidget}'s
     * cursor handling for free -- the zone carries the cursor to request while hovered, which is
     * what gives toggles and tabs the pointing hand and sliders the horizontal resize arrows.
     */
    private record Zone(int left, int top, int right, int bottom, Runnable action,
                        java.util.function.IntConsumer drag, CursorType cursor) {
        Zone(int left, int top, int right, int bottom, Runnable action) {
            this(left, top, right, bottom, action, null, CursorTypes.POINTING_HAND);
        }

        Zone(int left, int top, int right, int bottom, Runnable action,
             java.util.function.IntConsumer drag) {
            this(left, top, right, bottom, action, drag, CursorTypes.RESIZE_EW);
        }

        boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }

        void press(int mouseX) {
            if (drag != null) {
                drag.accept(mouseX);
            } else {
                action.run();
            }
        }
    }
}
