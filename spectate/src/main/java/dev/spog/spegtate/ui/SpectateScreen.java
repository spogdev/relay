package dev.spog.spegtate.ui;

import dev.spog.spegtate.SpectateLang;
import dev.spog.spegtate.SpectateManager;
import dev.spog.spegtate.host.ViewerSession;
import dev.spog.spegtate.SpectateConfig;
import dev.spog.spegtate.SpectateRoster;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lists the teammates this player can spectate, and starts a session on click.
 *
 * <p>The list comes from this addon's own trust list, which is the closest thing available to "who
 * might let me watch them". It is only a candidate list: whether a given player actually trusts
 * this one is decided by the service, which holds both sides' trust sets -- this client knows who
 * <em>it</em> trusts, not who trusts it. So a row may be clicked and refused, and the refusal
 * arrives as a deny frame rather than being predicted here.
 *
 * <p>The screen also carries the "allow others to spectate me" switch, because this is the only
 * place the feature surfaces and a privacy control nobody can find is not much of a control.
 *
 * <p>Reachable from the main menu as well as in-game, which is the point of the feature; it reads
 * no world state.
 */
public final class SpectateScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int BUTTON_WIDTH = 200;
    private static final int LIST_TOP = 40;

    // The bottom of the screen stacks upward: Close, then the allow toggle, then the status line.
    // Derived from each other rather than written as three independent offsets, which is how the
    // status text and the toggle came to be drawn on top of one another.
    private static final int BUTTON_HEIGHT = 20;

    /** Status-line colours: working, broken, and neither. */
    private static final int GOOD = 0xFF55FF55;
    private static final int BAD = 0xFFFF5555;
    private static final int NEUTRAL = 0xFFAAAAAA;
    private static final int CLOSE_Y = 32;
    private static final int ALLOW_Y = CLOSE_Y + BUTTON_HEIGHT + 4;
    /** Baseline of the status line, clear of the toggle above it. */
    private static final int STATUS_Y = ALLOW_Y + BUTTON_HEIGHT + 2;

    private final Screen parent;

    public SpectateScreen(Screen parent) {
        super(SpectateLang.text("menu.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        SpectateManager manager = SpectateManager.get();
        ViewerSession active = manager.viewing();
        int centerX = width / 2 - BUTTON_WIDTH / 2;
        int y = LIST_TOP;

        if (active != null) {
            addRenderableWidget(Button.builder(
                    SpectateLang.text("menu.stop"),
                    b -> {
                        manager.stopViewing();
                        rebuildWidgets();
                    }).bounds(centerX, y, BUTTON_WIDTH, 20).build());
            y += ROW_HEIGHT;
        }

        for (Candidate candidate : candidates()) {
            addRenderableWidget(Button.builder(
                    Component.literal(candidate.name()),
                    b -> {
                        manager.requestSession(candidate.id());
                        rebuildWidgets();
                    }).bounds(centerX, y, BUTTON_WIDTH, 20).build());
            y += ROW_HEIGHT;
            if (y > height - STATUS_Y - ROW_HEIGHT) {
                // The list is capped by the window rather than scrolled: a private mod with
                // more people online at once than fit on screen is not the case this is for,
                // and a scroll widget here would be more code than the feature warrants.
                break;
            }
        }

        // Sits above Close, away from the target rows, so "stop people watching me" cannot be
        // misclicked while picking who to watch.
        addRenderableWidget(Button.builder(
                allowLabel(),
                b -> {
                    SpectateConfig config = SpectateConfig.get();
                    config.allowSpectators = !config.allowSpectators;
                    config.save();
                    // Pushed immediately rather than waiting for the periodic upload: this is a
                    // revocation, and a revocation that takes effect in a few seconds' time is
                    // not one the player would consider honoured.
                    SpectateManager.get().sendConsent(config.allowSpectators);
                    rebuildWidgets();
                }).bounds(centerX, height - ALLOW_Y, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(
                SpectateLang.text("menu.close"),
                b -> onClose()).bounds(centerX, height - CLOSE_Y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private static Component allowLabel() {
        return SpectateLang.text(SpectateConfig.get().allowSpectators
                ? "menu.allow_on" : "menu.allow_off");
    }

    private record Candidate(UUID id, String name) {
    }

    /**
     * Who the service says is online and accepting viewers, excluding this client.
     *
     * <p>The service already leaves each recipient out of its own roster, so this filter is a
     * second line rather than the only one. It is here because the roster arrives over the
     * network: a service running an older build, or one that has not yet learned this client's
     * id, would otherwise put a self row in the menu whose click is refused with "cannot
     * spectate yourself". Filtering locally costs nothing and does not depend on the far end
     * being right.
     */
    private List<Candidate> candidates() {
        UUID self = selfId();
        List<Candidate> out = new ArrayList<>();
        for (SpectateRoster.Peer peer : SpectateRoster.peers()) {
            if (self != null && self.equals(peer.id())) {
                continue;
            }
            out.add(new Candidate(peer.id(), peer.display()));
        }
        return out;
    }

    /** This client's own player id, or null if the session has none. */
    private UUID selfId() {
        return minecraft == null || minecraft.getUser() == null
                ? null : minecraft.getUser().getProfileId();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, title.getString(),
                width / 2 - font.width(title.getString()) / 2, 16, 0xFFFFFFFF, true);

        SpectateManager manager = SpectateManager.get();
        ViewerSession active = manager.viewing();
        String status;
        // Chosen by the branch that picks the message, not matched against its
        // wording afterwards: each branch already knows whether it is reporting a
        // working state or a problem, and text matching would break the first time
        // a string was reworded.
        int colour;
        if (active != null) {
            status = active.isInGame()
                    ? SpectateLang.string("status.watching", shortId(active.target()))
                    : SpectateLang.string("status.loading", shortId(active.target()));
            colour = GOOD;
        } else if (manager.lastDenial() != null) {
            status = manager.lastDenial();
            colour = BAD;
        } else if (minecraft != null && minecraft.level != null) {
            // Spectating replaces the client's world, so it cannot start from in-game. Said here
            // rather than on a failed click, which would look like the button did nothing.
            status = SpectateLang.string("error.in_world");
            colour = BAD;
        } else if (!manager.canStartSession()) {
            status = SpectateLang.string("error.not_connected");
            colour = BAD;
        } else if (candidates().isEmpty()) {
            status = SpectateLang.string("menu.empty");
            // Nobody online is not a fault -- nothing is broken and there
            // is nothing to fix -- so it stays neutral.
            colour = NEUTRAL;
        } else {
            status = SpectateLang.string("menu.hint");
            colour = NEUTRAL;
        }
        graphics.text(font, status,
                width / 2 - font.width(status) / 2, height - STATUS_Y, colour, true);
    }

    private static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
