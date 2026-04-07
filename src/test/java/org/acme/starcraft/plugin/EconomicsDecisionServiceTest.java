package org.acme.starcraft.plugin;

import org.acme.starcraft.agent.ResourceBudget;
import org.acme.starcraft.domain.*;
import org.acme.starcraft.plugin.flow.EconomicsDecisionService;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.BuildIntent;
import org.acme.starcraft.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EconomicsDecisionServiceTest {

    IntentQueue intentQueue;
    EconomicsDecisionService svc;

    @BeforeEach
    void setUp() {
        intentQueue = new IntentQueue();
        svc = new EconomicsDecisionService(intentQueue);
        intentQueue.drainAll();
    }

    // ─── checkSupply ────────────────────────────────────────────────────────

    @Test
    void buildsPylonWhenSupplyHeadroomLow() {
        // supplyUsed=13, supplyCap=15 → headroom=2, below threshold of 4
        var tick = tick(300, 13, 15, workers(6), buildings(nexus()), List.of());
        svc.checkSupply(tick);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .first().isInstanceOf(BuildIntent.class);
        assertThat(((BuildIntent) intentQueue.pending().get(0)).buildingType())
            .isEqualTo(BuildingType.PYLON);
    }

    @Test
    void noPylonWhenSupplyHeadroomSufficient() {
        var tick = tick(300, 8, 15, workers(6), buildings(nexus()), List.of());
        svc.checkSupply(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noPylonAtMaxSupply() {
        var tick = tick(500, 196, 200, workers(6), buildings(nexus()), List.of());
        svc.checkSupply(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noPylonWithoutMinerals() {
        var tick = tick(50, 13, 15, workers(6), buildings(nexus()), List.of());
        svc.checkSupply(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    // ─── checkProbes ─────────────────────────────────────────────────────────

    @Test
    void trainsProbeWhenUnderCap() {
        var tick = tick(200, 6, 15, workers(6), buildings(nexus()), List.of());
        svc.checkProbes(tick);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .first().isInstanceOf(TrainIntent.class);
        assertThat(((TrainIntent) intentQueue.pending().get(0)).unitType())
            .isEqualTo(UnitType.PROBE);
    }

    @Test
    void noProbeWhenAtCap() {
        var tick = tick(500, 22, 50, workers(22), buildings(nexus()), List.of());
        svc.checkProbes(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noProbeWithoutNexus() {
        var tick = tick(200, 6, 15, workers(6), List.of(), List.of());
        svc.checkProbes(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noProbeWithoutMinerals() {
        var tick = tick(40, 6, 15, workers(6), buildings(nexus()), List.of());
        svc.checkProbes(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noProbeFromIncompleteNexus() {
        Building incomplete = new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, false);
        var tick = tick(200, 6, 15, workers(6), List.of(incomplete), List.of());
        svc.checkProbes(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    // ─── checkGas ────────────────────────────────────────────────────────────

    @Test
    void buildsAssimilatorWhenGatewayExistsAndNoneBuilt() {
        Resource geyser = new Resource("g-0", new Point2d(20, 20), 2250);
        // gasReady=true because completeGateway() is in buildings
        var tick = tickWithGas(200, 6, 15, workers(6),
            buildings(nexus(), completeGateway()), List.of(geyser));
        svc.checkGas(tick);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .first().isInstanceOf(BuildIntent.class);
        assertThat(((BuildIntent) intentQueue.pending().get(0)).buildingType())
            .isEqualTo(BuildingType.ASSIMILATOR);
    }

    @Test
    void noAssimilatorWithoutGateway() {
        Resource geyser = new Resource("g-0", new Point2d(20, 20), 2250);
        // gasReady=false — tick() helper sets it to false
        var tick = tick(200, 6, 15, workers(6), buildings(nexus()), List.of(geyser));
        svc.checkGas(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noAssimilatorWhenBothAlreadyBuilt() {
        Resource g1 = new Resource("g-0", new Point2d(20, 20), 2250);
        Resource g2 = new Resource("g-1", new Point2d(22, 20), 2250);
        Building a1 = new Building("a-0", BuildingType.ASSIMILATOR, new Point2d(20, 20), 450, 450, true);
        Building a2 = new Building("a-1", BuildingType.ASSIMILATOR, new Point2d(22, 20), 450, 450, true);
        var tick = tickWithGas(500, 6, 15, workers(6),
            buildings(nexus(), completeGateway(), a1, a2), List.of(g1, g2));
        svc.checkGas(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noAssimilatorWithoutGeysers() {
        var tick = tickWithGas(200, 6, 15, workers(6), buildings(nexus(), completeGateway()), List.of());
        svc.checkGas(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    // ─── checkExpansion ──────────────────────────────────────────────────────

    @Test
    void queuesNexusWhenSaturatedAndAffordable() {
        var tick = tick(500, 22, 50, workers(22), buildings(nexus()), List.of());
        svc.checkExpansion(tick);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .first().isInstanceOf(BuildIntent.class);
        assertThat(((BuildIntent) intentQueue.pending().get(0)).buildingType())
            .isEqualTo(BuildingType.NEXUS);
    }

    @Test
    void noExpansionWhenWorkersUnderCap() {
        var tick = tick(500, 15, 50, workers(15), buildings(nexus()), List.of());
        svc.checkExpansion(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noExpansionWhenSecondNexusAlreadyExists() {
        Building nexus2 = new Building("n-1", BuildingType.NEXUS, new Point2d(32, 32), 1500, 1500, false);
        var tick = tick(500, 22, 50, workers(22), buildings(nexus(), nexus2), List.of());
        svc.checkExpansion(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noExpansionWithoutMinerals() {
        var tick = tick(350, 22, 50, workers(22), buildings(nexus()), List.of());
        svc.checkExpansion(tick);
        assertThat(intentQueue.pending()).isEmpty();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** tick with gasReady=false */
    private GameStateTick tick(int minerals, int supplyUsed, int supplyCap,
                               List<Unit> workers, List<Building> buildings,
                               List<Resource> geysers) {
        return new GameStateTick(minerals, 0, supplyUsed, supplyCap,
            workers, buildings, geysers,
            new ResourceBudget(minerals, 0), "MACRO", false);
    }

    /** tick with gasReady=true (gateway exists) */
    private GameStateTick tickWithGas(int minerals, int supplyUsed, int supplyCap,
                                      List<Unit> workers, List<Building> buildings,
                                      List<Resource> geysers) {
        return new GameStateTick(minerals, 0, supplyUsed, supplyCap,
            workers, buildings, geysers,
            new ResourceBudget(minerals, 0), "MACRO", true);
    }

    private List<Unit> workers(int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> new Unit("probe-" + i, UnitType.PROBE, new Point2d(9, 9), 45, 45))
            .toList();
    }

    private List<Building> buildings(Building... bs) { return List.of(bs); }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }

    private Building completeGateway() {
        return new Building("gw-0", BuildingType.GATEWAY, new Point2d(17, 18), 550, 550, true);
    }
}
