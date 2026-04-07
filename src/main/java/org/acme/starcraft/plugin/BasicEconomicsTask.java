package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.agent.ResourceBudget;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.EconomicsTask;
import org.acme.starcraft.domain.*;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.BuildIntent;
import org.acme.starcraft.sc2.intent.TrainIntent;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Basic Protoss economics: probe production and pylon supply management.
 *
 * <p>Rules (in priority order):
 * <ol>
 *   <li>Build a Pylon when supply headroom drops to {@link #SUPPLY_HEADROOM} or below.</li>
 *   <li>Train a Probe when worker count is below {@link #PROBE_CAP} and budget allows.</li>
 * </ol>
 *
 * <p>Uses {@link ResourceBudget} to prevent double-spending with other plugins.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class BasicEconomicsTask implements EconomicsTask {

    static final int PROBE_CAP       = 22;  // optimal: 16 mineral + 6 gas workers per base
    static final int SUPPLY_HEADROOM = 4;   // build Pylon when this many supply remain
    static final int MAX_SUPPLY      = 200;
    static final int PROBE_COST      = 50;
    static final int PYLON_COST      = 100;

    private static final Logger log = Logger.getLogger(BasicEconomicsTask.class);

    private final IntentQueue intentQueue;

    @Inject
    public BasicEconomicsTask(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "economics.basic"; }
    @Override public String getName() { return "Basic Economics"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys()  { return Set.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        int supplyUsed = caseFile.get(StarCraftCaseFile.SUPPLY_USED, Integer.class).orElse(0);
        int supplyCap  = caseFile.get(StarCraftCaseFile.SUPPLY_CAP,  Integer.class).orElse(0);

        List<Unit>     workers   = (List<Unit>)     caseFile.get(StarCraftCaseFile.WORKERS,      List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(StarCraftCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        ResourceBudget budget    = caseFile.get(StarCraftCaseFile.RESOURCE_BUDGET, ResourceBudget.class)
            .orElse(new ResourceBudget(0, 0));

        maybeBuildPylon(budget, supplyUsed, supplyCap, workers, buildings);
        maybeTrainProbe(budget, workers, buildings);

        log.debugf("[ECONOMICS] workers=%d/%d supply=%d/%d budget=%s",
            workers.size(), PROBE_CAP, supplyUsed, supplyCap, budget);
    }

    private void maybeBuildPylon(ResourceBudget budget, int supplyUsed, int supplyCap,
                                 List<Unit> workers, List<Building> buildings) {
        if (supplyCap >= MAX_SUPPLY) return;
        if (supplyUsed < supplyCap - SUPPLY_HEADROOM) return;
        if (!budget.spendMinerals(PYLON_COST)) return;
        workers.stream().findFirst().ifPresent(probe -> {
            Point2d pos = pylonPosition(buildings.size());
            intentQueue.add(new BuildIntent(probe.tag(), BuildingType.PYLON, pos));
            log.debugf("[ECONOMICS] Queuing Pylon at %s (supply %d/%d)", pos, supplyUsed, supplyCap);
        });
    }

    private void maybeTrainProbe(ResourceBudget budget, List<Unit> workers, List<Building> buildings) {
        if (workers.size() >= PROBE_CAP) return;
        if (!budget.spendMinerals(PROBE_COST)) return;
        buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS && b.isComplete())
            .findFirst()
            .ifPresent(nexus -> {
                intentQueue.add(new TrainIntent(nexus.tag(), UnitType.PROBE));
                log.debugf("[ECONOMICS] Queuing Probe (workers=%d/%d)", workers.size(), PROBE_CAP);
            });
    }

    /**
     * Returns a Pylon placement position. Spreads up to 4 pylons per row,
     * starting at (15,15) — safe for standard mock/bot maps.
     */
    static Point2d pylonPosition(int buildingCount) {
        int col = buildingCount % 4;
        int row = buildingCount / 4;
        return new Point2d(15 + col * 3, 15 + row * 3);
    }
}
