package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.*;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Phase E2 physics engine.
 * Probe-driven mineral harvesting, unit movement, scripted enemy waves,
 * and full intent handling (train/build/move/attack).
 * Not a CDI bean — owned and instantiated by {@link EmulatedEngine}.
 */
public class EmulatedGame {

    private static final Logger log = Logger.getLogger(EmulatedGame.class);
    private static final Point2d NEXUS_POS = new Point2d(8, 8);

    // E1 fields
    private double mineralAccumulator;
    private int    miningProbes;
    private int    vespene;
    private int    supply;
    private int    supplyUsed;
    private long   gameFrame;
    private final List<Unit>     myUnits     = new ArrayList<>();
    private final List<Building> myBuildings = new ArrayList<>();
    private final List<Unit>     enemyUnits  = new ArrayList<>();
    private final List<Resource> geysers     = new ArrayList<>();

    // E2 fields
    private double unitSpeed = 0.5;
    private final Map<String, Point2d> unitTargets  = new HashMap<>();
    private final Map<String, Point2d> enemyTargets = new HashMap<>();
    private final List<EnemyWave>         pendingWaves       = new ArrayList<>();
    private final List<PendingCompletion> pendingCompletions = new ArrayList<>();
    private int nextTag = 200;

    private record PendingCompletion(long completesAtTick, Runnable action) {}

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
        unitTargets.clear();
        enemyTargets.clear();
        pendingCompletions.clear();
        nextTag = 200;
        // pendingWaves intentionally NOT cleared — configured before reset() via configureWave()

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
        moveFriendlyUnits();
        moveEnemyUnits();
        fireCompletions();
        spawnEnemyWaves();
    }

    private void moveFriendlyUnits() {
        myUnits.replaceAll(u -> {
            Point2d target = unitTargets.get(u.tag());
            if (target == null) return u;
            Point2d newPos = stepToward(u.position(), target, unitSpeed);
            if (distance(newPos, target) < 0.2) unitTargets.remove(u.tag());
            return new Unit(u.tag(), u.type(), newPos, u.health(), u.maxHealth());
        });
    }

    private void moveEnemyUnits() {
        enemyUnits.replaceAll(u -> {
            Point2d target = enemyTargets.getOrDefault(u.tag(), NEXUS_POS);
            Point2d newPos = stepToward(u.position(), target, unitSpeed);
            return new Unit(u.tag(), u.type(), newPos, u.health(), u.maxHealth());
        });
    }

    private void fireCompletions() {
        pendingCompletions.removeIf(item -> {
            if (item.completesAtTick() > gameFrame) return false;
            item.action().run();
            return true;
        });
    }

    private void spawnEnemyWaves() {
        pendingWaves.removeIf(wave -> {
            if (wave.spawnFrame() > gameFrame) return false;
            for (int i = 0; i < wave.unitTypes().size(); i++) {
                UnitType type = wave.unitTypes().get(i);
                Point2d pos = new Point2d(wave.spawnPosition().x() + i * 0.5f,
                                          wave.spawnPosition().y());
                String tag = "enemy-" + nextTag++;
                int hp = SC2Data.maxHealth(type);
                enemyUnits.add(new Unit(tag, type, pos, hp, hp));
                enemyTargets.put(tag, wave.targetPosition());
            }
            log.infof("[EMULATED] Enemy wave spawned: %dx%s at frame %d",
                wave.unitTypes().size(), wave.unitTypes().get(0), gameFrame);
            return true;
        });
    }

    public void applyIntent(Intent intent) {
        switch (intent) {
            case MoveIntent   m -> setTarget(m.unitTag(), m.targetLocation());
            case AttackIntent a -> setTarget(a.unitTag(), a.targetLocation());
            case TrainIntent  t -> handleTrain(t);
            case BuildIntent  b -> handleBuild(b);
        }
    }

    private void setTarget(String tag, Point2d target) {
        if (myUnits.stream().anyMatch(u -> u.tag().equals(tag))) {
            unitTargets.put(tag, target);
            log.debugf("[EMULATED] %s → (%.1f,%.1f)", tag, target.x(), target.y());
        }
    }

    private void handleTrain(TrainIntent t) {
        int mCost = SC2Data.mineralCost(t.unitType());
        int gCost = SC2Data.gasCost(t.unitType());
        int sCost = SC2Data.supplyCost(t.unitType());
        if ((int) mineralAccumulator < mCost || vespene < gCost || supplyUsed + sCost > supply) {
            log.debugf("[EMULATED] Cannot train %s — insufficient resources", t.unitType());
            return;
        }
        mineralAccumulator -= mCost;
        vespene -= gCost;
        long completesAt = gameFrame + SC2Data.trainTimeInTicks(t.unitType());
        pendingCompletions.add(new PendingCompletion(completesAt, () -> {
            supplyUsed += sCost;
            String tag = "unit-" + nextTag++;
            int hp = SC2Data.maxHealth(t.unitType());
            myUnits.add(new Unit(tag, t.unitType(), new Point2d(9, 9), hp, hp));
            log.debugf("[EMULATED] Trained %s (tag=%s)", t.unitType(), tag);
        }));
    }

    private void handleBuild(BuildIntent b) {
        int mCost = SC2Data.mineralCost(b.buildingType());
        if ((int) mineralAccumulator < mCost) {
            log.debugf("[EMULATED] Cannot build %s — insufficient minerals", b.buildingType());
            return;
        }
        mineralAccumulator -= mCost;
        String tag = "bldg-" + nextTag++;
        BuildingType bt = b.buildingType();
        myBuildings.add(new Building(tag, bt, b.location(),
            SC2Data.maxBuildingHealth(bt), SC2Data.maxBuildingHealth(bt), false));
        long completesAt = gameFrame + SC2Data.buildTimeInTicks(bt);
        pendingCompletions.add(new PendingCompletion(completesAt, () -> {
            markBuildingComplete(tag);
            supply += SC2Data.supplyBonus(bt);
            log.debugf("[EMULATED] Completed %s (tag=%s)", bt, tag);
        }));
    }

    private void markBuildingComplete(String tag) {
        myBuildings.replaceAll(b -> b.tag().equals(tag)
            ? new Building(b.tag(), b.type(), b.position(), b.health(), b.maxHealth(), true)
            : b);
    }

    /** Package-private for testing — linear interpolation toward target. */
    static Point2d stepToward(Point2d from, Point2d to, double speed) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist <= speed) return to;
        return new Point2d(
            (float)(from.x() + dx * speed / dist),
            (float)(from.y() + dy * speed / dist));
    }

    static double distance(Point2d a, Point2d b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    public GameState snapshot() {
        return new GameState(
            (int) mineralAccumulator,  // floor: fractional minerals accumulate silently
            vespene, supply, supplyUsed,
            List.copyOf(myUnits), List.copyOf(myBuildings),
            List.copyOf(enemyUnits), List.copyOf(geysers),
            gameFrame);
    }

    // --- Package-private: called by EmulatedEngine ---

    /** Set unit movement speed in tiles/tick. Called by EmulatedEngine each tick for live config. */
    void setUnitSpeed(double speed) { this.unitSpeed = speed; }

    /** Configure the enemy wave. Call before reset() — pendingWaves survives reset(). */
    void configureWave(long spawnFrame, int unitCount, UnitType unitType) {
        pendingWaves.clear();
        List<UnitType> types = Collections.nCopies(unitCount, unitType);
        pendingWaves.add(new EnemyWave(
            spawnFrame,
            new ArrayList<>(types),
            new Point2d(26, 26),
            new Point2d(8, 8)
        ));
    }

    // --- Package-private: used by EmulatedGameTest ---

    void setMiningProbes(int count) { this.miningProbes = count; }

    /** Direct mineral override for tests — avoids tick-based accumulation. */
    void setMineralsForTesting(int amount) { this.mineralAccumulator = amount; }
}
