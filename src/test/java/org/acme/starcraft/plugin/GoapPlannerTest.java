package org.acme.starcraft.plugin;

import org.acme.starcraft.plugin.tactics.GoapAction;
import org.acme.starcraft.plugin.tactics.GoapPlanner;
import org.acme.starcraft.plugin.tactics.WorldState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoapPlannerTest {

    // ---- WorldState ----

    @Test
    void worldStateWithReturnsCopyWithNewValue() {
        WorldState original = new WorldState(Map.of("inRange", false));
        WorldState updated  = original.with("inRange", true);
        assertThat(updated.get("inRange")).isTrue();
        assertThat(original.get("inRange")).isFalse(); // immutable
    }

    @Test
    void worldStateSatisfiesReturnsTrueWhenConditionIsTrue() {
        WorldState state = new WorldState(Map.of("unitSafe", true));
        assertThat(state.satisfies("unitSafe")).isTrue();
    }

    @Test
    void worldStateSatisfiesReturnsFalseWhenConditionIsFalse() {
        WorldState state = new WorldState(Map.of("unitSafe", false));
        assertThat(state.satisfies("unitSafe")).isFalse();
    }

    @Test
    void worldStateSatisfiesReturnsFalseForAbsentCondition() {
        WorldState state = new WorldState(new HashMap<>());
        assertThat(state.satisfies("unknown")).isFalse();
    }

    // ---- GoapAction ----

    @Test
    void goapActionIsApplicableWhenPreconditionsMet() {
        GoapAction attack = attack();
        WorldState state  = new WorldState(Map.of("inRange", true, "enemyVisible", true));
        assertThat(attack.isApplicable(state)).isTrue();
    }

    @Test
    void goapActionNotApplicableWhenPreconditionUnmet() {
        GoapAction attack = attack();
        WorldState state  = new WorldState(Map.of("inRange", false, "enemyVisible", true));
        assertThat(attack.isApplicable(state)).isFalse();
    }

    @Test
    void goapActionApplyToProducesNewStateWithEffects() {
        GoapAction move = moveToEngage();
        WorldState state = new WorldState(Map.of("inRange", false, "enemyVisible", true));
        WorldState result = move.applyTo(state);
        assertThat(result.get("inRange")).isTrue();
        assertThat(state.get("inRange")).isFalse(); // original unchanged
    }

    // ---- GoapPlanner (these will fail until Task 2) ----

    @Test
    void plannerReturnsEmptyWhenGoalAlreadySatisfied() {
        GoapPlanner planner = new GoapPlanner();
        WorldState state = new WorldState(Map.of("enemyEliminated", true));
        List<GoapAction> plan = planner.plan(state, "enemyEliminated", List.of(attack()));
        assertThat(plan).isEmpty();
    }

    @Test
    void plannerReturnsEmptyWhenGoalUnreachable() {
        GoapPlanner planner = new GoapPlanner();
        WorldState state = new WorldState(Map.of("inRange", false, "enemyVisible", false));
        List<GoapAction> plan = planner.plan(state, "enemyEliminated", List.of(attack()));
        assertThat(plan).isEmpty();
    }

    @Test
    void plannerFindsDirectPlanForInRangeUnit() {
        GoapPlanner planner = new GoapPlanner();
        WorldState state = new WorldState(Map.of("inRange", true, "enemyVisible", true,
                                                  "lowHealth", false, "enemyEliminated", false));
        List<GoapAction> plan = planner.plan(state, "enemyEliminated", List.of(moveToEngage(), attack()));
        assertThat(plan).extracting(GoapAction::name).containsExactly("ATTACK");
    }

    @Test
    void plannerFindsChainedPlanForOutOfRangeUnit() {
        GoapPlanner planner = new GoapPlanner();
        WorldState state = new WorldState(Map.of("inRange", false, "enemyVisible", true,
                                                  "lowHealth", false, "enemyEliminated", false));
        List<GoapAction> plan = planner.plan(state, "enemyEliminated", List.of(moveToEngage(), attack()));
        assertThat(plan).extracting(GoapAction::name).containsExactly("MOVE_TO_ENGAGE", "ATTACK");
    }

    @Test
    void plannerFindsRetreatForLowHealthUnit() {
        GoapPlanner planner = new GoapPlanner();
        WorldState state = new WorldState(Map.of("lowHealth", true, "unitSafe", false));
        List<GoapAction> plan = planner.plan(state, "unitSafe", List.of(retreat(), attack(), moveToEngage()));
        assertThat(plan).extracting(GoapAction::name).containsExactly("RETREAT");
    }

    @Test
    void plannerPicksCheaperOfTwoPaths() {
        GoapAction cheapMove   = new GoapAction("CHEAP_MOVE",
            Map.of("enemyVisible", true, "inRange", false), Map.of("inRange", true), 1);
        GoapAction cheapAttack = new GoapAction("CHEAP_ATTACK",
            Map.of("inRange", true, "enemyVisible", true), Map.of("enemyEliminated", true), 1);
        GoapAction expensive   = new GoapAction("EXPENSIVE_ATTACK",
            Map.of("inRange", true, "enemyVisible", true), Map.of("enemyEliminated", true), 10);
        WorldState state = new WorldState(Map.of("inRange", true, "enemyVisible", true,
                                                  "enemyEliminated", false));
        GoapPlanner planner = new GoapPlanner();
        List<GoapAction> plan = planner.plan(state, "enemyEliminated",
            List.of(cheapMove, expensive, cheapAttack));
        assertThat(plan).extracting(GoapAction::name).containsExactly("CHEAP_ATTACK");
    }

    // ---- Helpers ----

    private GoapAction attack() {
        return new GoapAction("ATTACK",
            Map.of("inRange", true, "enemyVisible", true),
            Map.of("enemyEliminated", true), 2);
    }

    private GoapAction moveToEngage() {
        return new GoapAction("MOVE_TO_ENGAGE",
            Map.of("enemyVisible", true, "inRange", false),
            Map.of("inRange", true), 1);
    }

    private GoapAction retreat() {
        return new GoapAction("RETREAT",
            Map.of("lowHealth", true),
            Map.of("unitSafe", true), 1);
    }
}
