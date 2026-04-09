package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.Intent;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase E1 physics engine.
 * Probe-driven mineral harvesting. All other mechanics are stubs.
 * Not a CDI bean — owned and instantiated by {@link EmulatedEngine}.
 */
public class EmulatedGame {

    private static final Logger log = Logger.getLogger(EmulatedGame.class);

    private double mineralAccumulator;
    private int miningProbes;
    private int vespene;
    private int supply;
    private int supplyUsed;
    private long gameFrame;
    private final List<Unit>     myUnits     = new ArrayList<>();
    private final List<Building> myBuildings = new ArrayList<>();
    private final List<Unit>     enemyUnits  = new ArrayList<>();
    private final List<Resource> geysers     = new ArrayList<>();

    public void reset() {
        mineralAccumulator = SC2Data.INITIAL_MINERALS;
        miningProbes       = SC2Data.INITIAL_PROBES;
        vespene            = SC2Data.INITIAL_VESPENE;
        supply             = SC2Data.INITIAL_SUPPLY;
        supplyUsed         = SC2Data.INITIAL_SUPPLY_USED;
        gameFrame          = 0;
        myUnits.clear();
        myBuildings.clear();
        enemyUnits.clear();
        geysers.clear();

        for (int i = 0; i < SC2Data.INITIAL_PROBES; i++) {
            myUnits.add(new Unit("probe-" + i, UnitType.PROBE,
                new Point2d(9 + i * 0.5f, 9),
                SC2Data.maxHealth(UnitType.PROBE),
                SC2Data.maxHealth(UnitType.PROBE)));
        }
        myBuildings.add(new Building("nexus-0", BuildingType.NEXUS,
            new Point2d(8, 8),
            SC2Data.maxBuildingHealth(BuildingType.NEXUS),
            SC2Data.maxBuildingHealth(BuildingType.NEXUS),
            true));
        geysers.add(new Resource("geyser-0", new Point2d(5, 11), 2250));
        geysers.add(new Resource("geyser-1", new Point2d(11, 5), 2250));
    }

    public void tick() {
        gameFrame++;
        mineralAccumulator += miningProbes * SC2Data.MINERALS_PER_PROBE_PER_TICK;
    }

    /** E1 stub — intents are logged but not applied. */
    public void applyIntent(Intent intent) {
        log.debugf("[EMULATED] Intent (E1 no-op): %s", intent);
    }

    public GameState snapshot() {
        return new GameState(
            (int) mineralAccumulator,  // floor: fractional minerals accumulate silently
            vespene,
            supply,
            supplyUsed,
            List.copyOf(myUnits),
            List.copyOf(myBuildings),
            List.copyOf(enemyUnits),
            List.copyOf(geysers),
            gameFrame
        );
    }

    /** Package-private — used by tests to control mining probe count. */
    void setMiningProbes(int count) {
        this.miningProbes = count;
    }
}
