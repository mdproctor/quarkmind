package io.quarkmind.plugin.flow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.domain.*;
import io.quarkmind.plugin.BasicEconomicsTask;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * All per-tick economics decisions for the Flow workflow.
 *
 * <p>Called by {@link EconomicsFlow} on each tick event. Methods are side-effecting
 * — they add to {@link IntentQueue} when conditions are met. The budget in
 * {@link GameStateTick} is a per-tick snapshot independent of the CaseHub shared budget.
 *
 * <p>Assimilator ownership transferred here from DroolsStrategyTask (economics decision,
 * not strategy). Pylon position logic reused from {@link BasicEconomicsTask#pylonPosition}.
 */
@ApplicationScoped
public class EconomicsDecisionService {

    static final int PROBE_CAP        = 22;
    static final int SUPPLY_HEADROOM  = 4;
    static final int MAX_SUPPLY       = 200;
    static final int PYLON_COST       = 100;
    static final int PROBE_COST       = 50;
    static final int ASSIMILATOR_COST = 75;
    static final int NEXUS_COST       = 400;

    // Hardcoded natural expansion position — Phase 3+ will use spatial analysis
    static final Point2d NATURAL_EXPANSION_POS = new Point2d(32, 32);

    private static final Logger log = Logger.getLogger(EconomicsDecisionService.class);

    private final IntentQueue intentQueue;

    @Inject
    public EconomicsDecisionService(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    /** Builds a Pylon when supply headroom drops to SUPPLY_HEADROOM or below. */
    public void checkSupply(GameStateTick tick) {
        if (tick.supplyCap() >= MAX_SUPPLY) return;
        if (tick.supplyUsed() < tick.supplyCap() - SUPPLY_HEADROOM) return;
        // Skip if a Pylon is already under construction — prevents runaway queuing
        boolean pylonPending = tick.buildings().stream()
            .anyMatch(b -> b.type() == BuildingType.PYLON && !b.isComplete());
        if (pylonPending) return;
        if (!tick.budget().spendMinerals(PYLON_COST)) return;
        tick.workers().stream().findFirst().ifPresent(probe -> {
            Point2d pos = BasicEconomicsTask.pylonPosition(tick.buildings().size());
            intentQueue.add(new BuildIntent(probe.tag(), BuildingType.PYLON, pos));
            log.debugf("[FLOW-ECONOMICS] Pylon at %s (supply %d/%d)",
                pos, tick.supplyUsed(), tick.supplyCap());
        });
    }

    /** Trains a Probe when worker count is below PROBE_CAP and budget allows. */
    public void checkProbes(GameStateTick tick) {
        if (tick.workers().size() >= PROBE_CAP) return;
        if (!tick.budget().spendMinerals(PROBE_COST)) return;
        tick.buildings().stream()
            .filter(b -> b.type() == BuildingType.NEXUS && b.isComplete())
            .findFirst()
            .ifPresent(nexus -> {
                intentQueue.add(new TrainIntent(nexus.tag(), UnitType.PROBE));
                log.debugf("[FLOW-ECONOMICS] Probe (workers=%d/%d)", tick.workers().size(), PROBE_CAP);
            });
    }

    /**
     * Builds Assimilators (up to 2) once a Gateway exists.
     * Uses tick.gasReady() — set by FlowEconomicsTask when a Gateway is in buildings.
     * Ownership transferred from DroolsStrategyTask.
     */
    public void checkGas(GameStateTick tick) {
        if (!tick.gasReady()) return;

        long assimilatorCount = tick.buildings().stream()
            .filter(b -> b.type() == BuildingType.ASSIMILATOR).count();
        if (assimilatorCount >= 2) return;

        Set<Point2d> occupied = tick.buildings().stream()
            .filter(b -> b.type() == BuildingType.ASSIMILATOR)
            .map(Building::position)
            .collect(Collectors.toSet());

        tick.geysers().stream()
            .filter(g -> !occupied.contains(g.position()))
            .findFirst()
            .ifPresent(geyser -> {
                if (!tick.budget().spendMinerals(ASSIMILATOR_COST)) return;
                tick.workers().stream().findFirst().ifPresent(probe -> {
                    intentQueue.add(new BuildIntent(probe.tag(), BuildingType.ASSIMILATOR,
                        geyser.position()));
                    log.debugf("[FLOW-ECONOMICS] Assimilator at %s", geyser.position());
                });
            });
    }

    /**
     * Runs all four decision checks in one sequential pass against the same budget.
     *
     * <p>Called by {@link EconomicsFlow} as a single workflow step instead of four
     * separate {@code consume()} steps. Quarkus Flow serialises the {@link GameStateTick}
     * between {@code consume()} steps, resetting {@link ResourceBudget} to its original
     * values on each deserialisation — making multi-step budget arbitration impossible.
     * Running all checks in one step avoids that serialisation boundary.
     *
     * @see <a href="https://github.com/mdproctor/quarkmind/issues/15">#15 — budget arbitration bug</a>
     */
    public void checkAll(GameStateTick tick) {
        checkSupply(tick);
        checkProbes(tick);
        checkGas(tick);
        checkExpansion(tick);
    }

    /**
     * Queues a Nexus at the natural expansion when saturated (22 workers, 1 active Nexus).
     * Expansion position is hardcoded — Phase 3+ will use SC2MapAnalysis sidecar.
     */
    public void checkExpansion(GameStateTick tick) {
        long nexusCount = tick.buildings().stream()
            .filter(b -> b.type() == BuildingType.NEXUS).count();
        if (nexusCount >= 2) return;
        if (tick.workers().size() < PROBE_CAP) return;
        if (!tick.budget().spendMinerals(NEXUS_COST)) return;
        tick.workers().stream().findFirst().ifPresent(probe -> {
            intentQueue.add(new BuildIntent(probe.tag(), BuildingType.NEXUS, NATURAL_EXPANSION_POS));
            log.infof("[FLOW-ECONOMICS] Expanding to natural at %s", NATURAL_EXPANSION_POS);
        });
    }
}
