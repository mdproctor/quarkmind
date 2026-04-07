package org.acme.starcraft.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.agent.StarCraftCaseFile;
import org.acme.starcraft.agent.plugin.ScoutingTask;
import org.acme.starcraft.domain.*;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Basic scouting: derives intel from currently visible units and writes it
 * to the CaseFile for other plugins to consume.
 *
 * <p>Writes:
 * <ul>
 *   <li>{@link StarCraftCaseFile#ENEMY_ARMY_SIZE} — count of visible enemy units</li>
 *   <li>{@link StarCraftCaseFile#NEAREST_THREAT} — position of the enemy unit
 *       closest to our Nexus; absent when no enemies are visible</li>
 * </ul>
 *
 * <p>Note: This plugin operates only on units already visible in the current
 * observation. Active scouting (sending a Probe to explore) is a future concern.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class BasicScoutingTask implements ScoutingTask {

    private static final Logger log = Logger.getLogger(BasicScoutingTask.class);

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

        caseFile.put(StarCraftCaseFile.ENEMY_ARMY_SIZE, enemies.size());

        if (!enemies.isEmpty()) {
            Point2d home = nexusPosition(buildings);
            enemies.stream()
                .min(Comparator.comparingDouble(e -> e.position().distanceTo(home)))
                .ifPresent(nearest -> {
                    caseFile.put(StarCraftCaseFile.NEAREST_THREAT, nearest.position());
                    log.debugf("[SCOUTING] Nearest threat: %s at %s (dist=%.1f from home)",
                        nearest.type(), nearest.position(), nearest.position().distanceTo(home));
                });
        }

        log.debugf("[SCOUTING] visible enemies=%d", enemies.size());
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
