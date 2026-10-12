package dev.spog.relayspectate.ui;

import dev.spog.relayspectate.SpectateLang;
import dev.spog.relayspectate.SpectateManager;
import dev.spog.relayspectate.host.ViewerSession;
import dev.spog.relayspectate.SpectateConfig;
import dev.spog.relayspectate.SpectateTrust;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

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
            addDrawableChild(ButtonWidget.builder(
                    SpectateLang.text("menu.stop"),
                    b -> {
                        manager.stopViewing();
                        clearAndInit();
                    }).dimensions(centerX, y, BUTTON_WIDTH, 20).build());
            y += ROW_HEIGHT;
        }

        for (Candidate candidate : candidates()) {
            addDrawableChild(ButtonWidget.builder(
                    Text.literal(candidate.name()),
                    b -> {
                        manager.requestSession(candidate.id());
                        clearAndInit();
                    }).dimensions(centerX, y, BUTTON_WIDTH, 20).build());
            y += ROW_HEIGHT;
            if (y > height - 60) {
                // The list is capped by the window rather than scrolled: a trust list long enough
                // to need scrolling is not the case this addon is for, and a scroll widget here
                // would be more code than the feature warrants.
                break;
            }
        }

        // Sits above Close, away from the target rows, so "stop people watching me" cannot be
        // misclicked while picking who to watch.
        addDrawableChild(ButtonWidget.builder(
                allowLabel(),
                b -> {
                    SpectateConfig config = SpectateConfig.get();
                    config.allowSpectators = !config.allowSpectators;
                    config.save();
                    // Pushed immediately rather than waiting for the periodic upload: this is a
                    // revocation, and a revocation that takes effect in a few seconds' time is
                    // not one the player would consider honoured.
                    SpectateManager.get().sendTrust(SpectateTrust.allowedViewers());
                    clearAndInit();
                }).dimensions(centerX, height - 56, BUTTON_WIDTH, 20).build());

        addDrawableChild(ButtonWidget.builder(
                SpectateLang.text("menu.close"),
                b -> onClose()).dimensions(centerX, height - 32, BUTTON_WIDTH, 20).build());
    }

    private static Text allowLabel() {
        return SpectateLang.text(SpectateConfig.get().allowSpectators
                ? "menu.allow_on" : "menu.allow_off");
    }

    private record Candidate(UUID id, String name) {
    }

    /** This addon's trust list, as spectate candidates. */
    private List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        for (SpectateTrust.Entry entry : SpectateTrust.candidates()) {
            out.add(new Candidate(entry.id(), entry.display()));
        }
        return out;
    }

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY,
                                   float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.text(textRenderer, title.getString(),
                width / 2 - textRenderer.getWidth(title.getString()) / 2, 16, 0xFFFFFFFF, true);

        SpectateManager manager = SpectateManager.get();
        ViewerSession active = manager.viewing();
        String status;
        if (active != null) {
            status = active.isInGame()
                    ? SpectateLang.string("status.watching", shortId(active.target()))
                    : SpectateLang.string("status.loading", shortId(active.target()));
        } else if (manager.lastDenial() != null) {
            status = manager.lastDenial();
        } else if (client != null && client.world != null) {
            // Spectating replaces the client's world, so it cannot start from in-game. Said here
            // rather than on a failed click, which would look like the button did nothing.
            status = SpectateLang.string("error.in_world");
        } else if (!manager.canStartSession()) {
            status = SpectateLang.string("error.not_connected");
        } else if (candidates().isEmpty()) {
            status = SpectateLang.string("menu.empty");
        } else {
            status = SpectateLang.string("menu.hint");
        }
        graphics.text(textRenderer, status,
                width / 2 - textRenderer.getWidth(status) / 2, height - 48, 0xFFAAAAAA, true);
    }

    private static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    @Override
    public void onClose() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
