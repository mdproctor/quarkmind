package io.quarkmind.sc2.mock.scenario;

import io.quarkmind.sc2.mock.SimulatedGame;

public class SetResourcesScenario implements Scenario {
    @Override
    public void apply(SimulatedGame game) {
        game.setMinerals(500);
        game.setVespene(200);
    }
}
