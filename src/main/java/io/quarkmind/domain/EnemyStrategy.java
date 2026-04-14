package io.quarkmind.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record EnemyStrategy(
    @JsonProperty("buildOrder")      List<EnemyBuildStep>  buildOrder,
    @JsonProperty("loop")            boolean               loop,
    @JsonProperty("mineralsPerTick") int                   mineralsPerTick,
    @JsonProperty("attackConfig")    EnemyAttackConfig     attackConfig
) {
    public static EnemyStrategy defaultProtoss() {
        return new EnemyStrategy(
            List.of(
                new EnemyBuildStep(UnitType.ZEALOT),
                new EnemyBuildStep(UnitType.ZEALOT),
                new EnemyBuildStep(UnitType.ZEALOT)
            ),
            true, 2,
            new EnemyAttackConfig(3, 200)
        );
    }
}
