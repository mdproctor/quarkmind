package org.acme.starcraft.plugin.scouting;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.ScoutingTask;
import org.acme.starcraft.domain.*;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.MoveIntent;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Drools-backed {@link ScoutingTask} — fourth R&D integration.
 *
 * <p>Each tick:
 * <ol>
 *   <li>Detects game restarts (frame going backwards) and resets buffers.</li>
 *   <li>Computes passive intel: {@code ENEMY_ARMY_SIZE} and {@code NEAREST_THREAT}.</li>
 *   <li>Updates Java event buffers via {@link ScoutingSessionManager}; evicts expired events.</li>
 *   <li>Fires a fresh {@link RuleUnitInstance} from the current buffer state.</li>
 *   <li>Writes {@code ENEMY_BUILD_ORDER}, {@code TIMING_ATTACK_INCOMING}, {@code ENEMY_POSTURE}.</li>
 *   <li>Dispatches active probe scout (same logic as BasicScoutingTask).</li>
 * </ol>
 *
 * <p>Replaces {@link org.acme.starcraft.plugin.BasicScoutingTask} as the active CDI bean
 * (BasicScoutingTask is marked {@code @Alternative} to avoid ambiguous-bean conflict).
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class DroolsScoutingTask implements ScoutingTask {

    /** Game-speed constant: SC2 Faster = 22.4 frames per second. */
    static final double FRAMES_PER_SECOND = 22.4;

    /** Delay before sending a scout — let the economy stabilise first. */
    public static final int SCOUT_DELAY_TICKS = 20;

    private static final Logger log = Logger.getLogger(DroolsScoutingTask.class);

    private final RuleUnit<ScoutingRuleUnit> ruleUnit;
    private final ScoutingSessionManager     sessionManager;
    private final IntentQueue                intentQueue;

    /** Tag of the probe currently assigned to scout. Null when no active scout. */
    private volatile String scoutProbeTag;
    // Single scheduler thread — no synchronisation needed
    private long lastFrame = -1;

    @Inject
    public DroolsScoutingTask(RuleUnit<ScoutingRuleUnit> ruleUnit,
                               ScoutingSessionManager sessionManager,
                               IntentQueue intentQueue) {
        this.ruleUnit       = ruleUnit;
        this.sessionManager = sessionManager;
        this.intentQueue    = intentQueue;
    }

    @Override public String getId()   { return "scouting.drools-cep"; }
    @Override public String getName() { return "Drools CEP Scouting"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys()  {
        return Set.of(
            StarCraftCaseFile.ENEMY_ARMY_SIZE,
            StarCraftCaseFile.NEAREST_THREAT,
            StarCraftCaseFile.ENEMY_BUILD_ORDER,
            StarCraftCaseFile.TIMING_ATTACK_INCOMING,
            StarCraftCaseFile.ENEMY_POSTURE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        List<Unit>     enemies   = (List<Unit>)     caseFile.get(StarCraftCaseFile.ENEMY_UNITS,  List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(StarCraftCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        List<Unit>     workers   = (List<Unit>)     caseFile.get(StarCraftCaseFile.WORKERS,      List.class).orElse(List.of());
        long frame = caseFile.get(StarCraftCaseFile.GAME_FRAME, Long.class).orElse(0L);

        // Detect game restart (mock loop resets frame to 0)
        if (frame < lastFrame) {
            sessionManager.reset();
            scoutProbeTag = null;
        }
        lastFrame = frame;

        long gameTimeMs = (long) (frame * (1000.0 / FRAMES_PER_SECOND));
        Point2d ourNexus      = nexusPosition(buildings);
        Point2d estimatedBase = estimatedEnemyBase(ourNexus);

        // --- Passive intel (plain Java, no rules needed) ---
        caseFile.put(StarCraftCaseFile.ENEMY_ARMY_SIZE, enemies.size());
        if (!enemies.isEmpty()) {
            enemies.stream()
                .min(Comparator.comparingDouble(e -> e.position().distanceTo(ourNexus)))
                .ifPresent(nearest -> caseFile.put(StarCraftCaseFile.NEAREST_THREAT, nearest.position()));
        }

        // --- CEP event accumulation ---
        sessionManager.processFrame(enemies, gameTimeMs, ourNexus, estimatedBase);
        sessionManager.evict(gameTimeMs);

        // --- Drools rules firing ---
        ScoutingRuleUnit data = sessionManager.buildRuleUnit();
        try (RuleUnitInstance<ScoutingRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }

        // --- Write CEP intel to CaseFile ---
        String build = data.getDetectedBuilds().isEmpty() ? "UNKNOWN" : data.getDetectedBuilds().get(0);
        caseFile.put(StarCraftCaseFile.ENEMY_BUILD_ORDER, build);
        caseFile.put(StarCraftCaseFile.TIMING_ATTACK_INCOMING, !data.getTimingAlerts().isEmpty());
        String posture = data.getPostureDecisions().isEmpty()
            ? "UNKNOWN"
            : data.getPostureDecisions().get(0);
        caseFile.put(StarCraftCaseFile.ENEMY_POSTURE, posture);

        log.debugf("[SCOUTING] enemies=%d | build=%s | timing=%b | posture=%s",
            enemies.size(), build, !data.getTimingAlerts().isEmpty(), posture);

        // --- Active scouting (same as BasicScoutingTask) ---
        if (enemies.isEmpty()) {
            maybeSendScout(frame, workers, estimatedBase);
        } else {
            scoutProbeTag = null; // enemies found — release scout
        }
    }

    private void maybeSendScout(long frame, List<Unit> workers, Point2d target) {
        if (frame < SCOUT_DELAY_TICKS) return;
        if (workers.isEmpty()) return;

        if (scoutProbeTag != null) {
            boolean alive = workers.stream().anyMatch(w -> w.tag().equals(scoutProbeTag));
            if (alive) return;
            scoutProbeTag = null; // probe died — assign a new one
        }

        Unit scout = workers.get(workers.size() - 1);
        scoutProbeTag = scout.tag();
        intentQueue.add(new MoveIntent(scout.tag(), target));
        log.infof("[SCOUTING] Scout probe %s dispatched toward %s", scoutProbeTag, target);
    }

    static Point2d estimatedEnemyBase(Point2d ourBase) {
        float targetX = ourBase.x() < 64 ? 224 : 32;
        float targetY = ourBase.y() < 64 ? 224 : 32;
        return new Point2d(targetX, targetY);
    }

    private static Point2d nexusPosition(List<Building> buildings) {
        return buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst()
            .map(Building::position)
            .orElse(new Point2d(0, 0));
    }
}
