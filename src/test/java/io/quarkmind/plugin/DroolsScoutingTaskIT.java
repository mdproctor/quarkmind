package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.ScoutingTask;
import io.quarkmind.domain.*;
import io.quarkmind.plugin.scouting.DroolsScoutingTask;
import io.quarkmind.plugin.scouting.ScoutingSessionManager;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.MoveIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for DroolsScoutingTask.
 */
@QuarkusTest
class DroolsScoutingTaskIT {

    @Inject @CaseType("starcraft-game") ScoutingTask scoutingTask;
    @Inject IntentQueue intentQueue;
    @Inject ScoutingSessionManager sessionManager;

    @BeforeEach @AfterEach
    void reset() {
        intentQueue.drainAll();
        sessionManager.reset(); // clear persistent buffers between tests
    }

    // ---- Passive intel ----

    @Test
    void writesArmySizeEachTick() {
        var cf = caseFile(List.of(enemy(10, 10), enemy(20, 20)), List.of(), 100L);
        scoutingTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class)).contains(2);
    }

    @Test
    void writesNearestThreat() {
        var cf = caseFile(List.of(enemy(10, 10), enemy(100, 100)), List.of(), 100L);
        scoutingTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class))
            .contains(new Point2d(10, 10));
    }

    // ---- CEP keys written each tick ----

    @Test
    void timingAttackFalseWhenNoArmyNearBase() {
        var cf = caseFile(List.of(enemy(200, 200)), List.of(), 100L);
        scoutingTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.TIMING_ATTACK_INCOMING, Boolean.class))
            .contains(Boolean.FALSE);
    }

    @Test
    void postureUnknownWhenNoEnemiesEverSeen() {
        var cf = caseFile(List.of(), List.of(), 100L);
        scoutingTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.ENEMY_POSTURE, String.class))
            .contains("UNKNOWN");
    }

    @Test
    void buildOrderUnknownWhenNoEnemiesEverSeen() {
        var cf = caseFile(List.of(), List.of(), 100L);
        scoutingTask.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.ENEMY_BUILD_ORDER, String.class))
            .contains("UNKNOWN");
    }

    @Test
    void buildOrderDetectedAfterEnoughSightings() {
        // Execute 6 ticks with unique ROACH tags — accumulates in buffer
        for (int i = 0; i < 6; i++) {
            var cf = caseFile(
                List.of(new Unit("r-" + i, UnitType.ROACH, new Point2d(200, 200), 100, 100, 0, 0)),
                List.of(),
                (long)(i + 1) * 500);
            scoutingTask.execute(cf);
        }
        var finalCf = caseFile(List.of(), List.of(), 6 * 500L);
        scoutingTask.execute(finalCf);
        assertThat(finalCf.get(QuarkMindCaseFile.ENEMY_BUILD_ORDER, String.class))
            .contains("ZERG_ROACH_RUSH");
    }

    @Test
    void scoutProbeDispatchedAfterDelay() {
        var cf = caseFile(List.of(), List.of(probe("p-0")),
            (long) DroolsScoutingTask.SCOUT_DELAY_TICKS);
        scoutingTask.execute(cf);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .first().isInstanceOf(MoveIntent.class);
    }

    @Test
    void scoutDispatchedToEstimatedSC2Base() {
        // Regression guard: default map width (256) must still target the SC2 far corner.
        // nexus at (8,8) → estimated enemy base = (224,224).
        // Uses a distinct probe tag to avoid scoutProbeTag state from other tests.
        var cf = caseFile(List.of(), List.of(probe("sc-guard-probe")),
            (long) DroolsScoutingTask.SCOUT_DELAY_TICKS);
        scoutingTask.execute(cf);
        assertThat(intentQueue.pending()).hasSize(1);
        MoveIntent move = (MoveIntent) intentQueue.pending().get(0);
        assertThat(move.targetLocation()).isEqualTo(new Point2d(224, 224));
    }

    // ---- Helpers ----

    private CaseFile caseFile(List<Unit> enemies, List<Unit> workers, long frame) {
        var cf = new InMemoryCaseFileRepository().create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.ENEMY_UNITS,  enemies);
        cf.put(QuarkMindCaseFile.WORKERS,      workers);
        cf.put(QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus()));
        cf.put(QuarkMindCaseFile.GAME_FRAME,   frame);
        cf.put(QuarkMindCaseFile.READY,        Boolean.TRUE);
        return cf;
    }

    private Unit enemy(float x, float y) {
        return new Unit("e-" + System.nanoTime(), UnitType.ZEALOT, new Point2d(x, y), 100, 100, 50, 50);
    }

    private Unit probe(String tag) {
        return new Unit(tag, UnitType.PROBE, new Point2d(9, 9), 45, 45, 20, 20);
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }
}
