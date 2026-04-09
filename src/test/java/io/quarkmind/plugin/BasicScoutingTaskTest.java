package io.quarkmind.plugin;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.DefaultCaseFile;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.MoveIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BasicScoutingTaskTest {

    IntentQueue intentQueue;
    BasicScoutingTask task;

    @BeforeEach
    void setUp() {
        intentQueue = new IntentQueue();
        task = new BasicScoutingTask(intentQueue);
    }

    // --- Passive intel ---

    @Test
    void writesZeroArmySizeWhenNoEnemiesVisible() {
        var cf = caseFile(List.of(), List.of(nexus()), List.of(probe("p-0")), 0L);
        task.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class)).contains(0);
    }

    @Test
    void writesCorrectArmySizeWhenEnemiesPresent() {
        var cf = caseFile(List.of(enemy(10, 10), enemy(20, 20)), List.of(nexus()), List.of(), 0L);
        task.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class)).contains(2);
    }

    @Test
    void writesNearestThreatPositionWhenEnemiesVisible() {
        var cf = caseFile(List.of(enemy(10, 10), enemy(100, 100)), List.of(nexus()), List.of(), 0L);
        task.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class))
            .contains(new Point2d(10, 10));
    }

    @Test
    void doesNotWriteNearestThreatWhenNoEnemies() {
        var cf = caseFile(List.of(), List.of(nexus()), List.of(probe("p-0")), 0L);
        task.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class)).isEmpty();
    }

    @Test
    void usesNexusAsHomeForDistanceCalculation() {
        Building farNexus = new Building("n-0", BuildingType.NEXUS, new Point2d(50, 50), 1500, 1500, true);
        var cf = caseFile(List.of(enemy(51, 51), enemy(0, 0)), List.of(farNexus), List.of(), 0L);
        task.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class))
            .contains(new Point2d(51, 51));
    }

    // --- Active scouting ---

    @Test
    void doesNotSendScoutBeforeDelay() {
        var cf = caseFile(List.of(), List.of(nexus()), List.of(probe("p-0")), 0L);
        task.execute(cf);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void sendsScoutAfterDelayWhenNoEnemiesVisible() {
        var cf = caseFile(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(cf);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .first().isInstanceOf(MoveIntent.class);
    }

    @Test
    void scoutTargetsEstimatedEnemyBase() {
        var cf = caseFile(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(cf);
        MoveIntent move = (MoveIntent) intentQueue.pending().get(0);
        // Nexus at (8,8) → enemy estimated at (224,224)
        assertThat(move.targetLocation()).isEqualTo(new Point2d(224, 224));
    }

    @Test
    void doesNotSendSecondScoutIfFirstStillAlive() {
        var cf = caseFile(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(cf); // assigns scout
        intentQueue.drainAll();

        task.execute(cf); // same probe still alive — no new intent
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void assignsNewScoutIfPreviousDied() {
        var cf = caseFile(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(cf); // assigns p-0 as scout
        intentQueue.drainAll();

        // p-0 is gone — only p-1 remains
        var cf2 = caseFile(List.of(), List.of(nexus()), List.of(probe("p-1")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS + 1);
        task.execute(cf2);
        assertThat(intentQueue.pending()).hasSize(1);
        assertThat(((MoveIntent) intentQueue.pending().get(0)).unitTag()).isEqualTo("p-1");
    }

    @Test
    void releasesScoutWhenEnemiesFound() {
        var cf = caseFile(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(cf); // assigns scout
        intentQueue.drainAll();

        // Enemies appear → scout released
        var cf2 = caseFile(List.of(enemy(20, 20)), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS + 1);
        task.execute(cf2);
        intentQueue.drainAll();

        // No enemies again → should assign a fresh scout
        task.execute(cf);
        assertThat(intentQueue.pending()).hasSize(1);
    }

    // --- estimatedEnemyBase ---

    @Test
    void estimatesLowerLeftEnemyFromUpperRightBase() {
        assertThat(BasicScoutingTask.estimatedEnemyBase(new Point2d(100, 100)))
            .isEqualTo(new Point2d(32, 32));
    }

    @Test
    void estimatesUpperRightEnemyFromLowerLeftBase() {
        assertThat(BasicScoutingTask.estimatedEnemyBase(new Point2d(8, 8)))
            .isEqualTo(new Point2d(224, 224));
    }

    // --- Helpers ---

    private DefaultCaseFile caseFile(List<Unit> enemies, List<Building> buildings,
                                     List<Unit> workers, long frame) {
        var cf = new DefaultCaseFile("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.ENEMY_UNITS,  enemies);
        cf.put(QuarkMindCaseFile.MY_BUILDINGS, buildings);
        cf.put(QuarkMindCaseFile.WORKERS,      workers);
        cf.put(QuarkMindCaseFile.GAME_FRAME,   frame);
        cf.put(QuarkMindCaseFile.READY,        Boolean.TRUE);
        return cf;
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }

    private Unit enemy(float x, float y) {
        return new Unit("e-" + (int) x, UnitType.ZEALOT, new Point2d(x, y), 100, 100);
    }

    private Unit probe(String tag) {
        return new Unit(tag, UnitType.PROBE, new Point2d(9, 9), 45, 45);
    }
}
