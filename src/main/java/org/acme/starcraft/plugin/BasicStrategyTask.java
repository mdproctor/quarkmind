package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.StrategyTask;
import org.acme.starcraft.domain.*;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.BuildIntent;
import org.acme.starcraft.sc2.intent.TrainIntent;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Basic Protoss strategy: gateway opener into Stalkers.
 *
 * <p>Build order (in priority order each tick):
 * <ol>
 *   <li>Build Gateway when a Pylon is complete and no Gateway exists.</li>
 *   <li>Build CyberneticsCore when a Gateway is complete and no Core exists.</li>
 *   <li>Train Stalkers from a complete Gateway when CyberneticsCore is done.</li>
 * </ol>
 *
 * <p>Strategy assessment written to {@link StarCraftCaseFile#STRATEGY}:
 * <ul>
 *   <li>{@code DEFEND} — enemy units visible</li>
 *   <li>{@code ATTACK} — army has {@link #ATTACK_THRESHOLD}+ Stalkers and no visible enemies</li>
 *   <li>{@code MACRO} — otherwise (keep building)</li>
 * </ul>
 *
 * <p>Note: Assimilator construction requires vespene geyser positions which are not
 * yet in the domain model. Vespene comes from {@code PlayerStatsEvent} in replay mode;
 * mock mode tests can set it via scenario. Assimilator is a Phase 3+ concern.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class BasicStrategyTask implements StrategyTask {

    static final int GATEWAY_COST          = 150;
    static final int CYBERNETICS_CORE_COST = 150;
    static final int STALKER_MINERAL_COST  = 125;
    static final int STALKER_GAS_COST      = 50;
    static final int ATTACK_THRESHOLD      = 4; // attack with 4+ Stalkers

    // Hardcoded placement — sufficient for mock and bot maps; Phase 3+ will use spatial logic
    private static final Point2d GATEWAY_POS         = new Point2d(17, 18);
    private static final Point2d CYBERNETICS_CORE_POS = new Point2d(20, 18);

    private static final Logger log = Logger.getLogger(BasicStrategyTask.class);

    private final IntentQueue intentQueue;

    @Inject
    public BasicStrategyTask(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "strategy.basic"; }
    @Override public String getName() { return "Basic Strategy"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys()  { return Set.of(StarCraftCaseFile.STRATEGY); }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        int minerals  = caseFile.get(StarCraftCaseFile.MINERALS,    Integer.class).orElse(0);
        int vespene   = caseFile.get(StarCraftCaseFile.VESPENE,     Integer.class).orElse(0);

        List<Unit>     workers   = (List<Unit>)     caseFile.get(StarCraftCaseFile.WORKERS,      List.class).orElse(List.of());
        List<Unit>     army      = (List<Unit>)     caseFile.get(StarCraftCaseFile.ARMY,         List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(StarCraftCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        List<Unit>     enemies   = (List<Unit>)     caseFile.get(StarCraftCaseFile.ENEMY_UNITS,  List.class).orElse(List.of());

        maybeBuildGateway(minerals, workers, buildings);
        maybeBuildCyberneticsCore(minerals, workers, buildings);
        maybeTrainStalker(minerals, vespene, buildings);

        String strategy = assessStrategy(army, enemies);
        caseFile.put(StarCraftCaseFile.STRATEGY, strategy);

        log.debugf("[STRATEGY] %s | stalkers=%d | enemies=%d | minerals=%d gas=%d",
            strategy,
            army.stream().filter(u -> u.type() == UnitType.STALKER).count(),
            enemies.size(), minerals, vespene);
    }

    private void maybeBuildGateway(int minerals, List<Unit> workers, List<Building> buildings) {
        if (minerals < GATEWAY_COST) return;
        boolean hasGateway = buildings.stream().anyMatch(b -> b.type() == BuildingType.GATEWAY);
        if (hasGateway) return;
        boolean hasPylon = buildings.stream().anyMatch(b -> b.type() == BuildingType.PYLON && b.isComplete());
        if (!hasPylon) return;
        workers.stream().findFirst().ifPresent(probe -> {
            intentQueue.add(new BuildIntent(probe.tag(), BuildingType.GATEWAY, GATEWAY_POS));
            log.debugf("[STRATEGY] Queuing Gateway");
        });
    }

    private void maybeBuildCyberneticsCore(int minerals, List<Unit> workers, List<Building> buildings) {
        if (minerals < CYBERNETICS_CORE_COST) return;
        boolean gatewayComplete = buildings.stream()
            .anyMatch(b -> b.type() == BuildingType.GATEWAY && b.isComplete());
        if (!gatewayComplete) return;
        boolean hasCore = buildings.stream().anyMatch(b -> b.type() == BuildingType.CYBERNETICS_CORE);
        if (hasCore) return;
        workers.stream().findFirst().ifPresent(probe -> {
            intentQueue.add(new BuildIntent(probe.tag(), BuildingType.CYBERNETICS_CORE, CYBERNETICS_CORE_POS));
            log.debugf("[STRATEGY] Queuing CyberneticsCore");
        });
    }

    private void maybeTrainStalker(int minerals, int vespene, List<Building> buildings) {
        if (minerals < STALKER_MINERAL_COST || vespene < STALKER_GAS_COST) return;
        boolean coreComplete = buildings.stream()
            .anyMatch(b -> b.type() == BuildingType.CYBERNETICS_CORE && b.isComplete());
        if (!coreComplete) return;
        buildings.stream()
            .filter(b -> b.type() == BuildingType.GATEWAY && b.isComplete())
            .findFirst()
            .ifPresent(gateway -> {
                intentQueue.add(new TrainIntent(gateway.tag(), UnitType.STALKER));
                log.debugf("[STRATEGY] Queuing Stalker");
            });
    }

    private static String assessStrategy(List<Unit> army, List<Unit> enemies) {
        if (!enemies.isEmpty()) return "DEFEND";
        long stalkers = army.stream().filter(u -> u.type() == UnitType.STALKER).count();
        if (stalkers >= ATTACK_THRESHOLD) return "ATTACK";
        return "MACRO";
    }
}
