package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.DefaultCaseFile;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.ScoutingTask;
import org.acme.starcraft.domain.*;
import org.acme.starcraft.plugin.scouting.DroolsScoutingTask;
import org.acme.starcraft.plugin.scouting.ScoutingSessionManager;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.MoveIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertThat(cf.get(StarCraftCaseFile.ENEMY_ARMY_SIZE, Integer.class)).contains(2);
    }

    @Test
    void writesNearestThreat() {
        var cf = caseFile(List.of(enemy(10, 10), enemy(100, 100)), List.of(), 100L);
        scoutingTask.execute(cf);
        assertThat(cf.get(StarCraftCaseFile.NEAREST_THREAT, Point2d.class))
            .contains(new Point2d(10, 10));
    }

    // ---- CEP keys written each tick ----

    @Test
    void timingAttackFalseWhenNoArmyNearBase() {
        var cf = caseFile(List.of(enemy(200, 200)), List.of(), 100L);
        scoutingTask.execute(cf);
        assertThat(cf.get(StarCraftCaseFile.TIMING_ATTACK_INCOMING, Boolean.class))
            .contains(Boolean.FALSE);
    }

    @Test
    void postureUnknownWhenNoEnemiesEverSeen() {
        var cf = caseFile(List.of(), List.of(), 100L);
        scoutingTask.execute(cf);
        assertThat(cf.get(StarCraftCaseFile.ENEMY_POSTURE, String.class))
            .contains("UNKNOWN");
    }

    @Test
    void buildOrderUnknownWhenNoEnemiesEverSeen() {
        var cf = caseFile(List.of(), List.of(), 100L);
        scoutingTask.execute(cf);
        assertThat(cf.get(StarCraftCaseFile.ENEMY_BUILD_ORDER, String.class))
            .contains("UNKNOWN");
    }

    @Test
    void buildOrderDetectedAfterEnoughSightings() {
        // Execute 6 ticks with unique ROACH tags — accumulates in buffer
        for (int i = 0; i < 6; i++) {
            var cf = caseFile(
                List.of(new Unit("r-" + i, UnitType.ROACH, new Point2d(200, 200), 100, 100)),
                List.of(),
                (long)(i + 1) * 500);
            scoutingTask.execute(cf);
        }
        var finalCf = caseFile(List.of(), List.of(), 6 * 500L);
        scoutingTask.execute(finalCf);
        assertThat(finalCf.get(StarCraftCaseFile.ENEMY_BUILD_ORDER, String.class))
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

    // ---- Helpers ----

    private DefaultCaseFile caseFile(List<Unit> enemies, List<Unit> workers, long frame) {
        var cf = new DefaultCaseFile("test-" + System.nanoTime(), "starcraft-game", null, null);
        cf.put(StarCraftCaseFile.ENEMY_UNITS,  enemies);
        cf.put(StarCraftCaseFile.WORKERS,      workers);
        cf.put(StarCraftCaseFile.MY_BUILDINGS, List.of(nexus()));
        cf.put(StarCraftCaseFile.GAME_FRAME,   frame);
        cf.put(StarCraftCaseFile.READY,        Boolean.TRUE);
        return cf;
    }

    private Unit enemy(float x, float y) {
        return new Unit("e-" + System.nanoTime(), UnitType.ZEALOT, new Point2d(x, y), 100, 100);
    }

    private Unit probe(String tag) {
        return new Unit(tag, UnitType.PROBE, new Point2d(9, 9), 45, 45);
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }
}
