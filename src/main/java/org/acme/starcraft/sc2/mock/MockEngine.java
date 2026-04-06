package org.acme.starcraft.sc2.mock;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.domain.GameState;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.SC2Engine;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Mock engine — drives the full agent loop against {@link SimulatedGame}.
 * Active in all profiles except {@code %sc2}.
 */
@UnlessBuildProfile(anyOf = {"sc2", "replay"})
@ApplicationScoped
public class MockEngine implements SC2Engine {

    private static final Logger log = Logger.getLogger(MockEngine.class);

    private final SimulatedGame game;
    private final IntentQueue intentQueue;
    private final List<Consumer<GameState>> frameListeners = new ArrayList<>();
    private boolean connected = false;

    @Inject
    public MockEngine(SimulatedGame game, IntentQueue intentQueue) {
        this.game = game;
        this.intentQueue = intentQueue;
    }

    @Override
    public void connect() {
        connected = true;
        log.info("[MOCK] Engine connected");
    }

    @Override
    public void joinGame() {
        game.reset();
        log.info("[MOCK] Joined game");
    }

    @Override
    public void leaveGame() {
        connected = false;
        log.info("[MOCK] Left game");
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
            log.debugf("[MOCK] Dispatching: %s", intent);
            game.applyIntent(intent);
        });
    }

    @Override
    public void addFrameListener(Consumer<GameState> listener) {
        frameListeners.add(listener);
    }
}
