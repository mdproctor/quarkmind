package org.acme.starcraft.sc2.mock.scenario;

import org.acme.starcraft.sc2.mock.SimulatedGame;

@FunctionalInterface
public interface Scenario {
    void apply(SimulatedGame game);
}
