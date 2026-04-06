package org.acme.starcraft.sc2.mock.scenario;

import org.acme.starcraft.domain.Point2d;
import org.acme.starcraft.domain.UnitType;
import org.acme.starcraft.sc2.mock.SimulatedGame;

public class EnemyExpandsScenario implements Scenario {
    @Override
    public void apply(SimulatedGame game) {
        // Enemy probe heading to expansion location
        game.spawnEnemyUnit(UnitType.PROBE, new Point2d(50, 50));
    }
}
