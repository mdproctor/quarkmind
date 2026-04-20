package io.quarkmind.plugin;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.scouting.ScoutingRuleUnit;
import io.quarkmind.plugin.scouting.events.EnemyArmyNearBase;
import io.quarkmind.plugin.scouting.events.EnemyExpansionSeen;
import io.quarkmind.plugin.scouting.events.EnemyUnitFirstSeen;
import io.quarkmind.domain.Point2d;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests each DroolsScoutingTask.drl rule in isolation.
 *
 * <p>Requires {@code @QuarkusTest} — {@link org.drools.ruleunits.api.DataSource#createStore()}
 * is initialised at Quarkus build time (GE-0053).
 */
@QuarkusTest
class DroolsScoutingRulesTest {

    @Inject RuleUnit<ScoutingRuleUnit> ruleUnit;

    // ---- Build-order rules ----

    @Test
    void fourRoachesDetectsZergRoachRush() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        for (int i = 0; i < 4; i++) data.getUnitEvents().add(unit(UnitType.ROACH, i * 1000L));
        fire(data);
        assertThat(data.getDetectedBuilds()).contains("ZERG_ROACH_RUSH");
    }

    @Test
    void threeRoachesDoesNotDetectRoachRush() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        for (int i = 0; i < 3; i++) data.getUnitEvents().add(unit(UnitType.ROACH, i * 1000L));
        fire(data);
        assertThat(data.getDetectedBuilds()).doesNotContain("ZERG_ROACH_RUSH");
    }

    @Test
    void fiveMarinesDetectsTerran3Rax() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        for (int i = 0; i < 5; i++) data.getUnitEvents().add(unit(UnitType.MARINE, i * 1000L));
        fire(data);
        assertThat(data.getDetectedBuilds()).contains("TERRAN_3RAX");
    }

    @Test
    void fourMarinesDoesNotDetect3Rax() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        for (int i = 0; i < 4; i++) data.getUnitEvents().add(unit(UnitType.MARINE, i * 1000L));
        fire(data);
        assertThat(data.getDetectedBuilds()).doesNotContain("TERRAN_3RAX");
    }

    @Test
    void fourStalkersDetectsProtoss4Gate() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        for (int i = 0; i < 4; i++) data.getUnitEvents().add(unit(UnitType.STALKER, i * 1000L));
        fire(data);
        assertThat(data.getDetectedBuilds()).contains("PROTOSS_4GATE");
    }

    @Test
    void mixedTwoStalkersAndTwoZealotsDetects4Gate() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        for (int i = 0; i < 2; i++) data.getUnitEvents().add(unit(UnitType.STALKER, i * 1000L));
        for (int i = 0; i < 2; i++) data.getUnitEvents().add(unit(UnitType.ZEALOT, i * 1000L));
        fire(data);
        assertThat(data.getDetectedBuilds()).contains("PROTOSS_4GATE");
    }

    // ---- Timing attack rule ----

    @Test
    void armyNearBaseEventDetectsTimingAttack() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        data.getArmyNearBaseEvents().add(new EnemyArmyNearBase(5, 1000L));
        fire(data);
        assertThat(data.getTimingAlerts()).isNotEmpty();
    }

    @Test
    void noArmyNearBaseProducesNoTimingAlert() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        fire(data);
        assertThat(data.getTimingAlerts()).isEmpty();
    }

    // ---- Expansion posture rules ----

    @Test
    void unitEventsWithNoExpansionDetectsAllIn() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        data.getUnitEvents().add(unit(UnitType.MARINE, 1000L));
        fire(data);
        assertThat(data.getPostureDecisions()).contains("ALL_IN");
    }

    @Test
    void expansionEventDetectsMacro() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        data.getExpansionEvents().add(new EnemyExpansionSeen(new Point2d(64, 64), 1000L));
        fire(data);
        assertThat(data.getPostureDecisions()).contains("MACRO");
    }

    @Test
    void noEventsPostureIsUnknown() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        fire(data);
        assertThat(data.getPostureDecisions()).isEmpty();
    }

    @Test
    void expansionPreventsAllInPosture() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        data.getUnitEvents().add(unit(UnitType.MARINE, 1000L));
        data.getExpansionEvents().add(new EnemyExpansionSeen(new Point2d(64, 64), 1000L));
        fire(data);
        assertThat(data.getPostureDecisions()).doesNotContain("ALL_IN");
        assertThat(data.getPostureDecisions()).contains("MACRO");
    }

    // ---- Helpers ----

    private void fire(ScoutingRuleUnit data) {
        try (RuleUnitInstance<ScoutingRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }
    }

    private EnemyUnitFirstSeen unit(UnitType type, long gameTimeMs) {
        return new EnemyUnitFirstSeen(type, gameTimeMs);
    }
}
