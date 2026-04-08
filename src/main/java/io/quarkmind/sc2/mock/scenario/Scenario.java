package io.quarkmind.sc2.mock.scenario;

import io.quarkmind.sc2.mock.SimulatedGame;

@FunctionalInterface
public interface Scenario {
    void apply(SimulatedGame game);
}
