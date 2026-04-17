package io.quarkmind.plugin;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.drools.TacticsRuleUnit;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for StarCraftTactics.drl rules.
 *
 * <p>Requires {@code @QuarkusTest} — {@code DataSource.createStore()} is initialised
 * at Quarkus build time and unavailable in plain JUnit (GE-0053).
 */
@QuarkusTest
class DroolsTacticsRuleUnitTest {

    @Inject RuleUnit<TacticsRuleUnit> ruleUnit;

    // ---- Phase 1: Group Classification ----

    @Test
    void unitBelowThirtyPercentHealthClassifiedAsLowHealth() {
        TacticsRuleUnit data = attack(List.of(stalker("s-0", 20, 100)), List.of(enemy()), List.of());
        fire(data);
        assertThat(data.getGroupDecisions())
            .anyMatch(g -> g.startsWith("low-health:UNIT_SAFE:s-0"));
    }

    @Test
    void unitWithinRangeClassifiedAsInRange() {
        TacticsRuleUnit data = attack(List.of(stalker("s-0", 80, 80)), List.of(enemy()), List.of("s-0"));
        fire(data);
        assertThat(data.getGroupDecisions())
            .anyMatch(g -> g.startsWith("in-range:ENEMY_ELIMINATED:s-0"));
    }

    @Test
    void healthyUnitWithEnemyNotInRangeClassifiedAsOutOfRange() {
        TacticsRuleUnit data = attack(List.of(stalker("s-0", 80, 80)), List.of(enemy()), List.of());
        fire(data);
        assertThat(data.getGroupDecisions())
            .anyMatch(g -> g.startsWith("out-of-range:ENEMY_ELIMINATED:s-0"));
    }

    @Test
    void mixedArmyProducesThreeGroups() {
        List<Unit> army = List.of(
            stalker("low",  20, 100),
            stalker("near", 80, 80),
            stalker("far",  80, 80)
        );
        TacticsRuleUnit data = attack(army, List.of(enemy()), List.of("near"));
        fire(data);
        assertThat(data.getGroupDecisions())
            .anyMatch(g -> g.startsWith("low-health:"))
            .anyMatch(g -> g.startsWith("in-range:"))
            .anyMatch(g -> g.startsWith("out-of-range:"));
    }

    @Test
    void noEnemiesProducesNoGroups() {
        TacticsRuleUnit data = attack(List.of(stalker("s-0", 80, 80)), List.of(), List.of());
        fire(data);
        assertThat(data.getGroupDecisions()).isEmpty();
    }

    @Test
    void emptyArmyProducesNoGroups() {
        TacticsRuleUnit data = attack(List.of(), List.of(enemy()), List.of());
        fire(data);
        assertThat(data.getGroupDecisions()).isEmpty();
    }

    @Test
    void macroStrategyProducesNoGroups() {
        TacticsRuleUnit data = new TacticsRuleUnit();
        data.setStrategyGoal("MACRO");
        data.getArmy().add(stalker("s-0", 80, 80));
        data.getEnemies().add(enemy());
        fire(data);
        assertThat(data.getGroupDecisions()).isEmpty();
    }

    // ---- Phase 2: Action Emission ----

    @Test
    void lowHealthGroupEmitsRetreatAction() {
        TacticsRuleUnit data = attack(List.of(stalker("s-0", 20, 100)), List.of(enemy()), List.of());
        fire(data);
        assertThat(data.getActionDecisions()).contains("RETREAT:1");
    }

    @Test
    void inRangeGroupEmitsAttackAction() {
        TacticsRuleUnit data = attack(List.of(stalker("s-0", 80, 80)), List.of(enemy()), List.of("s-0"));
        fire(data);
        assertThat(data.getActionDecisions()).contains("ATTACK:2");
    }

    @Test
    void outOfRangeGroupEmitsMoveToEngageAction() {
        TacticsRuleUnit data = attack(List.of(stalker("s-0", 80, 80)), List.of(enemy()), List.of());
        fire(data);
        assertThat(data.getActionDecisions()).contains("MOVE_TO_ENGAGE:1");
    }

    // ---- Helpers ----

    private void fire(TacticsRuleUnit data) {
        try (RuleUnitInstance<TacticsRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }
    }

    private TacticsRuleUnit attack(List<Unit> army, List<Unit> enemies, List<String> inRangeTags) {
        TacticsRuleUnit data = new TacticsRuleUnit();
        data.setStrategyGoal("ATTACK");
        army.forEach(data.getArmy()::add);
        enemies.forEach(data.getEnemies()::add);
        inRangeTags.forEach(data.getInRangeTags()::add);
        return data;
    }

    private Unit stalker(String tag, int health, int maxHealth) {
        return new Unit(tag, UnitType.STALKER, new Point2d(10, 10), health, maxHealth, 80, 80, 0);
    }

    private Unit enemy() {
        return new Unit("e-0", UnitType.ZEALOT, new Point2d(15, 15), 100, 100, 50, 50, 0);
    }
}
