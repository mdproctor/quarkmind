package org.acme.starcraft.plugin.flow;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.agent.ResourceBudget;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.EconomicsTask;
import org.acme.starcraft.domain.*;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * CaseHub plugin shim for the Flow-backed economics implementation.
 *
 * <p>Each CaseHub tick, this class extracts game state from the CaseFile into a
 * {@link GameStateTick} and emits it on the {@code economics-ticks} in-memory channel.
 * The long-lived {@link EconomicsFlow} instance processes the tick asynchronously
 * (one-tick lag — see ADR-0001).
 *
 * <p>The budget in the tick is a snapshot copy — independent of the CaseHub shared
 * ResourceBudget, which may already be partially consumed by other plugins before
 * the flow processes it.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class FlowEconomicsTask implements EconomicsTask {

    private static final Logger log = Logger.getLogger(FlowEconomicsTask.class);

    @Inject
    @Channel("economics-ticks")
    MutinyEmitter<GameStateTick> emitter;

    @Override public String getId()   { return "economics.flow"; }
    @Override public String getName() { return "Flow Economics"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys()  { return Set.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        List<Unit>     workers   = (List<Unit>)     caseFile.get(StarCraftCaseFile.WORKERS,      List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(StarCraftCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        List<Resource> geysers   = (List<Resource>) caseFile.get(StarCraftCaseFile.GEYSERS,      List.class).orElse(List.of());
        int supplyUsed = caseFile.get(StarCraftCaseFile.SUPPLY_USED, Integer.class).orElse(0);
        int supplyCap  = caseFile.get(StarCraftCaseFile.SUPPLY_CAP,  Integer.class).orElse(0);
        int minerals   = caseFile.get(StarCraftCaseFile.MINERALS,    Integer.class).orElse(0);
        int vespene    = caseFile.get(StarCraftCaseFile.VESPENE,     Integer.class).orElse(0);
        String strategy = caseFile.get(StarCraftCaseFile.STRATEGY, String.class).orElse("MACRO");
        ResourceBudget shared = caseFile.get(StarCraftCaseFile.RESOURCE_BUDGET, ResourceBudget.class)
            .orElse(new ResourceBudget(0, 0));

        // Snapshot copy — flow processes this one tick later; shared budget already consumed
        ResourceBudget snapshot = new ResourceBudget(shared.minerals(), shared.vespene());

        boolean gasReady = buildings.stream().anyMatch(b -> b.type() == BuildingType.GATEWAY);

        GameStateTick tick = new GameStateTick(minerals, vespene, supplyUsed, supplyCap,
            workers, buildings, geysers, snapshot, strategy, gasReady);

        emitter.sendAndForget(tick);
        log.debugf("[FLOW-ECONOMICS] Tick emitted: workers=%d supply=%d/%d gasReady=%b",
            workers.size(), supplyUsed, supplyCap, gasReady);
    }
}
