package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
 *   <li>Train a Probe when worker count is below {@link #PROBE_CAP} and minerals allow.</li>
 * </ol>
 *
 * <p>Pylon placement uses a fixed spread of positions starting at (15,15) — sufficient
 * for mock mode. Real spatial placement is deferred to a later plugin revision.
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
        int minerals   = caseFile.get(StarCraftCaseFile.MINERALS,    Integer.class).orElse(0);
        int supplyUsed = caseFile.get(StarCraftCaseFile.SUPPLY_USED, Integer.class).orElse(0);
        int supplyCap  = caseFile.get(StarCraftCaseFile.SUPPLY_CAP,  Integer.class).orElse(0);

        List<Unit>     workers   = (List<Unit>)     caseFile.get(StarCraftCaseFile.WORKERS,      List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(StarCraftCaseFile.MY_BUILDINGS, List.class).orElse(List.of());

        maybeBuildPylon(minerals, supplyUsed, supplyCap, workers, buildings);
        maybeTrainProbe(minerals, workers, buildings);

        log.debugf("[ECONOMICS] workers=%d/%d supply=%d/%d minerals=%d",
            workers.size(), PROBE_CAP, supplyUsed, supplyCap, minerals);
    }

    private void maybeBuildPylon(int minerals, int supplyUsed, int supplyCap,
                                 List<Unit> workers, List<Building> buildings) {
        if (supplyCap >= MAX_SUPPLY) return;
        if (supplyUsed < supplyCap - SUPPLY_HEADROOM) return;
        if (minerals < PYLON_COST) return;
        workers.stream().findFirst().ifPresent(probe -> {
            Point2d pos = pylonPosition(buildings.size());
            intentQueue.add(new BuildIntent(probe.tag(), BuildingType.PYLON, pos));
            log.debugf("[ECONOMICS] Queuing Pylon at %s (supply %d/%d)", pos, supplyUsed, supplyCap);
        });
    }

    private void maybeTrainProbe(int minerals, List<Unit> workers, List<Building> buildings) {
        if (workers.size() >= PROBE_CAP) return;
        if (minerals < PROBE_COST) return;
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
