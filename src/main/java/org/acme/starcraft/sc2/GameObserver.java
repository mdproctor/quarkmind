package org.acme.starcraft.sc2;

import org.acme.starcraft.domain.GameState;
import java.util.function.Consumer;

public interface GameObserver {
    GameState observe();
    void addFrameListener(Consumer<GameState> listener);
}
