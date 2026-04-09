package io.quarkmind.plugin;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BasicTacticsTaskTest {

    IntentQueue intentQueue;
    BasicTacticsTask task;

    @BeforeEach
    void setUp() {
        intentQueue = new IntentQueue();
        task = new BasicTacticsTask(intentQueue);
    }

    // --- ATTACK ---

    @Test
    void attackQueuesAttackIntentForEachArmyUnit() {
        var cf = caseFile("ATTACK", List.of(stalker("s-0"), stalker("s-1")), List.of(nexus()), null);
        task.execute(cf);
        assertThat(intentQueue.pending())
            .hasSize(2)
            .allMatch(i -> i instanceof AttackIntent);
    }

    @Test
    void attackTargetsNearestThreatWhenAvailable() {
        Point2d threat = new Point2d(50, 50);
        var cf = caseFile("ATTACK", List.of(stalker("s-0")), List.of(nexus()), threat);
        task.execute(cf);
        assertThat(((AttackIntent) intentQueue.pending().get(0)).targetLocation()).isEqualTo(threat);
    }

    @Test
    void attackTargetsMapCenterWhenNoThreatKnown() {
        var cf = caseFile("ATTACK", List.of(stalker("s-0")), List.of(nexus()), null);
        task.execute(cf);
        assertThat(((AttackIntent) intentQueue.pending().get(0)).targetLocation())
            .isEqualTo(BasicTacticsTask.MAP_CENTER);
    }

    // --- DEFEND ---

    @Test
    void defendQueuesMoveIntentForEachArmyUnit() {
        var cf = caseFile("DEFEND", List.of(stalker("s-0"), stalker("s-1")), List.of(nexus()), null);
        task.execute(cf);
        assertThat(intentQueue.pending())
            .hasSize(2)
            .allMatch(i -> i instanceof MoveIntent);
    }

    @Test
    void defendRalliesToNexusPosition() {
        Point2d nexusPos = new Point2d(8, 8);
        var cf = caseFile("DEFEND", List.of(stalker("s-0")), List.of(nexus()), null);
        task.execute(cf);
        assertThat(((MoveIntent) intentQueue.pending().get(0)).targetLocation()).isEqualTo(nexusPos);
    }

    @Test
    void defendFallsBackToMapCenterWhenNoNexus() {
        var cf = caseFile("DEFEND", List.of(stalker("s-0")), List.of(), null);
        task.execute(cf);
        assertThat(((MoveIntent) intentQueue.pending().get(0)).targetLocation())
            .isEqualTo(BasicTacticsTask.MAP_CENTER);
    }

    // --- MACRO ---

    @Test
    void macroProducesNoIntents() {
        var cf = caseFile("MACRO", List.of(stalker("s-0")), List.of(nexus()), null);
        task.execute(cf);
        assertThat(intentQueue.pending()).isEmpty();
    }

    // --- Edge cases ---

    @Test
    void emptyArmyProducesNoIntents() {
        var cf = caseFile("ATTACK", List.of(), List.of(nexus()), null);
        task.execute(cf);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noStrategyDefaultsToMacro() {
        var cf = new InMemoryCaseFileRepository().create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.ARMY,         List.of(stalker("s-0")));
        cf.put(QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus()));
        cf.put(QuarkMindCaseFile.READY,        Boolean.TRUE);
        task.execute(cf);
        assertThat(intentQueue.pending()).isEmpty();
    }

    // --- Helpers ---

    private CaseFile caseFile(String strategy, List<Unit> army,
                                     List<Building> buildings, Point2d nearestThreat) {
        var cf = new InMemoryCaseFileRepository().create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.STRATEGY,     strategy);
        cf.put(QuarkMindCaseFile.ARMY,         army);
        cf.put(QuarkMindCaseFile.MY_BUILDINGS, buildings);
        cf.put(QuarkMindCaseFile.READY,        Boolean.TRUE);
        if (nearestThreat != null) cf.put(QuarkMindCaseFile.NEAREST_THREAT, nearestThreat);
        return cf;
    }

    private Unit stalker(String tag) {
        return new Unit(tag, UnitType.STALKER, new Point2d(10, 10), 80, 80);
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }
}
