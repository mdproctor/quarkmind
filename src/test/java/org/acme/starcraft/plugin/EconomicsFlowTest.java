package org.acme.starcraft.plugin;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.inject.Inject;
import org.acme.starcraft.agent.ResourceBudget;
import org.acme.starcraft.domain.*;
import org.acme.starcraft.plugin.flow.EconomicsFlow;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.BuildIntent;
import org.acme.starcraft.sc2.intent.TrainIntent;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the full Flow economics pipeline.
 *
 * <p>Requires @QuarkusTest — Quarkus Flow needs CDI context to initialise
 * the workflow engine. Emits GameStateTick events directly to the
 * economics-ticks channel and asserts IntentQueue contents after async processing.
 *
 * <p>Drains IntentQueue in @BeforeEach / @AfterEach to prevent state bleed
 * (same pattern as DroolsStrategyTaskTest).
 */
@QuarkusTest
class EconomicsFlowTest {

    @Inject EconomicsFlow flow;
    @Inject IntentQueue intentQueue;

    @Inject
    @Channel("economics-ticks")
    MutinyEmitter<GameStateTick> emitter;

    @BeforeEach
    @AfterEach
    void drainQueue() {
        intentQueue.drainAll();
    }

    @Test
    void flowQueuesPylonWhenSupplyLow() throws Exception {
        // supply=13/15 → headroom=2, below threshold of 4
        GameStateTick tick = tick(300, 13, 15, workers(6), List.of(nexus()), List.of());
        emitter.sendAndAwait(tick);
        Thread.sleep(300);

        assertThat(intentQueue.pending())
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.PYLON);
    }

    @Test
    void flowQueuesProbeWhenUnderCap() throws Exception {
        // supply headroom=9, workers=6 < 22
        GameStateTick tick = tick(200, 6, 15, workers(6), List.of(nexus()), List.of());
        emitter.sendAndAwait(tick);
        Thread.sleep(300);

        assertThat(intentQueue.pending())
            .anyMatch(i -> i instanceof TrainIntent ti && ti.unitType() == UnitType.PROBE);
    }

    @Test
    void flowBuildsAssimilatorWhenGatewayExists() throws Exception {
        Resource geyser = new Resource("g-0", new Point2d(20, 20), 2250);
        // gasReady=true because there's a gateway in buildings
        GameStateTick tick = tickWithGas(200, 6, 15, workers(6),
            List.of(nexus(), completeGateway()), List.of(geyser));
        emitter.sendAndAwait(tick);
        Thread.sleep(300);

        assertThat(intentQueue.pending())
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.ASSIMILATOR);
    }

    @Test
    void flowQueuesExpansionWhenSaturated() throws Exception {
        // 22 workers, 1 nexus, 500 minerals
        GameStateTick tick = tick(500, 22, 50, workers(22), List.of(nexus()), List.of());
        emitter.sendAndAwait(tick);
        Thread.sleep(300);

        assertThat(intentQueue.pending())
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.NEXUS);
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

    /** tick with gasReady=true */
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

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }

    private Building completeGateway() {
        return new Building("gw-0", BuildingType.GATEWAY, new Point2d(17, 18), 550, 550, true);
    }
}
