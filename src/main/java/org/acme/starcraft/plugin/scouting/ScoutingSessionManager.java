package org.acme.starcraft.plugin.scouting;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.domain.Point2d;
import org.acme.starcraft.domain.Unit;
import org.acme.starcraft.plugin.scouting.events.EnemyArmyNearBase;
import org.acme.starcraft.plugin.scouting.events.EnemyExpansionSeen;
import org.acme.starcraft.plugin.scouting.events.EnemyUnitFirstSeen;

import java.util.*;

/**
 * Manages Java-side event buffers for {@link DroolsScoutingTask}.
 *
 * <p>Maintains three rolling windows:
 * <ul>
 *   <li>Unit first-seen events — 3-minute build-order window (evicted by timestamp)</li>
 *   <li>Army-near-base events — 10-second threat window (evicted by timestamp)</li>
 *   <li>Expansion events     — permanent for the life of the game</li>
 * </ul>
 *
 * <p>Call {@link #reset()} on game restart (detected by frame going backwards).
 */
@ApplicationScoped
public class ScoutingSessionManager {

    /** Build-order detection window: 3 minutes at SC2 Faster speed. */
    public static final long UNIT_WINDOW_MS = 3L * 60 * 1000;

    /** Timing-attack threat window: 10 seconds. */
    public static final long ARMY_WINDOW_MS = 10L * 1000;

    /**
     * Distance threshold (tiles) from estimated enemy main base beyond which
     * a sighted unit is treated as evidence of an expansion or forward base.
     */
    public static final float EXPANSION_DISTANCE_THRESHOLD = 50f;

    /** Minimum enemy units near our Nexus to trigger an army-near-base event. */
    public static final int MIN_ARMY_NEAR_BASE = 3;

    /** Distance (tiles) from our Nexus that counts as "near our base". */
    public static final float NEAR_BASE_DISTANCE = 30f;

    private final Set<String>               seenUnitTags       = new HashSet<>();
    private final Set<String>               seenExpansionCells = new HashSet<>();
    private final Deque<EnemyUnitFirstSeen> unitBuffer         = new ArrayDeque<>();
    private final Deque<EnemyArmyNearBase>  armyBuffer         = new ArrayDeque<>();
    private final List<EnemyExpansionSeen>  expansionBuffer    = new ArrayList<>();

    /** Clears all buffers. Call when a new game starts. */
    public void reset() {
        seenUnitTags.clear();
        seenExpansionCells.clear();
        unitBuffer.clear();
        armyBuffer.clear();
        expansionBuffer.clear();
    }

    /**
     * Processes visible enemy units for this tick, inserting new events into buffers.
     *
     * @param enemies              currently visible enemy units
     * @param gameTimeMs           current game time in milliseconds (frame x 1000/22.4)
     * @param ourNexus             position of our first Nexus (home base reference)
     * @param estimatedEnemyBase   estimated position of the enemy main base
     */
    public void processFrame(List<Unit> enemies, long gameTimeMs,
                             Point2d ourNexus, Point2d estimatedEnemyBase) {
        long nearCount = 0;
        for (Unit e : enemies) {
            if (seenUnitTags.add(e.tag())) {
                unitBuffer.add(new EnemyUnitFirstSeen(e.type(), gameTimeMs));
            }

            float distToEnemyBase = (float) e.position().distanceTo(estimatedEnemyBase);
            if (distToEnemyBase > EXPANSION_DISTANCE_THRESHOLD) {
                String cell = (int) e.position().x() + ":" + (int) e.position().y();
                if (seenExpansionCells.add(cell)) {
                    expansionBuffer.add(new EnemyExpansionSeen(e.position(), gameTimeMs));
                }
            }

            if (e.position().distanceTo(ourNexus) < NEAR_BASE_DISTANCE) {
                nearCount++;
            }
        }

        if (nearCount >= MIN_ARMY_NEAR_BASE) {
            armyBuffer.add(new EnemyArmyNearBase((int) nearCount, gameTimeMs));
        }
    }

    /**
     * Removes events that have fallen outside their temporal window.
     * Call once per tick AFTER {@link #processFrame}.
     */
    public void evict(long currentGameTimeMs) {
        unitBuffer.removeIf(e -> currentGameTimeMs - e.gameTimeMs() > UNIT_WINDOW_MS);
        armyBuffer.removeIf(e -> currentGameTimeMs - e.gameTimeMs() > ARMY_WINDOW_MS);
    }

    /**
     * Builds a fresh {@link ScoutingRuleUnit} populated from the current buffer contents.
     * Call after {@link #evict} so the rule unit only sees events within their windows.
     *
     * <p><strong>Note:</strong> requires Quarkus build-time init (GE-0053) — not callable
     * from plain JUnit tests. Use the testability accessors below in unit tests instead.
     */
    public ScoutingRuleUnit buildRuleUnit() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        unitBuffer.forEach(data.getUnitEvents()::add);
        expansionBuffer.forEach(data.getExpansionEvents()::add);
        armyBuffer.forEach(data.getArmyNearBaseEvents()::add);
        return data;
    }

    // ---- Testability accessors ----
    public int seenTagCount()        { return seenUnitTags.size(); }
    public int unitBufferSize()      { return unitBuffer.size(); }
    public int armyBufferSize()      { return armyBuffer.size(); }
    public int expansionBufferSize() { return expansionBuffer.size(); }
}
