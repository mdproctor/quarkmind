package io.quarkmind.sc2.mock.scenario;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.SimulatedGame;

public class EnemyExpandsScenario implements Scenario {
    @Override
    public void apply(SimulatedGame game) {
        // Enemy probe heading to expansion location
        game.spawnEnemyUnit(UnitType.PROBE, new Point2d(50, 50));
    }
}
