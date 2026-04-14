package io.quarkmind.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EnemyStrategyTest {

    @Test
    void buildStepHoldsUnitType() {
        EnemyBuildStep step = new EnemyBuildStep(UnitType.STALKER);
        assertThat(step.unitType()).isEqualTo(UnitType.STALKER);
    }

    @Test
    void attackConfigHoldsBothTriggers() {
        EnemyAttackConfig cfg = new EnemyAttackConfig(3, 200);
        assertThat(cfg.armyThreshold()).isEqualTo(3);
        assertThat(cfg.attackIntervalFrames()).isEqualTo(200);
    }

    @Test
    void defaultProtossHasThreeZealots() {
        EnemyStrategy s = EnemyStrategy.defaultProtoss();
        assertThat(s.buildOrder()).hasSize(3);
        assertThat(s.buildOrder()).allMatch(step -> step.unitType() == UnitType.ZEALOT);
    }

    @Test
    void defaultProtossConfigIsCorrect() {
        EnemyStrategy s = EnemyStrategy.defaultProtoss();
        assertThat(s.loop()).isTrue();
        assertThat(s.mineralsPerTick()).isEqualTo(2);
        assertThat(s.attackConfig().armyThreshold()).isEqualTo(3);
        assertThat(s.attackConfig().attackIntervalFrames()).isEqualTo(200);
    }

    @Test
    void strategyRoundTripsViaJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EnemyStrategy original = EnemyStrategy.defaultProtoss();
        String json = mapper.writeValueAsString(original);

        assertThat(json).contains("\"buildOrder\"");
        assertThat(json).contains("\"ZEALOT\"");
        assertThat(json).contains("\"loop\"");
        assertThat(json).contains("\"mineralsPerTick\"");
        assertThat(json).contains("\"attackConfig\"");

        EnemyStrategy restored = mapper.readValue(json, EnemyStrategy.class);
        assertThat(restored.buildOrder()).hasSize(3);
        assertThat(restored.loop()).isTrue();
        assertThat(restored.mineralsPerTick()).isEqualTo(2);
        assertThat(restored.attackConfig().armyThreshold()).isEqualTo(3);
    }

    @Test
    void buildStepRoundTripsViaJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EnemyBuildStep step = new EnemyBuildStep(UnitType.STALKER);
        String json = mapper.writeValueAsString(step);
        EnemyBuildStep restored = mapper.readValue(json, EnemyBuildStep.class);
        assertThat(restored.unitType()).isEqualTo(UnitType.STALKER);
    }

    @Test
    void customStrategyCanBeConstructedProgrammatically() {
        EnemyStrategy s = new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.STALKER), new EnemyBuildStep(UnitType.IMMORTAL)),
            false, 5,
            new EnemyAttackConfig(2, 100));
        assertThat(s.buildOrder()).hasSize(2);
        assertThat(s.buildOrder().get(0).unitType()).isEqualTo(UnitType.STALKER);
        assertThat(s.loop()).isFalse();
    }
}
