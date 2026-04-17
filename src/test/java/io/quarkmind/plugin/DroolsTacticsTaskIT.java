package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.TacticsTask;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for DroolsTacticsTask.
 *
 * <p>Each test documents one complete pipeline scenario: CaseFile state → Drools
 * group classification → A* planning → Intent emission.
 */
@QuarkusTest
class DroolsTacticsTaskIT {

    @Inject @CaseType("starcraft-game") TacticsTask tacticsTask;
    @Inject IntentQueue intentQueue;

    @BeforeEach @AfterEach
    void drainQueue() { intentQueue.drainAll(); }

    @Test
    void attackAllUnitsInRangeEmitsAttackIntents() {
        var cf = caseFile("ATTACK",
            List.of(stalker("s-0", new Point2d(10, 10), 80, 80),
                    stalker("s-1", new Point2d(11, 10), 80, 80)),
            List.of(enemy(new Point2d(14, 10))),
            new Point2d(14, 10));
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending())
            .hasSize(2)
            .allMatch(i -> i instanceof AttackIntent);
    }

    @Test
    void attackAllUnitsOutOfRangeEmitsMoveIntents() {
        var cf = caseFile("ATTACK",
            List.of(stalker("s-0", new Point2d(10, 10), 80, 80)),
            List.of(enemy(new Point2d(30, 30))),
            new Point2d(30, 30));
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .allMatch(i -> i instanceof MoveIntent);
        assertThat(((MoveIntent) intentQueue.pending().get(0)).targetLocation())
            .isEqualTo(new Point2d(30, 30));
    }

    @Test
    void attackLowHealthUnitsRetreatsToNexus() {
        Point2d nexusPos = new Point2d(8, 8);
        var cf = caseFile("ATTACK",
            List.of(stalker("s-0", new Point2d(10, 10), 20, 100)),
            List.of(enemy(new Point2d(30, 30))),
            new Point2d(30, 30));
        cf.put(QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus(nexusPos)));
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .allMatch(i -> i instanceof MoveIntent);
        assertThat(((MoveIntent) intentQueue.pending().get(0)).targetLocation())
            .isEqualTo(nexusPos);
    }

    @Test
    void attackMixedArmyEmitsCorrectIntentPerGroup() {
        // s-low: low health → retreat (MoveIntent)
        // s-near: healthy + in range (distance ~4 ≤ 6) → attack (AttackIntent)
        // s-far: healthy + out of range → move-to-engage (MoveIntent)
        var cf = caseFile("ATTACK",
            List.of(stalker("s-low",  new Point2d(10, 10), 20, 100),
                    stalker("s-near", new Point2d(10, 10), 80, 80),
                    stalker("s-far",  new Point2d(50, 50), 80, 80)),
            List.of(enemy(new Point2d(14, 10))),
            new Point2d(14, 10));
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending()).hasSize(3);
        assertThat(intentQueue.pending().stream()
            .filter(i -> i instanceof MoveIntent mi && mi.unitTag().equals("s-low")))
            .hasSize(1);
        assertThat(intentQueue.pending().stream()
            .filter(i -> i instanceof AttackIntent ai && ai.unitTag().equals("s-near")))
            .hasSize(1);
        assertThat(intentQueue.pending().stream()
            .filter(i -> i instanceof MoveIntent mi && mi.unitTag().equals("s-far")))
            .hasSize(1);
    }

    @Test
    void defendAllUnitsMovesToNexus() {
        Point2d nexusPos = new Point2d(8, 8);
        var cf = caseFile("DEFEND",
            List.of(stalker("s-0", new Point2d(10, 10), 80, 80),
                    stalker("s-1", new Point2d(20, 20), 80, 80)),
            List.of(enemy(new Point2d(12, 12))),
            new Point2d(12, 12));
        cf.put(QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus(nexusPos)));
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending())
            .hasSize(2)
            .allMatch(i -> i instanceof MoveIntent mi && mi.targetLocation().equals(nexusPos));
    }

    @Test
    void macroProducesNoIntents() {
        var cf = caseFile("MACRO",
            List.of(stalker("s-0", new Point2d(10, 10), 80, 80)),
            List.of(enemy(new Point2d(12, 12))),
            null);
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noArmyProducesNoIntents() {
        var cf = caseFile("ATTACK", List.of(), List.of(enemy(new Point2d(12, 12))), new Point2d(12, 12));
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noEnemiesProducesNoIntents() {
        var cf = caseFile("ATTACK",
            List.of(stalker("s-0", new Point2d(10, 10), 80, 80)),
            List.of(), null);
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void focusFire_allInRangeUnitsTargetWeakestEnemy() {
        // Arrange: two Stalkers in range (distance ~4 ≤ 5.0), two enemies at different HP
        Unit weakEnemy   = new Unit("e-weak",   UnitType.ZEALOT, new Point2d(14, 10), 10, 100,  0, 50, 0);
        Unit strongEnemy = new Unit("e-strong", UnitType.ZEALOT, new Point2d(14, 11), 100, 100, 50, 50, 0);
        var cf = caseFile("ATTACK",
            List.of(stalker("s-0", new Point2d(10, 10), 80, 80, 0),
                    stalker("s-1", new Point2d(10, 10), 80, 80, 0)),
            List.of(weakEnemy, strongEnemy),
            weakEnemy.position());
        // Act
        tacticsTask.execute(cf);
        // Assert: both Stalkers attack the weak enemy's position (focus-fire)
        assertThat(intentQueue.pending()).hasSize(2)
            .allMatch(i -> i instanceof AttackIntent ai &&
                           ai.targetLocation().equals(weakEnemy.position()));
    }

    @Test
    void kitingUnit_onCooldown_getsMoveIntentAwayFromEnemy() {
        Point2d stalkerPos = new Point2d(10, 10);
        Point2d enemyPos   = new Point2d(10, 14); // enemy is 4 tiles away (within Stalker range 5.0)
        var cf = caseFile("ATTACK",
            List.of(stalker("s-kite", stalkerPos, 80, 80, 3)), // cooldown = 3
            List.of(new Unit("e-0", UnitType.ZEALOT, enemyPos, 100, 100, 50, 50, 0)),
            enemyPos);
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending()).hasSize(1);
        assertThat(intentQueue.pending().get(0)).isInstanceOf(MoveIntent.class);
        MoveIntent move = (MoveIntent) intentQueue.pending().get(0);
        // Retreat target must be farther from enemy than current position
        assertThat(distance(move.targetLocation(), enemyPos))
            .isGreaterThan(distance(stalkerPos, enemyPos));
    }

    @Test
    void mixedCooldown_correctIntentPerUnit() {
        // s-ready: off cooldown → in-range → AttackIntent
        // s-kiting: on cooldown → kiting → MoveIntent backward
        Point2d enemyPos = new Point2d(14, 10); // 4 tiles from (10,10), within Stalker range
        var cf = caseFile("ATTACK",
            List.of(stalker("s-ready",  new Point2d(10, 10), 80, 80, 0),
                    stalker("s-kiting", new Point2d(10, 10), 80, 80, 3)),
            List.of(new Unit("e-0", UnitType.ZEALOT, enemyPos, 100, 100, 50, 50, 0)),
            enemyPos);
        tacticsTask.execute(cf);
        assertThat(intentQueue.pending()).hasSize(2);
        assertThat(intentQueue.pending())
            .anyMatch(i -> i instanceof AttackIntent ai && ai.unitTag().equals("s-ready"));
        assertThat(intentQueue.pending())
            .anyMatch(i -> i instanceof MoveIntent mi && mi.unitTag().equals("s-kiting"));
    }

    @Test
    void happyPath_attackCycle_focusFiresWeakestEnemy() {
        // Three Stalkers vs two enemies; all in range, all off cooldown
        // All three should target the weakest enemy
        Unit weak   = new Unit("e-weak",   UnitType.ZEALOT, new Point2d(14, 10),  5, 100,  0, 50, 0);
        Unit strong = new Unit("e-strong", UnitType.ZEALOT, new Point2d(14, 11), 100, 100, 50, 50, 0);
        var cf = caseFile("ATTACK",
            List.of(stalker("s-0", new Point2d(10, 10), 80, 80, 0),
                    stalker("s-1", new Point2d(10, 10), 80, 80, 0),
                    stalker("s-2", new Point2d(10, 10), 80, 80, 0)),
            List.of(weak, strong),
            weak.position());
        tacticsTask.execute(cf);
        long focusCount = intentQueue.pending().stream()
            .filter(i -> i instanceof AttackIntent ai &&
                         ai.targetLocation().equals(weak.position()))
            .count();
        assertThat(focusCount).isEqualTo(3);
    }

    // ---- Helpers ----

    private CaseFile caseFile(String strategy, List<Unit> army,
                                     List<Unit> enemies, Point2d nearestThreat) {
        var cf = new InMemoryCaseFileRepository().create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.STRATEGY,      strategy);
        cf.put(QuarkMindCaseFile.ARMY,          army);
        cf.put(QuarkMindCaseFile.ENEMY_UNITS,   enemies);
        cf.put(QuarkMindCaseFile.MY_BUILDINGS,  List.of());
        cf.put(QuarkMindCaseFile.READY,         Boolean.TRUE);
        if (nearestThreat != null) cf.put(QuarkMindCaseFile.NEAREST_THREAT, nearestThreat);
        return cf;
    }

    private Unit stalker(String tag, Point2d pos, int health, int maxHealth) {
        return new Unit(tag, UnitType.STALKER, pos, health, maxHealth, 80, 80, 0);
    }

    private Unit stalker(String tag, Point2d pos, int health, int maxHealth, int cooldown) {
        return new Unit(tag, UnitType.STALKER, pos, health, maxHealth, 80, 80, cooldown);
    }

    private double distance(Point2d a, Point2d b) {
        return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2));
    }

    private Unit enemy(Point2d pos) {
        return new Unit("e-0", UnitType.ZEALOT, pos, 100, 100, 50, 50, 0);
    }

    private Building nexus(Point2d pos) {
        return new Building("n-0", BuildingType.NEXUS, pos, 1500, 1500, true);
    }
}
