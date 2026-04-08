package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.ScoutingTask;
import org.acme.starcraft.domain.*;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.MoveIntent;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Basic scouting: passive intel from visible units + active probe scout.
 *
 * <p><b>Passive intel</b> (every tick):
 * <ul>
 *   <li>{@link StarCraftCaseFile#ENEMY_ARMY_SIZE} — count of visible enemy units</li>
 *   <li>{@link StarCraftCaseFile#NEAREST_THREAT} — position of enemy closest to our Nexus</li>
 * </ul>
 *
 * <p><b>Active scouting</b>: after {@link #SCOUT_DELAY_TICKS} ticks with no visible enemies,
 * dispatches one Probe toward the estimated enemy base (derived from our Nexus position
 * on a typical symmetric 2-player map). The assigned probe is tracked by tag; if it dies,
 * a new one is assigned.
 *
 * <p>Deactivated in favour of {@link org.acme.starcraft.plugin.scouting.DroolsScoutingTask}.
 * Marked {@code @Alternative} so Quarkus Arc does not register it as a CDI bean.
 */
@Alternative
@ApplicationScoped
@CaseType("starcraft-game")
public class BasicScoutingTask implements ScoutingTask {

    /** Delay before sending a scout — let the economy stabilise first. */
    static final int SCOUT_DELAY_TICKS = 20;

    private static final Logger log = Logger.getLogger(BasicScoutingTask.class);

    private final IntentQueue intentQueue;

    /** Tag of the probe currently assigned to scout. Null when no active scout. */
    private volatile String scoutProbeTag;

    @Inject
    public BasicScoutingTask(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "scouting.basic"; }
    @Override public String getName() { return "Basic Scouting"; }
    @Override public Set<String> entryCriteria() { return Set.of(StarCraftCaseFile.READY); }
    @Override public Set<String> producedKeys() {
        return Set.of(StarCraftCaseFile.ENEMY_ARMY_SIZE, StarCraftCaseFile.NEAREST_THREAT);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        List<Unit>     enemies   = (List<Unit>)     caseFile.get(StarCraftCaseFile.ENEMY_UNITS,  List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(StarCraftCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        List<Unit>     workers   = (List<Unit>)     caseFile.get(StarCraftCaseFile.WORKERS,      List.class).orElse(List.of());
        long frame = caseFile.get(StarCraftCaseFile.GAME_FRAME, Long.class).orElse(0L);

        // --- Passive intel ---
        caseFile.put(StarCraftCaseFile.ENEMY_ARMY_SIZE, enemies.size());

        if (!enemies.isEmpty()) {
            Point2d home = nexusPosition(buildings);
            enemies.stream()
                .min(Comparator.comparingDouble(e -> e.position().distanceTo(home)))
                .ifPresent(nearest -> {
                    caseFile.put(StarCraftCaseFile.NEAREST_THREAT, nearest.position());
                    log.debugf("[SCOUTING] Nearest threat: %s at %s",
                        nearest.type(), nearest.position());
                });
            scoutProbeTag = null; // enemies found — mission complete, release scout
        } else {
            // --- Active scouting ---
            maybeSendScout(frame, buildings, workers);
        }

        log.debugf("[SCOUTING] visible enemies=%d | scout=%s", enemies.size(), scoutProbeTag);
    }

    private void maybeSendScout(long frame, List<Building> buildings, List<Unit> workers) {
        if (frame < SCOUT_DELAY_TICKS) return;
        if (workers.isEmpty()) return;

        // Check if assigned scout is still alive
        if (scoutProbeTag != null) {
            boolean alive = workers.stream().anyMatch(w -> w.tag().equals(scoutProbeTag));
            if (alive) return; // scout is still out
            scoutProbeTag = null; // probe died — assign a new one
        }

        // Assign the last probe in the list (least likely to be actively mining)
        Unit scout = workers.get(workers.size() - 1);
        scoutProbeTag = scout.tag();

        Point2d home   = nexusPosition(buildings);
        Point2d target = estimatedEnemyBase(home);
        intentQueue.add(new MoveIntent(scout.tag(), target));
        log.infof("[SCOUTING] Scout probe %s dispatched toward estimated enemy base %s", scoutProbeTag, target);
    }

    /**
     * Estimates enemy base on a symmetric 2-player map.
     * If our Nexus is in the lower-left quadrant, enemy is upper-right, and vice versa.
     */
    static Point2d estimatedEnemyBase(Point2d ourBase) {
        float targetX = ourBase.x() < 64 ? 224 : 32;
        float targetY = ourBase.y() < 64 ? 224 : 32;
        return new Point2d(targetX, targetY);
    }

    /** Returns the position of our first Nexus, or map origin if none exists yet. */
    private static Point2d nexusPosition(List<Building> buildings) {
        return buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst()
            .map(Building::position)
            .orElse(new Point2d(0, 0));
    }
}
