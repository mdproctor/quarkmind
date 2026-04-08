package io.quarkmind.sc2.mock.scenario;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.SimulatedGame;

public class SpawnEnemyAttackScenario implements Scenario {
    @Override
    public void apply(SimulatedGame game) {
        game.spawnEnemyUnit(UnitType.ZEALOT, new Point2d(20, 20));
        game.spawnEnemyUnit(UnitType.ZEALOT, new Point2d(21, 20));
        game.spawnEnemyUnit(UnitType.STALKER, new Point2d(22, 20));
    }
}
