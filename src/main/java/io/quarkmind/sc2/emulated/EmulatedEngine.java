package io.quarkmind.sc2.emulated;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.SC2Engine;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SC2Engine implementation backed by {@link EmulatedGame}.
 * Active only in the {@code %emulated} profile.
 * Mirrors {@link io.quarkmind.sc2.mock.MockEngine} in structure.
 */
@IfBuildProfile("emulated")
@ApplicationScoped
public class EmulatedEngine implements SC2Engine {

    private static final Logger log = Logger.getLogger(EmulatedEngine.class);

    private final EmulatedGame game = new EmulatedGame();
    private final IntentQueue intentQueue;
    private final List<Consumer<GameState>> frameListeners = new ArrayList<>();
    private boolean connected = false;

    @Inject
    public EmulatedEngine(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override
    public void connect() {
        connected = true;
        log.info("[EMULATED] Engine connected");
    }

    @Override
    public void joinGame() {
        game.reset();
        log.info("[EMULATED] Joined game — 12 probes harvesting, emulated physics active");
    }

    @Override
    public void leaveGame() {
        connected = false;
        log.info("[EMULATED] Left game");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void tick() {
        game.tick();
    }

    @Override
    public GameState observe() {
        GameState state = game.snapshot();
        frameListeners.forEach(l -> l.accept(state));
        return state;
    }

    @Override
    public void dispatch() {
        intentQueue.drainAll().forEach(intent -> {
            log.debugf("[EMULATED] Dispatching: %s", intent);
            game.applyIntent(intent);
        });
    }

    @Override
    public void addFrameListener(Consumer<GameState> listener) {
        frameListeners.add(listener);
    }
}
