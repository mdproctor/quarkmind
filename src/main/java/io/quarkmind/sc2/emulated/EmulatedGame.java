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
    private static final Point2d NEXUS_POS    = new Point2d(8, 8);
    private static final Point2d STAGING_POS  = new Point2d(26, 26);

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
    private final Set<String>          attackingUnits = new HashSet<>(); // E3: units with active AttackIntent
    // E4: per-unit attack cooldowns (absent key = 0 = can attack immediately)
    private final Map<String, Integer> unitCooldowns  = new HashMap<>();
    private final Map<String, Integer> enemyCooldowns = new HashMap<>();
    // E4: enemy economy
    private EnemyStrategy enemyStrategy;
    private double        enemyMineralAccumulator;
    private int           enemyBuildIndex;
    private long          framesSinceLastAttack;
    private final List<Unit> enemyStagingArea = new ArrayList<>();
    // E6: retreat tracking
    private final Set<String> retreatingUnits  = new HashSet<>();
    private int               initialAttackSize = 0;
    private final List<EnemyWave>         pendingWaves       = new ArrayList<>();
    private final List<PendingCompletion> pendingCompletions = new ArrayList<>();
    private int nextTag = 200;
    private final DamageCalculator damageCalculator = new DamageCalculator();
    private MovementStrategy movementStrategy = new DirectMovement();
    // E7: hard physics constraint — no unit may land on a wall tile regardless of movement strategy.
    // Null in mock/test contexts where no terrain exists.
    private TerrainGrid terrainGrid = null;
    private Random random = new Random(42L);
    // E9: fog of war — persists across ticks, reset on game restart
    private final VisibilityGrid visibility = new VisibilityGrid();

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
        attackingUnits.clear();
        unitCooldowns.clear();
        enemyCooldowns.clear();
        enemyMineralAccumulator = 0;
        enemyBuildIndex         = 0;
        framesSinceLastAttack   = 0;
        enemyStagingArea.clear();
        retreatingUnits.clear();
        initialAttackSize = 0;
        pendingCompletions.clear();
        nextTag = 200;
        movementStrategy.reset();
        visibility.reset();
        // pendingWaves intentionally NOT cleared — configured before reset() via configureWave()
        // terrainGrid and random intentionally NOT cleared — set by EmulatedEngine before reset(), persist across resets

        for (int i = 0; i < SC2Data.INITIAL_PROBES; i++) {
            myUnits.add(new Unit("probe-" + i, UnitType.PROBE,
                new Point2d(9 + i * 0.5f, 9),
                SC2Data.maxHealth(UnitType.PROBE), SC2Data.maxHealth(UnitType.PROBE),
                SC2Data.maxShields(UnitType.PROBE), SC2Data.maxShields(UnitType.PROBE)));
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
        // Recompute after movement, before combat: a unit that dies this tick still
        // provided vision for this frame — correct SC2 behaviour.
        visibility.recompute(myUnits, myBuildings, terrainGrid);
        moveEnemyUnits();
        resolveCombat();
        tickEnemyRetreat();
        fireCompletions();
        spawnEnemyWaves();
        tickEnemyStrategy();
    }

    /**
     * Hard physics wall constraint — applied after every movement strategy call.
     * If the proposed position lands on a wall tile, the unit stays put.
     * This is independent of pathfinding and acts as an inviolable backstop.
     */
    private Point2d enforceWall(String unitTag, Point2d proposed, Point2d current) {
        if (terrainGrid == null) return proposed;
        int tx = (int) proposed.x();
        int ty = (int) proposed.y();
        if (!terrainGrid.isWalkable(tx, ty)) {
            log.warnf("[PHYSICS] Wall collision blocked %s at (%.2f,%.2f) tile(%d,%d) — invalidating path",
                unitTag, proposed.x(), proposed.y(), tx, ty);
            movementStrategy.invalidatePath(unitTag); // repath next tick from current position
            return current;
        }
        return proposed;
    }

    private void moveFriendlyUnits() {
        myUnits.replaceAll(u -> {
            Point2d target = unitTargets.get(u.tag());
            if (target == null) return u;
            Point2d newPos = enforceWall(u.tag(),
                movementStrategy.advance(u.tag(), u.position(), target, unitSpeed),
                u.position());
            if (distance(newPos, target) < 0.2) unitTargets.remove(u.tag());
            return new Unit(u.tag(), u.type(), newPos, u.health(), u.maxHealth(),
                            u.shields(), u.maxShields());
        });
    }

    private void moveEnemyUnits() {
        enemyUnits.replaceAll(u -> {
            // Non-retreating units stop to fight when a friendly is within attack range.
            // Retreating units always move — they are disengaging, not attacking.
            if (!retreatingUnits.contains(u.tag()) &&
                    nearestInRange(u.position(), myUnits, SC2Data.attackRange(u.type())).isPresent()) {
                return u; // stay and fight — resolveCombat() handles the attack this tick
            }
            Point2d target = enemyTargets.getOrDefault(u.tag(), NEXUS_POS);
            Point2d newPos = enforceWall(u.tag(),
                movementStrategy.advance(u.tag(), u.position(), target, unitSpeed),
                u.position());
            // Log when a unit crosses the wall row (y transitions through 18)
            float prevY = u.position().y();
            float nextY = newPos.y();
            if ((prevY > 18f && nextY <= 18f) || (prevY >= 18f && nextY < 18f)) {
                log.infof("[WALL-CROSS] %s crossed y=18: from (%.2f,%.2f) to (%.2f,%.2f) — x=%.2f %s",
                    u.tag(), u.position().x(), prevY, newPos.x(), nextY, newPos.x(),
                    (newPos.x() >= 11f && newPos.x() <= 14f) ? "IN GAP ✓" : "THROUGH WALL ✗");
            }
            return new Unit(u.tag(), u.type(), newPos, u.health(), u.maxHealth(),
                            u.shields(), u.maxShields());
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
                enemyUnits.add(new Unit(tag, type, pos, hp, hp,
                    SC2Data.maxShields(type), SC2Data.maxShields(type)));
                enemyTargets.put(tag, wave.targetPosition());
            }
            log.infof("[EMULATED] Enemy wave spawned: %dx%s at frame %d",
                wave.unitTypes().size(), wave.unitTypes().get(0), gameFrame);
            return true;
        });
    }

    private void tickEnemyRetreat() {
        if (enemyStrategy == null || initialAttackSize == 0) return;
        EnemyAttackConfig atk = enemyStrategy.attackConfig();

        // 1. Per-unit health threshold
        if (atk.retreatHealthPercent() > 0) {
            for (Unit u : enemyUnits) {
                if (retreatingUnits.contains(u.tag())) continue;
                double totalHp    = u.health() + u.shields();
                double maxTotalHp = (double) u.maxHealth() + u.maxShields();
                if (totalHp / maxTotalHp * 100 < atk.retreatHealthPercent()) {
                    retreatingUnits.add(u.tag());
                    enemyTargets.put(u.tag(), STAGING_POS);
                    log.debugf("[EMULATED] Unit %s retreating (%.1f%% hp)", u.tag(),
                        totalHp / maxTotalHp * 100);
                }
            }
        }

        // 2. Army-wide depletion threshold
        if (atk.retreatArmyPercent() > 0) {
            double survivingPct = (double) enemyUnits.size() / initialAttackSize * 100;
            if (survivingPct < atk.retreatArmyPercent()) {
                for (Unit u : enemyUnits) {
                    if (retreatingUnits.contains(u.tag())) continue;
                    retreatingUnits.add(u.tag());
                    enemyTargets.put(u.tag(), STAGING_POS);
                }
                log.infof("[EMULATED] Army retreat: %.0f%% surviving (%d/%d)",
                    survivingPct, enemyUnits.size(), initialAttackSize);
            }
        }

        // 3. Transfer arrived retreating units back to enemyStagingArea.
        // Threshold < 0.1 (not 0.5): stepToward snaps units to exactly STAGING_POS when
        // within unitSpeed (0.5) of it. After snapping, distance = 0.0. Using < 0.1 avoids
        // the floating-point ambiguity of comparing ~0.5 against 0.5 at the pre-snap position.
        enemyUnits.removeIf(u -> {
            if (!retreatingUnits.contains(u.tag())) return false;
            if (distance(u.position(), STAGING_POS) >= 0.1) return false;
            retreatingUnits.remove(u.tag());
            enemyTargets.remove(u.tag());
            movementStrategy.clearUnit(u.tag());
            enemyStagingArea.add(u);  // damaged HP preserved — no healing
            log.debugf("[EMULATED] Unit %s arrived at staging (hp=%d shields=%d)",
                u.tag(), u.health(), u.shields());
            return true;
        });
    }

    private void tickEnemyStrategy() {
        if (enemyStrategy == null) return;

        // 1. Accumulate enemy minerals
        enemyMineralAccumulator += enemyStrategy.mineralsPerTick();

        // 2. Execute next build step if affordable and order not exhausted
        List<EnemyBuildStep> order = enemyStrategy.buildOrder();
        if (!order.isEmpty()) {
            boolean canAdvance = enemyStrategy.loop() || enemyBuildIndex < order.size();
            if (canAdvance) {
                EnemyBuildStep step = order.get(enemyBuildIndex % order.size());
                int cost = SC2Data.mineralCost(step.unitType());
                if ((int) enemyMineralAccumulator >= cost) {
                    enemyMineralAccumulator -= cost;
                    String tag = "enemy-" + nextTag++;
                    int hp = SC2Data.maxHealth(step.unitType());
                    enemyStagingArea.add(new Unit(tag, step.unitType(),
                        STAGING_POS, hp, hp,
                        SC2Data.maxShields(step.unitType()), SC2Data.maxShields(step.unitType())));
                    if (enemyStrategy.loop() || enemyBuildIndex < order.size() - 1)
                        enemyBuildIndex++;
                    else
                        enemyBuildIndex = order.size(); // mark exhausted
                    log.debugf("[EMULATED] Enemy trained %s (staging size=%d)",
                        step.unitType(), enemyStagingArea.size());
                }
            }
        }

        // 3. Check attack triggers (army threshold OR frame timer)
        framesSinceLastAttack++;
        EnemyAttackConfig atk = enemyStrategy.attackConfig();
        boolean thresholdMet = enemyStagingArea.size() >= atk.armyThreshold();
        boolean timerFired   = framesSinceLastAttack >= atk.attackIntervalFrames();
        if ((thresholdMet || timerFired) && !enemyStagingArea.isEmpty()) {
            initialAttackSize = enemyStagingArea.size();   // E6: denominator for army retreat check
            for (Unit u : enemyStagingArea) {
                enemyUnits.add(u);
                enemyTargets.put(u.tag(), NEXUS_POS);
            }
            log.infof("[EMULATED] Enemy attack launched: %d units (threshold=%b timer=%b)",
                enemyStagingArea.size(), thresholdMet, timerFired);
            enemyStagingArea.clear();
            framesSinceLastAttack = 0;
        }
    }

    public void applyIntent(Intent intent) {
        switch (intent) {
            case MoveIntent   m -> setTarget(m.unitTag(), m.targetLocation(), false);
            case AttackIntent a -> setTarget(a.unitTag(), a.targetLocation(), true);
            case TrainIntent  t -> handleTrain(t);
            case BuildIntent  b -> handleBuild(b);
        }
    }

    private void setTarget(String tag, Point2d target, boolean isAttack) {
        if (myUnits.stream().anyMatch(u -> u.tag().equals(tag))) {
            unitTargets.put(tag, target);
            if (isAttack) attackingUnits.add(tag);
            else          attackingUnits.remove(tag);  // MoveIntent cancels auto-attack
            log.debugf("[EMULATED] %s → (%.1f,%.1f) attack=%b", tag, target.x(), target.y(), isAttack);
        }
    }

    private void handleTrain(TrainIntent t) {
        // Note: t.unitTag() (the training building) is intentionally not validated in the emulator —
        // we have one Nexus and training always succeeds if resources allow. In real SC2 the tag
        // would identify which specific building to queue the unit in.
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
            // TODO: reserve supplyUsed at queue time (not completion time) to prevent
            // double-queuing when two TrainIntents arrive before either completes.
            supplyUsed += sCost;
            String tag = "unit-" + nextTag++;
            int hp = SC2Data.maxHealth(t.unitType());
            myUnits.add(new Unit(tag, t.unitType(), new Point2d(9, 9), hp, hp,
                SC2Data.maxShields(t.unitType()), SC2Data.maxShields(t.unitType())));
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

    private void resolveCombat() {
        // Step 1: decrement all cooldowns (floor 0)
        unitCooldowns.replaceAll((tag, cd) -> Math.max(0, cd - 1));
        enemyCooldowns.replaceAll((tag, cd) -> Math.max(0, cd - 1));

        Map<String, Integer> pending       = new HashMap<>();
        Set<String>          firedFriendly = new HashSet<>();
        Set<String>          firedEnemy    = new HashSet<>();

        // Step 2: collect damage from units where cooldown == 0
        for (Unit attacker : myUnits) {
            if (!attackingUnits.contains(attacker.tag())) continue;
            if (unitCooldowns.getOrDefault(attacker.tag(), 0) > 0) continue;
            nearestInRange(attacker.position(), enemyUnits, SC2Data.attackRange(attacker.type()))
                .ifPresent(target -> {
                    if (!missesHighGround(attacker.position(), target.position(), attacker.type())) {
                        pending.merge(target.tag(),
                            damageCalculator.computeEffective(attacker.type(), target), Integer::sum);
                    }
                    firedFriendly.add(attacker.tag()); // cooldown resets even on miss
                });
        }
        for (Unit attacker : enemyUnits) {
            if (enemyCooldowns.getOrDefault(attacker.tag(), 0) > 0) continue;
            nearestInRange(attacker.position(), myUnits, SC2Data.attackRange(attacker.type()))
                .ifPresent(target -> {
                    if (!missesHighGround(attacker.position(), target.position(), attacker.type())) {
                        pending.merge(target.tag(),
                            damageCalculator.computeEffective(attacker.type(), target), Integer::sum);
                    }
                    firedEnemy.add(attacker.tag()); // cooldown resets even on miss
                });
        }

        // Step 3: apply damage — two-pass (collect all, then apply)
        myUnits.replaceAll(u -> applyDamage(u, pending.getOrDefault(u.tag(), 0)));
        myUnits.removeIf(u -> {
            if (u.health() <= 0) {
                unitTargets.remove(u.tag());
                attackingUnits.remove(u.tag());
                unitCooldowns.remove(u.tag());
                movementStrategy.clearUnit(u.tag());
                return true;
            }
            return false;
        });
        enemyUnits.replaceAll(u -> applyDamage(u, pending.getOrDefault(u.tag(), 0)));
        enemyUnits.removeIf(u -> {
            if (u.health() <= 0) {
                enemyTargets.remove(u.tag());
                enemyCooldowns.remove(u.tag());
                retreatingUnits.remove(u.tag());  // E6: clean up if killed while retreating
                movementStrategy.clearUnit(u.tag());
                return true;
            }
            return false;
        });

        // Step 4: reset cooldown for units that fired
        for (Unit u : myUnits) {
            if (firedFriendly.contains(u.tag()))
                unitCooldowns.put(u.tag(), SC2Data.attackCooldownInTicks(u.type()));
        }
        for (Unit u : enemyUnits) {
            if (firedEnemy.contains(u.tag()))
                enemyCooldowns.put(u.tag(), SC2Data.attackCooldownInTicks(u.type()));
        }
    }

    private static Optional<Unit> nearestInRange(Point2d from, List<Unit> candidates, float range) {
        return candidates.stream()
            .filter(u -> distance(from, u.position()) <= range)
            .min(Comparator.comparingDouble(u ->
                distance(from, u.position()) * 1000 + u.health() + u.shields()));
    }

    /**
     * Returns true if this attack should miss due to low-ground-to-high-ground penalty.
     * Condition: attacker on LOW, target on HIGH, attack is ranged (range > 1.0), and RNG says miss.
     * Returns false (no miss) when terrainGrid is null or the height condition is not met.
     */
    private boolean missesHighGround(Point2d attackerPos, Point2d targetPos, UnitType attackerType) {
        if (terrainGrid == null) return false;
        if (SC2Data.attackRange(attackerType) <= 1.0f) return false; // melee — never penalised
        TerrainGrid.Height ah = terrainGrid.heightAt((int) attackerPos.x(), (int) attackerPos.y());
        TerrainGrid.Height th = terrainGrid.heightAt((int) targetPos.x(),   (int) targetPos.y());
        if (ah != TerrainGrid.Height.LOW || th != TerrainGrid.Height.HIGH) return false;
        return random.nextDouble() < 0.25;
    }

    private static Unit applyDamage(Unit u, int damage) {
        if (damage <= 0) return u;
        int shieldsLeft = Math.max(0, u.shields() - damage);
        int overflow    = Math.max(0, damage - u.shields());
        int hpLeft      = Math.max(0, u.health() - overflow);
        return new Unit(u.tag(), u.type(), u.position(), hpLeft, u.maxHealth(),
                        shieldsLeft, u.maxShields());
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
        // Fog filtering is only active when terrain is configured.
        // Without terrain (mock/test contexts) all enemies are visible as before.
        // Fog gated on terrainGrid: recompute(null terrain) treats all tiles as LOW and
        // produces selective visibility (only tiles within probe range are VISIBLE), which
        // would make distant enemies invisible in no-terrain test contexts. When no terrain
        // is configured, return all enemies as pre-fog behaviour.
        if (terrainGrid != null) {
            List<Unit> visibleEnemies = enemyUnits.stream()
                .filter(u -> visibility.isVisible(u.position()))
                .toList();
            List<Unit> visibleStaging = enemyStagingArea.stream()
                .filter(u -> visibility.isVisible(u.position()))
                .toList();
            return new GameState(
                (int) mineralAccumulator,
                vespene, supply, supplyUsed,
                List.copyOf(myUnits), List.copyOf(myBuildings),
                visibleEnemies,
                visibleStaging,
                List.copyOf(geysers),
                gameFrame);
        }
        return new GameState(
            (int) mineralAccumulator, // floor: fractional minerals accumulate silently
            vespene, supply, supplyUsed,
            List.copyOf(myUnits), List.copyOf(myBuildings),
            List.copyOf(enemyUnits),
            List.copyOf(enemyStagingArea),
            List.copyOf(geysers),
            gameFrame);
    }

    /** Returns the current visibility grid — used by EmulatedEngine to update VisibilityHolder. */
    VisibilityGrid observeVisibility() { return visibility; }

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
            STAGING_POS,
            new Point2d(8, 8)
        ));
    }

    // --- Package-private: used by EmulatedGameTest ---

    void setMiningProbes(int count) { this.miningProbes = count; }

    void setEnemyStrategy(EnemyStrategy s) { this.enemyStrategy = s; }
    int  enemyMinerals()                   { return (int) enemyMineralAccumulator; }
    int  enemyStagingSize()                { return enemyStagingArea.size(); }

    /** Direct mineral override for tests — avoids tick-based accumulation. */
    void setMineralsForTesting(int amount) { this.mineralAccumulator = amount; }

    /** Positions an enemy near the base for combat tests. */
    void spawnEnemyForTesting(UnitType type, Point2d position) {
        int hp = SC2Data.maxHealth(type);
        String tag = "test-enemy-" + nextTag++;
        enemyUnits.add(new Unit(tag, type, position, hp, hp,
            SC2Data.maxShields(type), SC2Data.maxShields(type)));
        enemyTargets.put(tag, NEXUS_POS);
    }

    /** Sets a friendly unit's health for combat threshold tests. */
    void setHealthForTesting(String tag, int health) {
        myUnits.replaceAll(u -> u.tag().equals(tag)
            ? new Unit(u.tag(), u.type(), u.position(), health, u.maxHealth(), u.shields(), u.maxShields())
            : u);
    }

    /** Sets a friendly unit's shields for shield absorption tests. */
    void setShieldsForTesting(String tag, int shields) {
        myUnits.replaceAll(u -> u.tag().equals(tag)
            ? new Unit(u.tag(), u.type(), u.position(), u.health(), u.maxHealth(), shields, u.maxShields())
            : u);
    }

    /** Spawns a friendly unit at a specific position for combat tests. Returns the unit's tag. */
    String spawnFriendlyForTesting(UnitType type, Point2d position) {
        String tag = "test-unit-" + nextTag++;
        int hp = SC2Data.maxHealth(type);
        myUnits.add(new Unit(tag, type, position, hp, hp,
            SC2Data.maxShields(type), SC2Data.maxShields(type)));
        return tag;
    }

    /** Sets an enemy unit's shields directly — for testing Hardened Shield at 0 shields. */
    void setEnemyShieldsForTesting(String tag, int shields) {
        enemyUnits.replaceAll(u -> u.tag().equals(tag)
            ? new Unit(u.tag(), u.type(), u.position(), u.health(), u.maxHealth(),
                       shields, u.maxShields())
            : u);
    }

    /** Returns a copy of retreating unit tags — for E6 retreat assertions. */
    Set<String> retreatingUnitTags() { return Set.copyOf(retreatingUnits); }

    /** Sets initialAttackSize directly — simulates a wave having been launched. */
    void setInitialAttackSizeForTesting(int n) { this.initialAttackSize = n; }

    /** Sets an enemy unit's health directly — for retreat health threshold tests. */
    void setEnemyHealthForTesting(String tag, int health) {
        enemyUnits.replaceAll(u -> u.tag().equals(tag)
            ? new Unit(u.tag(), u.type(), u.position(), health, u.maxHealth(),
                       u.shields(), u.maxShields())
            : u);
    }

    /** Swap movement strategy — used by pathfinding tests. Default is DirectMovement. */
    void setMovementStrategy(MovementStrategy s) { this.movementStrategy = s; }
    void setTerrainGrid(TerrainGrid g) { this.terrainGrid = g; }

    /** Injects a predictable Random for miss-chance tests. */
    void setRandomForTesting(Random r) { this.random = r; }

    /** Adds a unit directly to enemyStagingArea — for fog-of-war staging visibility tests. */
    void addStagedUnitForTesting(UnitType type, Point2d position) {
        String tag = "test-staging-" + nextTag++;
        int hp = SC2Data.maxHealth(type);
        enemyStagingArea.add(new Unit(tag, type, position, hp, hp,
            SC2Data.maxShields(type), SC2Data.maxShields(type)));
    }
}
