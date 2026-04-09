package io.quarkmind.plugin;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.inject.Inject;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.domain.*;
import io.quarkmind.plugin.flow.EconomicsFlow;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
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

    /**
     * Regression test for #15 — budget arbitration bug.
     *
     * Budget of 110 minerals: enough for a Pylon (100) but not Pylon + Probe (150).
     * Supply is low (headroom = 2) → Pylon decision triggers.
     * Workers are under cap (6 < 22) → Probe decision triggers.
     *
     * With the bug (each step sees original budget):
     *   checkSupply sees 110 ≥ 100 → queues Pylon, "spends" 100
     *   checkProbes sees 110 ≥ 50  → queues Probe, "spends" 50
     *   Result: 2 intents, 150 minerals "spent" — overcommit by 40.
     *
     * With the fix (sequential spend within one step):
     *   checkSupply sees 110 ≥ 100 → queues Pylon, spends 100 → budget now 10
     *   checkProbes sees 10 < 50   → skipped
     *   Result: 1 intent, 100 minerals spent — correct.
     */
    @Test
    void budgetNotOvercommittedWhenMultipleTriggersFire() throws Exception {
        // 110 minerals: supply low (headroom=2 triggers pylon@100), workers under cap (triggers probe@50)
        // After pylon spend, only 10 left — probe must NOT fire
        GameStateTick tick = tick(110, 13, 15, workers(6), List.of(nexus()), List.of());
        emitter.sendAndAwait(tick);
        Thread.sleep(300);

        List<io.quarkmind.sc2.intent.Intent> intents = intentQueue.pending();
        assertThat(intents)
            .as("Only the Pylon should be queued — Probe must not fire after budget is spent")
            .hasSize(1);
        assertThat(intents.get(0))
            .as("The one intent must be a Pylon")
            .isInstanceOf(BuildIntent.class)
            .satisfies(i -> assertThat(((BuildIntent) i).buildingType()).isEqualTo(BuildingType.PYLON));
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
            .mapToObj(i -> new Unit("probe-" + i, UnitType.PROBE, new Point2d(9, 9), 45, 45, 20, 20))
            .toList();
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }

    private Building completeGateway() {
        return new Building("gw-0", BuildingType.GATEWAY, new Point2d(17, 18), 550, 550, true);
    }
}
