package io.quarkmind.domain;

import java.util.List;

public record EnemyStrategy(
    List<EnemyBuildStep> buildOrder,
    boolean              loop,
    int                  mineralsPerTick,
    EnemyAttackConfig    attackConfig
) {
    public static EnemyStrategy defaultProtoss() {
        return new EnemyStrategy(
            List.of(
                new EnemyBuildStep(UnitType.ZEALOT),
                new EnemyBuildStep(UnitType.ZEALOT),
                new EnemyBuildStep(UnitType.ZEALOT)
            ),
            true, 2,
            new EnemyAttackConfig(3, 200, 30, 50)
        );
    }
}
