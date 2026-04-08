package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.TacticsTask;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Basic tactics: act on the {@link QuarkMindCaseFile#STRATEGY} key each tick.
 *
 * <ul>
 *   <li><b>ATTACK</b> — queue {@link AttackIntent} for each army unit toward
 *       {@link QuarkMindCaseFile#NEAREST_THREAT} (from scouting), or toward
 *       {@link #MAP_CENTER} when no visible threat is known.</li>
 *   <li><b>DEFEND</b> — queue {@link MoveIntent} for each army unit to rally
 *       near our Nexus.</li>
 *   <li><b>MACRO</b> — no-op; army holds position.</li>
 * </ul>
 *
 * <p>Attack target falls back to {@link #MAP_CENTER} when no enemy is visible.
 * This is a conservative approximation — future phases will use proper map data.
 */
public class BasicTacticsTask implements TacticsTask {

    /** Generic attack target when no scouted enemy position is available. */
    static final Point2d MAP_CENTER = new Point2d(64, 64);

    private static final Logger log = Logger.getLogger(BasicTacticsTask.class);

    private final IntentQueue intentQueue;

    @Inject
    public BasicTacticsTask(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "tactics.basic"; }
    @Override public String getName() { return "Basic Tactics"; }
    @Override public Set<String> entryCriteria() { return Set.of(QuarkMindCaseFile.READY); }
    @Override public Set<String> producedKeys()  { return Set.of(); }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        String strategy = caseFile.get(QuarkMindCaseFile.STRATEGY, String.class).orElse("MACRO");
        List<Unit>     army      = (List<Unit>)     caseFile.get(QuarkMindCaseFile.ARMY,         List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(QuarkMindCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        Point2d nearestThreat = caseFile.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class).orElse(null);

        if (army.isEmpty()) return;

        switch (strategy) {
            case "ATTACK" -> executeAttack(army, nearestThreat);
            case "DEFEND" -> executeDefend(army, buildings);
            // MACRO: hold position
        }

        log.debugf("[TACTICS] %s | army=%d | target=%s",
            strategy, army.size(), nearestThreat != null ? nearestThreat : MAP_CENTER);
    }

    private void executeAttack(List<Unit> army, Point2d nearestThreat) {
        Point2d target = nearestThreat != null ? nearestThreat : MAP_CENTER;
        army.forEach(unit -> intentQueue.add(new AttackIntent(unit.tag(), target)));
    }

    private void executeDefend(List<Unit> army, List<Building> buildings) {
        Point2d rallyPoint = buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst()
            .map(Building::position)
            .orElse(MAP_CENTER);
        army.forEach(unit -> intentQueue.add(new MoveIntent(unit.tag(), rallyPoint)));
    }
}
