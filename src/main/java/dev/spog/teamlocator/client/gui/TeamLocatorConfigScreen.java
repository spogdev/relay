package dev.spog.teamlocator.client.gui;

import dev.spog.spore.ui.screen.SporeConfigScreen;
import dev.spog.spore.ui.screen.SporeTab;
import dev.spog.spore.ui.theme.SporeDraw;
import dev.spog.spore.ui.theme.SporeTheme;
import dev.spog.spore.ui.widget.SporeDropdown;
import dev.spog.spore.ui.widget.SporeSlider;
import dev.spog.spore.ui.widget.SporeTextBox;
import dev.spog.teamlocator.client.PingPalette;
import dev.spog.teamlocator.client.RelayLang;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.compat.xaero.XaeroCompat;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.config.TrustEntry;
import dev.spog.teamlocator.client.net.NameLookup;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Relay's settings.
 *
 * <p>The frame, tabs, scrolling, tooltips, cursors and Done button come from
 * {@link SporeConfigScreen}; what is left here is the settings themselves.
 * This file was about fifteen hundred lines before, most of it the panel
 * chrome and a set of row helpers that SpogTiers had its own near-identical
 * copy of -- the two had drifted apart in spacing and in three shades of grey,
 * which is what the shared library exists to stop.
 *
 * <p>Three controls are still drawn by hand, because they are this mod's own
 * rather than anything a settings library should know about: the HUD's X/Y
 * position as two half-width sliders on one row, the HUD scale as a
 * multiplier, and the two hex colour fields with a live swatch. They use the
 * library's own primitives, so they scroll, clip, highlight and drag like
 * every other row.
 */
@Environment(EnvType.CLIENT)
public class TeamLocatorConfigScreen extends SporeConfigScreen<TeamLocatorConfigScreen.Tab> {
    /** Which page is showing. */
    public enum Tab implements SporeTab {
        GENERAL("relay.config.tab.general"),
        HUD("relay.config.tab.hud"),
        INTEGRATIONS("relay.config.tab.integrations"),
        TRUST("relay.config.tab.trust");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        @Override
        public String title() {
            return RelayLang.text(key).getString();
        }
    }

    /** The width of the relay address box, which is wider than a normal field. */
    private static final int RELAY_BOX_W = 200;

    /** The fixed, uneditable scheme shown inside the relay address box. */
    private static final String SCHEME_LABEL = "wss://";

    private final TeamConfig config;

    /**
     * Where the remove X sits: its centre column, in from the body's right edge, which centres it
     * between the Sharing switch and the card border.
     */
    private static final int REMOVE_INSET = 20;
    /** Rows above and below the X's centre row; the glyph is 2 * ARM + 2 wide, 2 * ARM + 1 tall. */
    private static final int REMOVE_ARM = 5;
    /** Half the click area, a little larger than the glyph so it is not fiddly to hit. */
    private static final int REMOVE_HALF = 8;
    /** Faint until hovered, so a column of them does not read as a column of warnings. */
    private static final int REMOVE_COLOR = 0x60FF5555;
    private static final int REMOVE_COLOR_HOVER = 0xFFFF5555;

    private final SporeDropdown<TeamConfig.HudAlign> hudAlign;
    private final SporeDropdown<TeamConfig.ArmorDisplay> armorDisplay;
    private final SporeDropdown<TeamConfig.DurabilityDisplay> durabilityDisplay;
    private final SporeDropdown<TeamConfig.AlertSound> alertSound;
    private final SporeDropdown<TeamConfig.Mode> activeMode;
    private final SporeDropdown<Integer> pingColor;

    private final SporeTextBox nameInput;
    private final SporeTextBox relayUrlInput;
    private final SporeTextBox chatPrefixInput;
    private final SporeTextBox primaryColorInput;
    private final SporeTextBox secondaryColorInput;

    /** Set by a row's remove X and consumed on the next draw; see {@link #drawRemoveX}. */
    private TrustEntry pendingRemoval;

    /** The result of the last name lookup, shown beside the Add button. */
    private Component addStatus;
    private int addStatusColor;

    public TeamLocatorConfigScreen(Screen parent, TeamConfig config) {
        super(parent, Component.literal("Relay"), Tab.values(), Tab.GENERAL);
        this.config = config;

        var font = Minecraft.getInstance().font;

        hudAlign = new SporeDropdown<>(value -> {
            config.hudAlign = value;
            config.save();
        });
        armorDisplay = new SporeDropdown<>(value -> {
            config.hudArmorDisplay = value;
            config.save();
        });
        durabilityDisplay = new SporeDropdown<>(value -> {
            config.hudDurabilityDisplay = value;
            config.save();
        });
        alertSound = new SporeDropdown<>(value -> {
            config.alertSound = value;
            config.save();
        });
        activeMode = new SporeDropdown<>(value -> {
            config.activeMode = value;
            config.save();
        });
        pingColor = new SporeDropdown<>(value -> {
            config.pingColorIndex = value;
            config.save();
        });

        chatPrefixInput = new SporeTextBox(font, 60, RelayLang.text("relay.config.chat_prefix"),
                value -> {
                    config.chatPrefix = value;
                    config.save();
                });
        chatPrefixInput.setMaxLength(1);
        chatPrefixInput.setValue(config.chatPrefix);

        relayUrlInput = new SporeTextBox(font, RELAY_BOX_W, RelayLang.text("relay.config.relay_url"),
                value -> {
                    config.relayUrl = TeamConfig.normalizeRelayAddress(value);
                    config.save();
                });
        relayUrlInput.setMaxLength(256);
        relayUrlInput.setValue(config.relayUrl);

        nameInput = new SporeTextBox(font, 130, RelayLang.text("relay.config.add_player"), value -> {
        });
        nameInput.setMaxLength(16);
        nameInput.setHint(RelayLang.text("relay.config.add_player"));

        primaryColorInput = colorBox(font, config.hudPrimaryColor,
                "relay.config.hud_primary_color", value -> {
                    config.hudPrimaryColor = value;
                    config.save();
                });
        secondaryColorInput = colorBox(font, config.hudSecondaryColor,
                "relay.config.hud_secondary_color", value -> {
                    config.hudSecondaryColor = value;
                    config.save();
                });
    }

    private SporeTextBox colorBox(net.minecraft.client.gui.Font font, String initial,
            String key, java.util.function.Consumer<String> onChange) {
        SporeTextBox box = new SporeTextBox(font, 70, RelayLang.text(key), onChange);
        box.setMaxLength(7);
        box.setValue(initial);
        return box;
    }

    @Override
    protected void init() {
        super.init();
        register(chatPrefixInput);
        register(relayUrlInput);
        register(nameInput);
        register(primaryColorInput);
        register(secondaryColorInput);
    }

    /**
     * Switches tabs from outside the screen, for the client gametest.
     *
     * <p>{@code setActiveTab} is protected, as it should be -- a tab is
     * changed by clicking one -- so this exposes it rather than weakening the
     * library's base class for a test's sake.
     *
     * @param tab the tab to show
     */
    public void openTabForTest(Tab tab) {
        setActiveTab(tab);
    }

    @Override
    protected void drawTab(Body body, Tab tab) {
        switch (tab) {
            case GENERAL -> drawGeneral(body);
            case HUD -> drawHud(body);
            case INTEGRATIONS -> drawIntegrations(body);
            case TRUST -> drawTrust(body);
        }
    }

    // ------------------------------------------------------------- General

    private void drawGeneral(Body body) {
        body.section(RelayLang.text("relay.config.section.sharing"));
        body.toggle(RelayLang.text("relay.config.share_location"), config.globalShareEnabled,
                RelayLang.text("relay.config.share_location.desc"), on -> {
                    config.globalShareEnabled = on;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });
        body.toggle(RelayLang.text("relay.config.share_armor"), config.shareArmor,
                RelayLang.text("relay.config.share_armor.desc"), on -> {
                    config.shareArmor = on;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });
        body.toggle(RelayLang.text("relay.config.share_health"), config.shareHealth,
                RelayLang.text("relay.config.share_health.desc"), on -> {
                    config.shareHealth = on;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });

        body.section(RelayLang.text("relay.config.section.pings"));
        body.toggle(RelayLang.text("relay.config.enable_alerts"), !config.hideAllAlerts, null,
                on -> {
                    config.hideAllAlerts = !on;
                    config.save();
                });
        body.toggle(RelayLang.text("relay.config.cross_server_pings"), config.crossServerPings,
                RelayLang.text("relay.config.cross_server_pings.desc"), on -> {
                    config.crossServerPings = on;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });

        alertSound.setEnumEntries(TeamConfig.AlertSound.values(),
                value -> alertSoundLabel(value).getString());
        body.dropdown(RelayLang.text("relay.config.alert_sound"), alertSound, config.alertSound,
                null);

        body.slider(RelayLang.text("relay.config.alert_cooldown"), config.pingCooldownSeconds,
                SporeSlider.DURATION_STEPS,
                RelayLang.text("relay.config.alert_cooldown.desc"), value -> {
                    config.pingCooldownSeconds = value;
                    config.save();
                    TeamLocatorClient.syncToServer();
                });

        body.section(RelayLang.text("relay.config.section.map_pings"));
        body.toggle(RelayLang.text("relay.config.show_pings"), config.showPings, null, on -> {
            config.showPings = on;
            config.save();
        });
        body.toggle(RelayLang.text("relay.config.pings_through_walls"), config.pingsThroughWalls,
                RelayLang.text("relay.config.pings_through_walls.desc"), on -> {
                    config.pingsThroughWalls = on;
                    config.save();
                });
        body.toggle(RelayLang.text("relay.config.ping_sound"), config.mapPingSound,
                RelayLang.text("relay.config.ping_sound.desc"), on -> {
                    config.mapPingSound = on;
                    config.save();
                });
        body.slider(RelayLang.text("relay.config.ping_duration"), config.pingDisplaySeconds,
                SporeSlider.LIFETIME_STEPS, null, value -> {
                    config.pingDisplaySeconds = value;
                    config.save();
                });
        body.slider(RelayLang.text("relay.config.ping_cooldown"), config.mapPingCooldownSeconds,
                SporeSlider.DURATION_STEPS_WITH_NONE,
                RelayLang.text("relay.config.ping_cooldown.desc"), value -> {
                    config.mapPingCooldownSeconds = value;
                    config.save();
                });

        // Ping colour: the five the account's UUID produces. The colours have no names, so each
        // row is its own hex value and the swatch beside the control shows the current pick --
        // the old "2 / 5" told you nothing about what you were choosing.
        List<String> palette = pingPalette();
        if (!palette.isEmpty()) {
            int selected = Math.floorMod(config.pingColorIndex, palette.size());
            // Label each row with its own hex value. A SporeDropdown.Entry carries only a label
            // and a texture icon -- there is no per-entry colour and the icon is blitted, not
            // tinted -- so an empty label left every row blank with nothing to tell them apart.
            List<SporeDropdown.Entry<Integer>> colors = new java.util.ArrayList<>();
            for (int i = 0; i < palette.size(); i++) {
                colors.add(new SporeDropdown.Entry<>(i, palette.get(i)));
            }
            pingColor.setEntries(colors);
            body.dropdown(RelayLang.text("relay.config.ping_color"), pingColor, selected,
                    RelayLang.text("relay.config.ping_color.desc"));

            // Back up over the row the dropdown just advanced past, so the swatch lands beside
            // the control rather than under the next row. The swatch goes in the gap between the
            // label column and the control: controlX() + CONTROL_W is the right edge every other
            // control shares, so the old controlX() + CONTROL_W + 8 put it outside the card, and
            // the control's own width is taken by its label at +6 and its arrow at +width-10.
            int swatchX = body.controlX() - 26;
            int swatchY = body.y() - (SporeTheme.ROW_HEIGHT + 2) - 4;
            body.graphics().fill(swatchX, swatchY, swatchX + 20, swatchY + SporeTheme.CONTROL_H,
                    0xFFFFFFFF);
            body.graphics().fill(swatchX + 1, swatchY + 1, swatchX + 19,
                    swatchY + SporeTheme.CONTROL_H - 1,
                    PingPalette.argb(palette.get(selected)));
        }

        body.section(RelayLang.text("relay.config.section.chat"));
        body.toggle(RelayLang.text("relay.config.chat_enabled"), config.chatEnabled,
                RelayLang.text("relay.config.chat_enabled.desc"), on -> {
                    config.chatEnabled = on;
                    config.save();
                });
        body.textBox(RelayLang.text("relay.config.chat_prefix"), chatPrefixInput, 60,
                RelayLang.text("relay.config.chat_prefix.desc"));

        drawMarkerSection(body);

        body.section(RelayLang.text("relay.config.section.advanced"));
        drawRelayAddress(body);
    }

    /**
     * The relay address: a framed box with a fixed {@code wss://} prefix and
     * the editable host after it, so the scheme reads as part of the field
     * without being editable.
     */
    private void drawRelayAddress(Body body) {
        var graphics = body.graphics();
        var font = body.font();
        int boxX = body.controlX();
        int top = body.y() - 4;

        body.graphics().text(font, RelayLang.text("relay.config.relay_url"),
                body.x(), body.y(), SporeTheme.LABEL_COLOR, false);

        // The box is placed after the prefix, and the frame is drawn around
        // the whole thing, so the two read as one control.
        relayUrlInput.place(graphics, boxX, top, RELAY_BOX_W, body.viewTop(), body.viewBottom(),
                relayUrlInput.isFocused());
        int prefixWidth = font.width(SCHEME_LABEL);
        graphics.text(font, Component.literal(SCHEME_LABEL), boxX + 5,
                top + (SporeTheme.CONTROL_H - font.lineHeight) / 2, SporeTheme.MUTED_COLOR, false);
        relayUrlInput.widget().setX(boxX + 5 + prefixWidth);
        relayUrlInput.widget().setWidth(RELAY_BOX_W - 10 - prefixWidth);

        // Clicking the prefix focuses the field too: the whole frame is one
        // text box as far as anyone looking at it is concerned.
        body.addZone(new dev.spog.spore.ui.screen.Zone(boxX, top, boxX + RELAY_BOX_W,
                top + SporeTheme.CONTROL_H, () -> setFocused(relayUrlInput.widget()),
                CursorTypes.IBEAM));
        body.tooltipFor(body.x(), body.y() - 2,
                body.x() + font.width(RelayLang.text("relay.config.relay_url")),
                body.y() + 10, RelayLang.text("relay.config.relay_url.desc"));
        body.advance(SporeTheme.ROW_HEIGHT + 6);
    }

    /**
     * The in-world teammate markers. A section of the General page rather than
     * a tab of its own: five rows is not enough to justify one, and they sit
     * naturally under the sharing settings that decide whether there is
     * anything to mark.
     */
    private void drawMarkerSection(Body body) {
        body.section(RelayLang.text("relay.config.section.player_marker"));
        body.toggle(RelayLang.text("relay.config.player_markers_enabled"),
                config.playerMarkersEnabled,
                RelayLang.text("relay.config.player_markers_enabled.desc"), on -> {
                    config.playerMarkersEnabled = on;
                    config.save();
                });

        // Xaero drawing the markers makes this mod's own sizing and opacity
        // settings inert; say so on the row rather than leaving controls that
        // quietly do nothing.
        boolean xaeroDraws = config.useXaeroInWorldIcons && XaeroCompat.isMinimapInstalled();
        boolean usable = !xaeroDraws && config.playerMarkersEnabled;
        Component note = xaeroDraws ? RelayLang.text("relay.config.player_marker_size.xaero") : null;

        body.slider(RelayLang.text("relay.config.player_marker_size"), config.playerMarkerSize,
                TeamConfig.MARKER_SIZE_MIN, TeamConfig.MARKER_SIZE_MAX, "px",
                note != null ? note : RelayLang.text("relay.config.player_marker_size.desc"),
                usable, value -> {
                    config.playerMarkerSize = value;
                    config.save();
                });
        body.slider(RelayLang.text("relay.config.player_marker_opacity"),
                config.playerMarkerIdleOpacity, TeamConfig.MARKER_OPACITY_MIN,
                TeamConfig.MARKER_OPACITY_MAX, "%",
                note != null ? note : RelayLang.text("relay.config.player_marker_opacity.desc"),
                usable, value -> {
                    config.playerMarkerIdleOpacity = value;
                    config.save();
                });
        body.slider(RelayLang.text("relay.config.player_marker_hide"),
                config.playerMarkerHideDistance, TeamConfig.MARKER_HIDE_MIN,
                TeamConfig.MARKER_HIDE_MAX, "m",
                note != null ? note : RelayLang.text("relay.config.player_marker_hide.desc"),
                usable, value -> {
                    config.playerMarkerHideDistance = value;
                    config.save();
                });
        body.toggle(RelayLang.text("relay.config.player_marker_show_distance"),
                config.playerMarkerShowDistance,
                RelayLang.text("relay.config.player_marker_show_distance.desc"), on -> {
                    config.playerMarkerShowDistance = on;
                    config.save();
                });
    }

    // ----------------------------------------------------------------- HUD

    private void drawHud(Body body) {
        body.section(RelayLang.text("relay.config.section.hud"));
        body.toggle(RelayLang.text("relay.config.hud_enabled"), config.hudEnabled,
                RelayLang.text("relay.config.hud_enabled.desc"), on -> {
                    config.hudEnabled = on;
                    config.save();
                });

        drawPositionRow(body);
        drawScaleRow(body);

        hudAlign.setEnumEntries(TeamConfig.HudAlign.values(),
                value -> hudAlignLabel(value).getString());
        body.dropdown(RelayLang.text("relay.config.hud_align"), hudAlign, config.hudAlign, null);

        body.toggle(RelayLang.text("relay.config.hud_grow_up"), config.hudGrowUp,
                RelayLang.text("relay.config.hud_grow_up.desc"), on -> {
                    config.hudGrowUp = on;
                    config.save();
                });
        body.toggle(RelayLang.text("relay.config.hud_show_coords"), config.hudShowCoords, null,
                on -> {
                    config.hudShowCoords = on;
                    config.save();
                });
        body.toggle(RelayLang.text("relay.config.hud_shorten_coords"), config.hudShortenCoords,
                RelayLang.text("relay.config.hud_shorten_coords.desc"), on -> {
                    config.hudShortenCoords = on;
                    config.save();
                });
        body.toggle(RelayLang.text("relay.config.hud_show_health"), config.hudShowHealth,
                RelayLang.text("relay.config.hud_show_health.desc"), on -> {
                    config.hudShowHealth = on;
                    config.save();
                });

        armorDisplay.setEnumEntries(TeamConfig.ArmorDisplay.values(),
                value -> armorDisplayLabel(value).getString());
        body.dropdown(RelayLang.text("relay.config.hud_show_armor"), armorDisplay,
                config.hudArmorDisplay, null);

        // Durability only means anything while armor is being shown at all.
        durabilityDisplay.setEnumEntries(TeamConfig.DurabilityDisplay.values(),
                value -> durabilityDisplayLabel(value).getString());
        // No reason text: the row's own description already says what this
        // depends on, and the original had none either.
        durabilityDisplay.setEnabled(config.hudArmorDisplay != TeamConfig.ArmorDisplay.OFF);
        body.dropdown(RelayLang.text("relay.config.hud_durability"), durabilityDisplay,
                config.hudDurabilityDisplay, RelayLang.text("relay.config.hud_durability.desc"));

        body.section(RelayLang.text("relay.config.section.hud_colors"));
        drawColorRow(body, "relay.config.hud_primary_color", primaryColorInput,
                config.hudPrimaryArgb());
        drawColorRow(body, "relay.config.hud_secondary_color", secondaryColorInput,
                config.hudSecondaryArgb());
    }

    /**
     * The HUD's position: two half-width sliders on one row.
     *
     * <p>Drawn by hand because it is two controls sharing a label, which no
     * row helper describes. The halves are measured from the control column
     * so they end where every other control does.
     */
    private void drawPositionRow(Body body) {
        body.graphics().text(body.font(), RelayLang.text("relay.config.hud_position"),
                body.x(), body.y(), SporeTheme.LABEL_COLOR, false);

        int half = (SporeTheme.CONTROL_W - 6) / 2;
        int leftX = body.controlX();
        int rightX = leftX + half + 6;
        int top = body.y() - 4;

        SporeSlider.bar(body.graphics(), body.font(), leftX, top, half, config.hudX,
                "X " + Math.round(config.hudX * 100) + "%", true, body.mouseX(), body.mouseY());
        body.addSliderZone(leftX, top, half, px -> {
            config.hudX = Math.clamp((double) (px - leftX) / half, 0.0, 1.0);
            config.save();
        });

        SporeSlider.bar(body.graphics(), body.font(), rightX, top, half, config.hudY,
                "Y " + Math.round(config.hudY * 100) + "%", true, body.mouseX(), body.mouseY());
        body.addSliderZone(rightX, top, half, px -> {
            config.hudY = Math.clamp((double) (px - rightX) / half, 0.0, 1.0);
            config.save();
        });

        body.tooltipFor(body.x(), body.y() - 2,
                body.x() + body.font().width(RelayLang.text("relay.config.hud_position")),
                body.y() + 10, RelayLang.text("relay.config.hud_position.desc"));
        body.advance(SporeTheme.ROW_HEIGHT + 2);
    }

    /** HUD scale, which runs 0.5..2.0 rather than over a list of steps. */
    private void drawScaleRow(Body body) {
        body.graphics().text(body.font(), RelayLang.text("relay.config.hud_scale"),
                body.x(), body.y(), SporeTheme.LABEL_COLOR, false);

        int barX = body.controlX();
        int top = body.y() - 4;
        SporeSlider.bar(body.graphics(), body.font(), barX, top, SporeTheme.CONTROL_W,
                (config.hudScale - 0.5) / 1.5, SporeSlider.formatScale(config.hudScale), true,
                body.mouseX(), body.mouseY());
        body.addSliderZone(barX, top, SporeTheme.CONTROL_W, px -> {
            double f = Math.clamp((double) (px - barX) / SporeTheme.CONTROL_W, 0.0, 1.0);
            // Quarter steps, so the value lands on round numbers instead of 1.37x.
            config.hudScale = Math.round((0.5 + f * 1.5) * 20.0) / 20.0;
            config.save();
        });
        body.advance(SporeTheme.ROW_HEIGHT + 2);
    }

    /** A hex colour field with a live swatch beside it. */
    private void drawColorRow(Body body, String labelKey, SporeTextBox box, int argb) {
        body.textBox(RelayLang.text(labelKey), box, 70, RelayLang.text(labelKey + ".desc"));

        // Back up over the row the text box just advanced past, so the swatch
        // lands beside it rather than under the next one.
        int swatchX = body.controlX() + 78;
        int swatchY = body.y() - SporeTheme.ROW_HEIGHT - 2 - 4;
        // Rounded like the text box it sits beside. The colour takes the same
        // radius as the white edge around it, not one less: the pixel of inset
        // is already what makes that edge visible, and stepping the radius too
        // would cancel it exactly at the corners.
        SporeDraw.roundedFill(body.graphics(), swatchX, swatchY, swatchX + 20,
                swatchY + SporeTheme.CONTROL_H, SporeTheme.CONTROL_RADIUS, 0xFFFFFFFF);
        SporeDraw.roundedFill(body.graphics(), swatchX + 1, swatchY + 1, swatchX + 19,
                swatchY + SporeTheme.CONTROL_H - 1, SporeTheme.CONTROL_RADIUS, argb);
    }

    // -------------------------------------------------------- Integrations

    private void drawIntegrations(Body body) {
        boolean minimap = XaeroCompat.isMinimapInstalled();
        boolean worldMap = XaeroCompat.isWorldMapInstalled();

        body.section(RelayLang.text("relay.config.section.xaero"));

        // Each control is disabled when the mod that acts on it is absent, so
        // a setting that could not possibly do anything reads as unavailable
        // rather than broken. The two have different requirements: the
        // map-icon flag is honoured by both Xaero trackers, while the
        // in-world icon is enforced by a mixin into the minimap's renderer.
        body.toggle(RelayLang.text("relay.config.xaero_map_icons"), config.xaeroMapIcons,
                minimap || worldMap,
                minimap || worldMap ? null : RelayLang.text("relay.config.integration.missing"),
                on -> {
                    config.xaeroMapIcons = on;
                    config.save();
                });
        body.toggle(RelayLang.text("relay.config.xaero_markers"), config.useXaeroInWorldIcons,
                minimap,
                minimap ? RelayLang.text("relay.config.xaero_markers.desc")
                        : RelayLang.text("relay.config.integration.missing"),
                on -> {
                    config.useXaeroInWorldIcons = on;
                    config.save();
                });
    }

    // --------------------------------------------------------------- Trust

    private void drawTrust(Body body) {
        // With no server there is no server list to edit: pin the mode to
        // GLOBAL and grey the control out rather than letting it select an
        // unusable state.
        boolean serverAvailable = TeamConfig.currentServerKey() != null;
        if (!serverAvailable && config.activeMode == TeamConfig.Mode.SERVER) {
            config.activeMode = TeamConfig.Mode.GLOBAL;
            config.save();
        }

        body.section(RelayLang.text("relay.config.section.trust"));

        activeMode.setEntries(List.of(
                new SporeDropdown.Entry<>(TeamConfig.Mode.GLOBAL,
                        modeLabel(TeamConfig.Mode.GLOBAL).getString()),
                new SporeDropdown.Entry<>(TeamConfig.Mode.SERVER,
                        modeLabel(TeamConfig.Mode.SERVER).getString())));
        activeMode.setEnabled(serverAvailable,
                serverAvailable ? null : RelayLang.text("relay.config.no_server"));
        body.dropdown(RelayLang.text("relay.config.active_list"), activeMode, config.activeMode,
                null);

        drawAddPlayerRow(body);

        List<TrustEntry> entries = currentList();
        if (entries.isEmpty()) {
            body.label(RelayLang.text("relay.config.trust_empty"));
            return;
        }

        body.columnHeading(RelayLang.text("relay.config.column.alerts"), 0, 2);
        body.columnHeading(RelayLang.text("relay.config.column.visibility"), 1, 2);
        body.endHeadings();

        // The X's zone fires from mouseClicked, a separate pass from this one, so it records its
        // target and the removal happens here on the next draw -- also keeping it out of the
        // iteration below, which a live remove would break.
        if (pendingRemoval != null) {
            entries.remove(pendingRemoval);
            pendingRemoval = null;
            config.save();
            TeamLocatorClient.syncToServer();
            if (entries.isEmpty()) {
                body.label(RelayLang.text("relay.config.trust_empty"));
                return;
            }
        }

        for (TrustEntry entry : entries) {
            if (body.rowHovered()) {
                body.highlightRow();
            }

            // The switches and the X sit a pixel higher than columnSwitch puts them, so they line
            // up with the player's name. Stepped up and back rather than changed in columnSwitch,
            // which is shared by every screen that draws a table.
            body.advance(-1);

            // First, so its zone is registered before the switches': zones are matched in order,
            // and the Sharing switch's click area runs a few pixels into the X's.
            drawRemoveX(body, entry);

            // Each switch reads as its own column heading, so on means what the heading says.
            // "Mute" on means silenced, and muting is the opt-in, so a new entry sits off.
            body.columnSwitch(0, 2, entry.mutePings, true, on -> {
                entry.mutePings = on;
                config.save();
            });
            // "Sharing" on means this player gets our coordinates. That is the default, which is
            // why the stored field is the negative one: hidden drops them from the sharing set.
            body.columnSwitch(1, 2, !entry.hidden, true, on -> {
                entry.hidden = !on;
                config.save();
                TeamLocatorClient.syncToServer();
            });
            body.advance(1);

            drawFaceAndName(body, entry);
            body.advance(SporeTheme.ROW_HEIGHT);
        }
    }

    /**
     * The remove X at the right end of a trust row. Drawn by hand rather than with
     * {@link dev.spog.spore.ui.widget.SporeIconButton}: that is a real widget, so it would not
     * scroll with the row, and it paints a panel border this row does not want.
     *
     * <p>Clicking only records the entry in {@link #pendingRemoval}; the list is edited on the
     * next draw, since zones fire from {@code mouseClicked} rather than from this pass.
     */
    private void drawRemoveX(Body body, TrustEntry entry) {
        int cx = body.right() - REMOVE_INSET;
        // Six below the text baseline puts the glyph's centre on the switches' centre line.
        int cy = body.y() + 6;
        // The glyph spans cx - ARM to cx + ARM + 2, so its middle is cx + 1.
        int left = cx + 1 - REMOVE_HALF;
        int top = cy - REMOVE_HALF;
        int size = REMOVE_HALF * 2;
        boolean hovered = SporeDraw.inside(body.mouseX(), body.mouseY(), left, top, size, size);

        // Faint until pointed at, so a row of them does not read as a column of warnings.
        int color = hovered ? REMOVE_COLOR_HOVER : REMOVE_COLOR;
        for (int r = -REMOVE_ARM; r <= REMOVE_ARM; r++) {
            // Each diagonal is two pixels wide on every row. Where the two meet in the middle
            // they cover the same pixels, and filling those twice with a translucent colour
            // draws them darker than the arms -- so overlapping runs are filled once.
            int a = cx + r;
            int b = cx - r;
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            if (hi < lo + 2) {
                body.graphics().fill(lo, cy + r, hi + 2, cy + r + 1, color);
            } else {
                body.graphics().fill(lo, cy + r, lo + 2, cy + r + 1, color);
                body.graphics().fill(hi, cy + r, hi + 2, cy + r + 1, color);
            }
        }

        body.addZone(new dev.spog.spore.ui.screen.Zone(left, top, left + size, top + size,
                () -> pendingRemoval = entry, CursorTypes.POINTING_HAND));
    }

    /** The name field and the Add button beside it. */
    private void drawAddPlayerRow(Body body) {
        body.graphics().text(body.font(), RelayLang.text("relay.config.add_player"),
                body.x(), body.y(), SporeTheme.LABEL_COLOR, false);

        int boxX = body.controlX();
        int top = body.y() - 4;
        nameInput.place(body.graphics(), boxX, top, 130, body.viewTop(), body.viewBottom(),
                nameInput.isFocused());
        // A pixel lower than place() centres it, which sits the text a half pixel high in the
        // frame. Moves only the text: place() has already drawn the frame.
        if (nameInput.widget().isVisible()) {
            nameInput.widget().setY(nameInput.widget().getY() + 1);
        }
        body.addZone(new dev.spog.spore.ui.screen.Zone(boxX, top, boxX + 130,
                top + SporeTheme.CONTROL_H, () -> setFocused(nameInput.widget()),
                CursorTypes.IBEAM));

        // The Add button, drawn rather than a widget so it scrolls with the
        // row it belongs to.
        int buttonX = boxX + 135;
        boolean hovered = body.mouseX() >= buttonX && body.mouseX() < buttonX + 65
                && body.mouseY() >= top && body.mouseY() < top + SporeTheme.CONTROL_H;
        dev.spog.spore.ui.theme.SporeDraw.panel(body.graphics(), buttonX, top, buttonX + 65,
                top + SporeTheme.CONTROL_H,
                hovered ? SporeTheme.BUTTON_FILL_HOVER : SporeTheme.BUTTON_FILL,
                hovered ? SporeTheme.CONTROL_BORDER_ACTIVE : SporeTheme.CONTROL_BORDER);
        dev.spog.spore.ui.theme.SporeDraw.centered(body.graphics(), body.font(),
                RelayLang.text("relay.config.add"), buttonX, top, 65, SporeTheme.CONTROL_H,
                SporeTheme.BUTTON_TEXT);
        body.addZone(new dev.spog.spore.ui.screen.Zone(buttonX, top, buttonX + 65,
                top + SporeTheme.CONTROL_H, this::addTypedPlayer));

        if (addStatus != null) {
            body.graphics().text(body.font(), addStatus, buttonX + 70, body.y(),
                    addStatusColor, false);
        }
        body.advance(SporeTheme.ROW_HEIGHT + 8);
    }

    /** A player's face and name, at the current row. */
    private void drawFaceAndName(Body body, TrustEntry entry) {
        UUID id = safeUuid(entry);
        PlayerSkin skin;
        // A name is preferred from the tab list, which is current, over the
        // stored one, which was right when the entry was added.
        String name = entry.name != null ? entry.name
                : (id != null ? id.toString().substring(0, 8) : "?");
        if (id != null && Minecraft.getInstance().getConnection() != null) {
            PlayerInfo info =
                    Minecraft.getInstance().getConnection().getPlayerInfo(id);
            skin = info != null ? info.getSkin() : DefaultPlayerSkin.get(id);
            if (info != null) {
                name = info.getProfile().name();
            }
        } else {
            skin = DefaultPlayerSkin.getDefaultSkin();
        }
        PlayerFaceExtractor.extractRenderState(body.graphics(), skin, body.x(), body.y() - 2, 14);
        body.graphics().text(body.font(), Component.literal(name),
                body.x() + 20, body.y(), SporeTheme.TITLE_COLOR, false);
    }

    // ------------------------------------------------------------- helpers

    private List<TrustEntry> currentList() {
        TeamConfig.TrustList list = config.activeList();
        // A mutable list even when disconnected in SERVER mode, so add and
        // remove callbacks that slip past their guards cannot throw
        // UnsupportedOperationException.
        return list != null ? list.trusted : new java.util.ArrayList<>();
    }

    /** The five colours for the signed-in account, or empty if there is not one yet. */
    private static List<String> pingPalette() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getUser() == null || mc.getUser().getProfileId() == null) {
            return List.of();
        }
        return PingPalette.forPlayer(mc.getUser().getProfileId());
    }

    /** The entry's id, or null when the stored value is not a valid uuid. */
    private static UUID safeUuid(TrustEntry entry) {
        try {
            return entry.uuid();
        } catch (Exception e) {
            return null;
        }
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
    }

    private void addTypedPlayer() {
        String typed = nameInput.value().trim();
        if (typed.isEmpty()) {
            return;
        }
        PlayerInfo listed = Minecraft.getInstance().getConnection() != null
                ? Minecraft.getInstance().getConnection().getPlayerInfo(typed)
                : null;
        if (listed != null) {
            addEntry(listed.getProfile().id(), listed.getProfile().name());
            nameInput.setValue("");
            addStatus = null;
            return;
        }
        addStatus = RelayLang.text("relay.config.lookup_pending", typed);
        addStatusColor = SporeTheme.MUTED_COLOR;
        NameLookup.resolve(typed).whenComplete((resolved, err) ->
                Minecraft.getInstance().execute(() -> {
                    if (resolved == null) {
                        addStatus = RelayLang.text("relay.config.lookup_failed", typed);
                        addStatusColor = SporeTheme.DANGER_TEXT;
                        return;
                    }
                    addEntry(resolved.id(), resolved.name());
                    nameInput.setValue("");
                    addStatus = null;
                }));
    }

    private Component alertSoundLabel(TeamConfig.AlertSound sound) {
        return RelayLang.text(sound == TeamConfig.AlertSound.ALARM
                ? "relay.config.alert_sound.alarm" : "relay.config.alert_sound.noteblocks");
    }

    private Component hudAlignLabel(TeamConfig.HudAlign align) {
        String key = switch (align) {
            case CENTER -> "relay.config.hud_align.center";
            case RIGHT -> "relay.config.hud_align.right";
            case TABLE -> "relay.config.hud_align.table";
            default -> "relay.config.hud_align.left";
        };
        return RelayLang.text(key);
    }

    /**
     * On/Off reuse vanilla's own components rather than mod-local copies, so a
     * resource pack that restyles {@code options.on}/{@code options.off}
     * restyles these too. Only LOWEST, which vanilla has no word for, is ours.
     */
    private Component armorDisplayLabel(TeamConfig.ArmorDisplay display) {
        return switch (display) {
            case OFF -> CommonComponents.OPTION_OFF;
            case LOWEST -> RelayLang.text("relay.config.hud_show_armor.lowest");
            default -> CommonComponents.OPTION_ON;
        };
    }

    private Component durabilityDisplayLabel(TeamConfig.DurabilityDisplay display) {
        String key = switch (display) {
            case BAR -> "bar";
            case NUMBER_ONLY -> "number_only";
            case BOTH -> "both";
        };
        return RelayLang.text("relay.config.hud_durability." + key);
    }

    private Component modeLabel(TeamConfig.Mode mode) {
        return RelayLang.text(mode == TeamConfig.Mode.GLOBAL
                ? "relay.config.active_list.global" : "relay.config.active_list.server");
    }
}
