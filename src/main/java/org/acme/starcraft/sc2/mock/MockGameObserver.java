package org.acme.starcraft.sc2.mock;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.domain.GameState;
import org.acme.starcraft.sc2.GameObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@UnlessBuildProfile("sc2")
@ApplicationScoped
public class MockGameObserver implements GameObserver {
    private final SimulatedGame game;
    private final List<Consumer<GameState>> frameListeners = new ArrayList<>();

    @Inject
    public MockGameObserver(SimulatedGame game) {
        this.game = game;
    }

    @Override
    public GameState observe() {
        GameState state = game.snapshot();
        frameListeners.forEach(l -> l.accept(state));
        return state;
    }

    @Override
    public void addFrameListener(Consumer<GameState> listener) {
        frameListeners.add(listener);
    }
}
