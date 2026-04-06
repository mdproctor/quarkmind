package org.acme.starcraft.sc2.mock.scenario;

import org.acme.starcraft.sc2.mock.SimulatedGame;

public class SupplyAlmostCappedScenario implements Scenario {
    @Override
    public void apply(SimulatedGame game) {
        // Set supply used to cap - 1 (tight supply)
        game.setSupplyUsed(game.snapshot().supply() - 1);
    }
}
