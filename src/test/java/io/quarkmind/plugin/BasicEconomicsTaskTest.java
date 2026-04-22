package io.quarkmind.plugin;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BasicEconomicsTaskTest {

    IntentQueue intentQueue;
    BasicEconomicsTask task;

    @BeforeEach
    void setUp() {
        intentQueue = new IntentQueue();
        task = new BasicEconomicsTask(intentQueue);
    }

    // --- Probe training ---

    @Test
    void trainsProbeWhenUnderCapAndMineralsAvailable() {
        // supply 6/15 → headroom 9, well above threshold — only probe rule fires
        var cf = caseFile(200, 6, 15, workers(6), buildings(nexus("n-0")));
        task.execute(cf);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .first().isInstanceOf(TrainIntent.class);
        assertThat(((TrainIntent) intentQueue.pending().get(0)).unitType()).isEqualTo(UnitType.PROBE);
    }

    @Test
    void doesNotTrainProbeWhenAtCap() {
        var cf = caseFile(500, BasicEconomicsTask.PROBE_CAP, 50, workers(BasicEconomicsTask.PROBE_CAP), buildings(nexus("n-0")));
        task.execute(cf);
        assertThat(intentQueue.pending().stream().noneMatch(i -> i instanceof TrainIntent)).isTrue();
    }

    @Test
    void doesNotTrainProbeWithoutMinerals() {
        var cf = caseFile(40, 12, 15, workers(12), buildings(nexus("n-0")));
        task.execute(cf);
        assertThat(intentQueue.pending().stream().noneMatch(i -> i instanceof TrainIntent)).isTrue();
    }

    @Test
    void doesNotTrainProbeWithoutNexus() {
        // supply 6/15 → headroom 9, no pylon needed; no buildings, so no nexus to train from
        var cf = caseFile(200, 6, 15, workers(6), List.of());
        task.execute(cf);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void doesNotTrainProbeFromIncompleteNexus() {
        Building incompleteNexus = new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, false);
        var cf = caseFile(200, 12, 15, workers(12), List.of(incompleteNexus));
        task.execute(cf);
        assertThat(intentQueue.pending().stream().noneMatch(i -> i instanceof TrainIntent)).isTrue();
    }

    // --- Pylon building ---

    @Test
    void buildsPylonWhenSupplyHeadroomLow() {
        // supply 13/15 → headroom = 2, below threshold of 4
        var cf = caseFile(200, 13, 15, workers(12), buildings(nexus("n-0")));
        task.execute(cf);
        assertThat(intentQueue.pending().stream().anyMatch(i -> i instanceof BuildIntent bi
            && bi.buildingType() == BuildingType.PYLON)).isTrue();
    }

    @Test
    void doesNotBuildPylonWhenHeadroomSufficient() {
        // supply 8/15 → headroom = 7, above threshold
        var cf = caseFile(200, 8, 15, workers(8), buildings(nexus("n-0")));
        task.execute(cf);
        assertThat(intentQueue.pending().stream().noneMatch(i -> i instanceof BuildIntent)).isTrue();
    }

    @Test
    void doesNotBuildPylonWithoutMinerals() {
        var cf = caseFile(50, 13, 15, workers(12), buildings(nexus("n-0")));
        task.execute(cf);
        assertThat(intentQueue.pending().stream().noneMatch(i -> i instanceof BuildIntent)).isTrue();
    }

    @Test
    void doesNotBuildPylonAtMaxSupply() {
        var cf = caseFile(500, 196, 200, workers(12), buildings(nexus("n-0")));
        task.execute(cf);
        assertThat(intentQueue.pending().stream().noneMatch(i -> i instanceof BuildIntent)).isTrue();
    }

    // --- Priority: Pylon before Probe ---

    @Test
    void bothQueuedWhenSupplyLowAndWorkersUnderCap() {
        // supply almost capped AND workers under cap → both intents
        var cf = caseFile(300, 13, 15, workers(12), buildings(nexus("n-0")));
        task.execute(cf);
        assertThat(intentQueue.pending()).hasSize(2);
        assertThat(intentQueue.pending().get(0)).isInstanceOf(BuildIntent.class); // Pylon first
        assertThat(intentQueue.pending().get(1)).isInstanceOf(TrainIntent.class); // Probe second
    }

    // --- Pylon position ---

    @Test
    void pylonPositionsSpreadAcrossGrid() {
        assertThat(BasicEconomicsTask.pylonPosition(0)).isEqualTo(new Point2d(15, 15));
        assertThat(BasicEconomicsTask.pylonPosition(1)).isEqualTo(new Point2d(18, 15));
        assertThat(BasicEconomicsTask.pylonPosition(4)).isEqualTo(new Point2d(15, 18));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 15, 16, 17, 100, 1000, 10000, Integer.MAX_VALUE / 2})
    void pylonPositionIsAlwaysWithinMapBounds(int buildingCount) {
        Point2d p = BasicEconomicsTask.pylonPosition(buildingCount);
        assertThat(p.x()).as("pylon x for buildingCount=%d", buildingCount).isBetween(0f, 63f);
        assertThat(p.y()).as("pylon y for buildingCount=%d", buildingCount).isBetween(0f, 63f);
    }

    @Test
    void doesNotQueueSecondPylonWhileOneIsUnderConstruction() {
        Building pendingPylon = new Building("pylon-0", BuildingType.PYLON,
            new Point2d(15, 15), 100, 100, false); // isComplete=false → still building
        var cf = caseFile(200, 13, 15, workers(12),
            buildings(nexus("n-0"), pendingPylon));
        task.execute(cf);
        long pylonIntents = intentQueue.pending().stream()
            .filter(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.PYLON)
            .count();
        assertThat(pylonIntents).as("must not queue another Pylon while one is building").isZero();
    }

    // --- Helpers ---

    private CaseFile caseFile(int minerals, int supplyUsed, int supplyCap,
                                     List<Unit> workers, List<Building> buildings) {
        var cf = new InMemoryCaseFileRepository().create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.MINERALS,       minerals);
        cf.put(QuarkMindCaseFile.SUPPLY_USED,    supplyUsed);
        cf.put(QuarkMindCaseFile.SUPPLY_CAP,     supplyCap);
        cf.put(QuarkMindCaseFile.WORKERS,        workers);
        cf.put(QuarkMindCaseFile.MY_BUILDINGS,   buildings);
        cf.put(QuarkMindCaseFile.RESOURCE_BUDGET, new ResourceBudget(minerals, 0));
        cf.put(QuarkMindCaseFile.READY,          Boolean.TRUE);
        return cf;
    }

    private List<Unit> workers(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> new Unit("probe-" + i, UnitType.PROBE, new Point2d(9, 9), 45, 45, 20, 20, 0, 0))
            .toList();
    }

    private List<Building> buildings(Building... bs) {
        return List.of(bs);
    }

    private Building nexus(String tag) {
        return new Building(tag, BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }
}
