package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.util.List;

class EmulatedGameTest {

    EmulatedGame game;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.configureWave(9999, 4, UnitType.ZEALOT); // defer wave — doesn't fire in E1 tests
        game.reset();
    }

    // ---- E1 tests (unchanged) ----

    @Test
    void initialMineralsAreFifty() {
        assertThat(game.snapshot().minerals()).isEqualTo(50);
    }

    @Test
    void mineralAccumulatesWithMiningProbes() {
        int ticks = 100;
        for (int i = 0; i < ticks; i++) game.tick();
        double expected = SC2Data.INITIAL_MINERALS + (SC2Data.INITIAL_PROBES * SC2Data.MINERALS_PER_PROBE_PER_TICK * ticks);
        assertThat(game.snapshot().minerals()).isCloseTo((int) expected, within(1));
    }

    @Test
    void zeroProbesYieldsNoMineralGain() {
        game.setMiningProbes(0);
        for (int i = 0; i < 100; i++) game.tick();
        assertThat(game.snapshot().minerals()).isEqualTo(50);
    }

    @Test
    void snapshotFrameDoesNotChangeAfterTick() {
        GameState before = game.snapshot();
        game.tick();
        assertThat(before.gameFrame()).isEqualTo(0L);
        assertThat(game.snapshot().gameFrame()).isEqualTo(1L);
    }

    @Test
    void resetRestoresInitialState() {
        for (int i = 0; i < 100; i++) game.tick();
        game.reset();
        assertThat(game.snapshot().minerals()).isEqualTo(50);
        assertThat(game.snapshot().gameFrame()).isEqualTo(0L);
    }

    @Test
    void moveIntentDoesNotChangeUnitCountOrMinerals() {
        game.applyIntent(new MoveIntent("probe-0", new Point2d(10, 10)));
        assertThat(game.snapshot().minerals()).isEqualTo(50);
        assertThat(game.snapshot().myUnits()).hasSize(12);
    }

    // ---- E2: movement ----

    @Test
    void unitMovesEachTickWhenTargetSet() {
        game.applyIntent(new MoveIntent("probe-0", new Point2d(15, 9)));
        Point2d before = game.snapshot().myUnits().get(0).position();
        game.tick();
        Point2d after = game.snapshot().myUnits().get(0).position();
        assertThat(after.x()).isGreaterThan(before.x()); // moved toward x=15
    }

    @Test
    void unitArrivesAtTarget() {
        // probe-0 starts at (9, 9), target at (9.1, 9) — speed 0.5 overshoots, snaps to target
        game.applyIntent(new MoveIntent("probe-0", new Point2d(9.1f, 9)));
        game.tick();
        Point2d pos = game.snapshot().myUnits().get(0).position();
        assertThat(pos.x()).isCloseTo(9.1f, within(0.01f));
    }

    @Test
    void attackIntentSetsMovementTarget() {
        game.applyIntent(new AttackIntent("probe-0", new Point2d(20, 20)));
        Point2d before = game.snapshot().myUnits().get(0).position();
        game.tick();
        Point2d after = game.snapshot().myUnits().get(0).position();
        assertThat(after.x()).isGreaterThan(before.x());
        assertThat(after.y()).isGreaterThan(before.y());
    }

    @Test
    void stepTowardHelperMovesCorrectDistance() {
        Point2d from   = new Point2d(0, 0);
        Point2d to     = new Point2d(10, 0);
        Point2d result = EmulatedGame.stepToward(from, to, 0.5);
        assertThat(result.x()).isCloseTo(0.5f, within(0.001f));
        assertThat(result.y()).isCloseTo(0f,   within(0.001f));
    }

    @Test
    void stepTowardHelperSnapsToTargetWhenCloseEnough() {
        Point2d from   = new Point2d(0, 0);
        Point2d to     = new Point2d(0.3f, 0);
        Point2d result = EmulatedGame.stepToward(from, to, 0.5); // speed > distance
        assertThat(result.x()).isEqualTo(to.x());
        assertThat(result.y()).isEqualTo(to.y());
    }

    // ---- E2: enemy wave ----

    @Test
    void enemySpawnsAtConfiguredFrame() {
        game.configureWave(5, 2, UnitType.ZEALOT);
        game.reset();
        assertThat(game.snapshot().enemyUnits()).isEmpty();
        for (int i = 0; i < 5; i++) game.tick();
        assertThat(game.snapshot().enemyUnits()).hasSize(2);
        assertThat(game.snapshot().enemyUnits().get(0).type()).isEqualTo(UnitType.ZEALOT);
    }

    @Test
    void enemyMovesEachTickTowardNexus() {
        game.configureWave(1, 1, UnitType.ZEALOT);
        game.reset();
        game.tick(); // frame 1 — wave spawns at (26, 26)
        Point2d spawnPos = game.snapshot().enemyUnits().get(0).position();
        game.tick(); // frame 2 — enemy moves toward (8, 8)
        Point2d afterPos = game.snapshot().enemyUnits().get(0).position();
        assertThat(afterPos.x()).isLessThan(spawnPos.x());
        assertThat(afterPos.y()).isLessThan(spawnPos.y());
    }

    // ---- E2: train intent ----

    @Test
    void trainIntentDeductsMinerals() {
        game.setMineralsForTesting(200);
        game.applyIntent(new TrainIntent("nexus-0", UnitType.ZEALOT));
        assertThat(game.snapshot().minerals()).isEqualTo(100); // 200 - 100
    }

    @Test
    void trainedUnitAppearsAfterBuildTime() {
        game.setMineralsForTesting(500);
        game.applyIntent(new TrainIntent("nexus-0", UnitType.ZEALOT));
        int before = game.snapshot().myUnits().size();
        for (int i = 0; i < 28; i++) game.tick(); // Zealot = 28 ticks
        assertThat(game.snapshot().myUnits()).hasSize(before + 1);
    }

    @Test
    void trainBlockedIfInsufficientMinerals() {
        // 50 minerals, Zealot costs 100 — blocked
        game.applyIntent(new TrainIntent("nexus-0", UnitType.ZEALOT));
        assertThat(game.snapshot().minerals()).isEqualTo(50); // unchanged
    }

    // ---- E2: build intent ----

    @Test
    void buildIntentDeductsMinerals() {
        game.setMineralsForTesting(500);
        game.applyIntent(new BuildIntent("probe-0", BuildingType.PYLON, new Point2d(15, 15)));
        assertThat(game.snapshot().minerals()).isEqualTo(400); // 500 - 100
    }

    @Test
    void buildingCompletesAfterBuildTime() {
        game.setMineralsForTesting(500);
        game.applyIntent(new BuildIntent("probe-0", BuildingType.PYLON, new Point2d(15, 15)));
        int supplyBefore = game.snapshot().supply();
        for (int i = 0; i < 18; i++) game.tick(); // Pylon = 18 ticks
        assertThat(game.snapshot().supply()).isEqualTo(supplyBefore + 8); // +8 from Pylon
    }

    // ---- E3: combat ----

    @Test
    void shieldsAbsorbDamageBeforeHp() {
        // probe-0 at (9,9) with 20 shields. Enemy Zealot at (9.3,9) — within 0.5-tile melee range.
        // Zealot deals 8 dmg/attack → shields take hit first (20→12), HP unchanged.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.health()).isEqualTo(SC2Data.maxHealth(UnitType.PROBE)); // HP untouched
        assertThat(probe.shields()).isLessThan(SC2Data.maxShields(UnitType.PROBE)); // shields hit
    }

    @Test
    void damageOverflowsFromShieldsToHp() {
        // probe-0 with 3 shields. Zealot deals 8 dmg → 3 absorbed by shields, 5 overflow to HP (45→40).
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.setShieldsForTesting("probe-0", 3);
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.shields()).isEqualTo(0);
        assertThat(probe.health()).isEqualTo(SC2Data.maxHealth(UnitType.PROBE) - 5); // 45 - 5 = 40
        // Zealot damagePerAttack=8: 3 shields absorb, 5 overflow to HP
    }

    @Test
    void unitDiesWhenHpReachesZero() {
        // probe-0 at 3 HP, 0 shields. Zealot deals 8 dmg → HP goes to -5 → unit removed.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.setHealthForTesting("probe-0", 3);
        game.setShieldsForTesting("probe-0", 0);
        int before = game.snapshot().myUnits().size();

        game.tick();

        assertThat(game.snapshot().myUnits()).hasSize(before - 1);
        assertThat(game.snapshot().myUnits().stream()
            .anyMatch(u -> u.tag().equals("probe-0"))).isFalse();
    }

    @Test
    void unitOutsideAttackRangeNotDamaged() {
        // Zealot melee range = 0.5 tiles. Place at 1.5 tiles away → out of range.
        // probe-0 is at (9,9). Enemy at (10.5,9) → distance=1.5 > 0.5 → no attack.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(10.5f, 9));
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.shields()).isEqualTo(SC2Data.maxShields(UnitType.PROBE)); // untouched
        assertThat(probe.health()).isEqualTo(SC2Data.maxHealth(UnitType.PROBE));   // untouched
    }

    @Test
    void unitInsideAttackRangeReceivesDamage() {
        // Stalker range = 5 tiles. Place at 3 tiles away from probe-0 → in range.
        // probe-0 at (9,9). Stalker placed at (6,9) → distance=3 ≤ 5 → attacks.
        // Stalker range = 5 tiles. Stalker at (6,9). Probes 0–4 are in range (distances 3.0–5.0),
        // but probe-0 at distance 3.0 is the nearest so it is the sole target.
        // Stalker deals 13 dmg → probe-0 shields: 20→7.
        game.spawnEnemyForTesting(UnitType.STALKER, new Point2d(6f, 9));
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.shields()).isEqualTo(
            SC2Data.maxShields(UnitType.PROBE) - SC2Data.damagePerAttack(UnitType.STALKER));
        // 20 - 13 = 7
    }

    @Test
    void combatIsSimultaneous() {
        // probe-0 gets AttackIntent toward enemy → probe attacks enemy too.
        // probe-0 at 5 HP, 0 shields → Zealot's 8 dmg kills it.
        // Zealot has 50 shields; probe deals 4 dmg (5 raw - 1 armour) → shields drop 50→46 (simultaneous resolution).
        // Both damage computations happen before either is applied (simultaneous).
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.setHealthForTesting("probe-0", 5);
        game.setShieldsForTesting("probe-0", 0);

        game.tick();

        // probe-0 should be dead (5 HP - 8 dmg → health ≤ 0)
        assertThat(game.snapshot().myUnits().stream()
            .anyMatch(u -> u.tag().equals("probe-0"))).isFalse();
        // Enemy should still be alive but damaged (shields 50→46 from probe's 4 effective dmg)
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
        assertThat(game.snapshot().enemyUnits().get(0).shields())
            .isLessThan(SC2Data.maxShields(UnitType.ZEALOT));
    }

    // ---- E4: attack cooldowns ----

    @Test
    void firstAttackFiresImmediately() {
        // Initial cooldown = 0 (absent from map) — attack fires on first tick.
        // Probe vs Zealot (1 armour): effective = 5 - 1 = 4. Shields: 50 -> 46.
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));

        game.tick();

        int effective = SC2Data.damagePerAttack(UnitType.PROBE) - SC2Data.armour(UnitType.ZEALOT); // 4
        Unit zealot = game.snapshot().enemyUnits().get(0);
        assertThat(zealot.shields())
            .isLessThan(SC2Data.maxShields(UnitType.ZEALOT))
            .isEqualTo(SC2Data.maxShields(UnitType.ZEALOT) - effective); // 46
    }

    @Test
    void attackCooldownPreventsRepeatOnNextTick() {
        // After attack fires, probe cooldown resets to 2 — no damage on tick 2
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));

        game.tick(); // tick 1: probe fires, cooldown → 2
        int shieldsAfterTick1 = game.snapshot().enemyUnits().get(0).shields(); // 46

        game.tick(); // tick 2: cooldown = 1, probe does NOT fire
        int shieldsAfterTick2 = game.snapshot().enemyUnits().get(0).shields();

        assertThat(shieldsAfterTick2).isEqualTo(shieldsAfterTick1);
    }

    @Test
    void cooldownExpiresAndAttackFiresAgain() {
        // PROBE cooldown = 2: fires tick 1, skips tick 2, fires tick 3.
        // Probe vs Zealot (1 armour): effective = 5 - 1 = 4 per hit. Shields: 50 -> 46 -> 42.
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));

        game.tick(); // tick 1: attack (shields 50 -> 46)
        game.tick(); // tick 2: cooldown 1 - no attack
        game.tick(); // tick 3: cooldown 0 - attack fires again (46 -> 42)

        int effective = SC2Data.damagePerAttack(UnitType.PROBE) - SC2Data.armour(UnitType.ZEALOT); // 4
        Unit zealot = game.snapshot().enemyUnits().get(0);
        assertThat(zealot.shields())
            .isEqualTo(SC2Data.maxShields(UnitType.ZEALOT) - 2 * effective); // 42
    }

    @Test
    void moveIntentCancelsAutoAttack() {
        // AttackIntent adds to attackingUnits; MoveIntent removes it immediately
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.applyIntent(new MoveIntent("probe-0", new Point2d(9.3f, 9))); // cancel

        // Run enough ticks for cooldown to cycle — probe should never attack
        for (int i = 0; i < 5; i++) game.tick();

        Unit zealot = game.snapshot().enemyUnits().get(0);
        // Probe-0 never attacked — Zealot shields untouched by probe
        assertThat(zealot.shields()).isEqualTo(SC2Data.maxShields(UnitType.ZEALOT));
    }

    @Test
    void enemyAlwaysAttacksWithCooldown() {
        // Enemy Zealot (cooldown=2) attacks probe every 2 ticks without AttackIntent
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));

        game.tick(); // tick 1: Zealot fires (cooldown 0→2), probe shields: 20→12
        int shieldsAfterTick1 = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow().shields();
        assertThat(shieldsAfterTick1)
            .isEqualTo(SC2Data.maxShields(UnitType.PROBE) - SC2Data.damagePerAttack(UnitType.ZEALOT));
        // 20 - 8 = 12

        game.tick(); // tick 2: Zealot cooldown 1 — no attack. shields unchanged.
        int shieldsAfterTick2 = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow().shields();
        assertThat(shieldsAfterTick2).isEqualTo(shieldsAfterTick1);
    }

    // ---- E4: enemy economy ----

    @Test
    void enemyStrategyNullIsNoop() {
        game.setEnemyStrategy(null);
        game.tick(); // must not throw NullPointerException
        assertThat(game.snapshot().enemyStagingArea()).isEmpty();
    }

    @Test
    void enemyAccumulatesMineralsEachTick() {
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(), false, 5, new EnemyAttackConfig(10, 9999, 0, 0)));

        game.tick();
        assertThat(game.enemyMinerals()).isEqualTo(5);

        game.tick();
        assertThat(game.enemyMinerals()).isEqualTo(10);
    }

    @Test
    void enemyTrainsUnitWhenMineralsAfford() {
        // 20 minerals/tick, Zealot costs 100 → trains after 5 ticks
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            false, 20,
            new EnemyAttackConfig(10, 9999, 0, 0)));

        for (int i = 0; i < 5; i++) game.tick();

        assertThat(game.snapshot().enemyStagingArea()).hasSize(1);
        assertThat(game.snapshot().enemyStagingArea().get(0).type()).isEqualTo(UnitType.ZEALOT);
    }

    @Test
    void enemyDoesNotTrainWhenInsufficientMinerals() {
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            false, 0,   // no mineral income
            new EnemyAttackConfig(10, 9999, 0, 0)));

        for (int i = 0; i < 10; i++) game.tick();

        assertThat(game.snapshot().enemyStagingArea()).isEmpty();
    }

    @Test
    void enemySendsAttackWhenArmyThresholdMet() {
        // threshold=1, 20 minerals/tick → after 5 ticks: 1 Zealot trained → attack fires
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            true, 20,
            new EnemyAttackConfig(1, 9999, 0, 0)));

        for (int i = 0; i < 5; i++) game.tick();

        assertThat(game.snapshot().enemyStagingArea()).isEmpty();  // cleared — attack sent
        assertThat(game.snapshot().enemyUnits()).isNotEmpty();
        assertThat(game.snapshot().enemyUnits().get(0).type()).isEqualTo(UnitType.ZEALOT);
    }

    @Test
    void enemySendsAttackWhenTimerFires() {
        // threshold=100 (never), timer=5 frames. 10 minerals/tick, Zealot(100) trains at tick 10.
        // At tick 5: staging empty → no attack (timer fires but guard prevents it).
        // At tick 10: Zealot trained, framesSinceAttack=10 >= 5 → timer fires → attack.
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            true, 10,
            new EnemyAttackConfig(100, 5, 0, 0)));

        for (int i = 0; i < 5; i++) game.tick();
        assertThat(game.snapshot().enemyUnits()).isEmpty(); // timer fired but staging empty → no attack

        for (int i = 0; i < 5; i++) game.tick(); // tick 10: Zealot trained, timer fires → attack
        assertThat(game.snapshot().enemyStagingArea()).isEmpty();
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
    }

    @Test
    void timerFiresBeforeArmyThreshold() {
        // threshold=3, timer=5. 25 minerals/tick, Zealot(100) trains at tick 4.
        // At tick 4: 1 unit in staging, threshold not met (3), timer not fired (4 < 5).
        // At tick 5: minerals=25 remaining, can't afford 2nd Zealot yet.
        //   framesSinceAttack=5 >= 5 → timer fires with 1 unit in staging → attack.
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            true, 25,
            new EnemyAttackConfig(3, 5, 0, 0)));

        for (int i = 0; i < 4; i++) game.tick();
        assertThat(game.snapshot().enemyStagingArea()).hasSize(1); // training done, attack not yet

        game.tick(); // frame 5: timer fires → attack sent
        assertThat(game.snapshot().enemyStagingArea()).isEmpty();
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
    }

    @Test
    void enemyStagingClearedAfterAttack() {
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            true, 20,
            new EnemyAttackConfig(1, 9999, 0, 0)));

        for (int i = 0; i < 5; i++) game.tick(); // trains 1 Zealot → threshold=1 → attack fires

        assertThat(game.snapshot().enemyStagingArea()).isEmpty();
    }

    @Test
    void enemyBuildOrderLoops() {
        // 2-step order [Zealot, Stalker] with loop=true, 125 minerals/tick
        // tick 1: +125 → train Zealot(100). minerals=25. staging=[Z].
        // tick 2: +125=150 → train Stalker(125). minerals=25. staging=[Z,S].
        // tick 3: +125=150 → loop: train Zealot(100). minerals=50. staging=[Z,S,Z].
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT), new EnemyBuildStep(UnitType.STALKER)),
            true, 125,
            new EnemyAttackConfig(10, 9999, 0, 0)));

        game.tick();
        assertThat(game.snapshot().enemyStagingArea()).hasSize(1);
        assertThat(game.snapshot().enemyStagingArea().get(0).type()).isEqualTo(UnitType.ZEALOT);

        game.tick();
        assertThat(game.snapshot().enemyStagingArea()).hasSize(2);
        assertThat(game.snapshot().enemyStagingArea().get(1).type()).isEqualTo(UnitType.STALKER);

        game.tick();
        assertThat(game.snapshot().enemyStagingArea()).hasSize(3);
        assertThat(game.snapshot().enemyStagingArea().get(2).type()).isEqualTo(UnitType.ZEALOT); // looped
    }

    @Test
    void enemyBuildOrderStopsWhenExhausted() {
        // loop=false, 2-step order, 100 minerals/tick → exactly 2 units, then stops
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT), new EnemyBuildStep(UnitType.ZEALOT)),
            false, 100,
            new EnemyAttackConfig(10, 9999, 0, 0)));

        for (int i = 0; i < 10; i++) game.tick();

        assertThat(game.snapshot().enemyStagingArea()).hasSize(2); // exactly 2, not more
    }

    @Test
    void enemyUnitsStayAtSpawnUntilAttack() {
        // threshold=5 (won't fire with 3 units), timer=9999 — no attack
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            true, 100,
            new EnemyAttackConfig(5, 9999, 0, 0)));

        for (int i = 0; i < 3; i++) game.tick();

        game.snapshot().enemyStagingArea().forEach(u ->
            assertThat(u.position()).isEqualTo(new Point2d(26, 26)));
        assertThat(game.snapshot().enemyUnits()).isEmpty();
    }

    // ---- E5: damage types, armour, Hardened Shield ----

    @Test
    void stalkerDealsCorrectDamageVsArmored() {
        // Stalker (friendly) attacks Roach (Armored, 1 armour):
        // effective = 13 + 4 (vs Armored) - 1 = 16, not raw 13
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(3, 3));
        game.spawnEnemyForTesting(UnitType.ROACH, new Point2d(3, 5)); // distance=2.0 <= Stalker range 5.0
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(3, 5)));

        game.tick();

        Unit roach = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.ROACH)
            .findFirst().orElseThrow();
        assertThat(roach.health()).isEqualTo(SC2Data.maxHealth(UnitType.ROACH) - 16); // 145 - 16 = 129
    }

    @Test
    void armourReducesIncomingDamage() {
        // Stalker (friendly) attacks Zealot (LIGHT, 1 armour):
        // effective = 13 + 0 (no bonus vs Light) - 1 = 12, not raw 13
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(3, 3));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(3, 5));
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(3, 5)));

        game.tick();

        Unit zealot = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.ZEALOT)
            .findFirst().orElseThrow();
        // Without armour: shields = 50 - 13 = 37. With armour: 50 - 12 = 38.
        assertThat(zealot.shields()).isEqualTo(SC2Data.maxShields(UnitType.ZEALOT) - 12); // 38
    }

    @Test
    void immortalShieldedCapsDamageAt10() {
        // Stalker (friendly) attacks shielded Immortal:
        // effective before cap = 13+4-1=16, Hardened Shield -> min(10,16) = 10
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(3, 3));
        game.spawnEnemyForTesting(UnitType.IMMORTAL, new Point2d(3, 5));
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(3, 5)));

        game.tick();

        Unit immortal = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.IMMORTAL)
            .findFirst().orElseThrow();
        assertThat(immortal.shields()).isEqualTo(SC2Data.maxShields(UnitType.IMMORTAL) - 10); // 90
        assertThat(immortal.health()).isEqualTo(SC2Data.maxHealth(UnitType.IMMORTAL));         // 200 untouched
    }

    @Test
    void immortalUnshieldedTakesFullDamage() {
        // Stalker vs Immortal with 0 shields: no Hardened Shield -> full 16 damage to HP
        String stalkerTag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(3, 3));
        game.spawnEnemyForTesting(UnitType.IMMORTAL, new Point2d(3, 5));
        String immortalTag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyShieldsForTesting(immortalTag, 0);
        game.applyIntent(new AttackIntent(stalkerTag, new Point2d(3, 5)));

        game.tick();

        Unit immortal = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.IMMORTAL)
            .findFirst().orElseThrow();
        assertThat(immortal.health()).isEqualTo(SC2Data.maxHealth(UnitType.IMMORTAL) - 16); // 184
    }

    @Test
    void spawnedMarineHasCorrectHp() {
        game.spawnEnemyForTesting(UnitType.MARINE, new Point2d(50, 50)); // far from Probes - no combat
        Unit marine = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.MARINE)
            .findFirst().orElseThrow();
        assertThat(marine.health()).isEqualTo(45);
    }

    @Test
    void spawnedImmortalHasCorrectHp() {
        game.spawnEnemyForTesting(UnitType.IMMORTAL, new Point2d(50, 50));
        Unit immortal = game.snapshot().enemyUnits().stream()
            .filter(u -> u.type() == UnitType.IMMORTAL)
            .findFirst().orElseThrow();
        assertThat(immortal.health()).isEqualTo(200);
    }

    // ---- E6: retreat infrastructure ----

    @Test
    void retreatingUnitTagsIsInitiallyEmpty() {
        assertThat(game.retreatingUnitTags()).isEmpty();
    }

    // ---- E6: retreat logic ----

    @Test
    void lowHealthUnitRetreats() {
        // Zealot HP+shields = 1+0 / 150 = 0.7% — below retreatHealthPercent=30
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick();

        assertThat(game.retreatingUnitTags()).contains(tag);
    }

    @Test
    void healthyUnitDoesNotRetreat() {
        // Zealot at full HP+shields = 150/150 = 100% — well above retreatHealthPercent=30
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        game.setInitialAttackSizeForTesting(1);

        game.tick();

        assertThat(game.retreatingUnitTags()).isEmpty();
    }

    @Test
    void armyDepletionTriggersGroupRetreat() {
        // 1 unit alive of 4 launched = 25% — below retreatArmyPercent=50
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 0, 50);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setInitialAttackSizeForTesting(4); // 1 alive of 4 launched = 25% < 50%

        game.tick();

        assertThat(game.retreatingUnitTags()).contains(tag);
    }

    @Test
    void retreatingUnitMovesTowardStaging() {
        // Tick 1: retreat fires → target becomes STAGING_POS (26,26)
        // Tick 2: unit moves toward staging — measurably closer than after tick 1
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick(); // retreat fires; unit moved toward nexus first, now target = staging
        Point2d afterTick1 = game.snapshot().enemyUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow().position();

        game.tick(); // unit moves toward staging
        Point2d afterTick2 = game.snapshot().enemyUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow().position();

        double distBefore = EmulatedGame.distance(afterTick1, new Point2d(26, 26));
        double distAfter  = EmulatedGame.distance(afterTick2, new Point2d(26, 26));
        assertThat(distAfter).isLessThan(distBefore);
    }

    @Test
    void retreatingUnitTransfersToStagingOnArrival() {
        // Unit placed exactly at STAGING_POS.
        // Tick 1: moveEnemyUnits moves it ~0.5 tiles toward nexus. retreat fires.
        //         Transfer check: distance ~0.5 >= 0.1 → NOT yet transferred.
        // Tick 2: moveEnemyUnits moves it back toward STAGING_POS (dist ~0.5 = speed → snaps to (26,26)).
        //         Transfer check: distance = 0 < 0.1 → TRANSFERRED.
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(26, 26)); // at staging
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick(); // retreat fires; not yet transferred
        assertThat(game.retreatingUnitTags()).contains(tag);

        game.tick(); // unit snaps to staging; transfer fires
        assertThat(game.snapshot().enemyUnits().stream()
            .anyMatch(u -> u.tag().equals(tag))).isFalse();
        assertThat(game.snapshot().enemyStagingArea()).hasSize(1);
        assertThat(game.snapshot().enemyStagingArea().get(0).tag()).isEqualTo(tag);
    }

    @Test
    void retreatedUnitKeepsDamagedHp() {
        // Zealot: 40+0 / 150 = 26.7% < 30% → retreats. HP preserved at 40 in staging.
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(26, 26)); // at staging
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 40);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick(); // retreat fires; not yet transferred (moved toward nexus)
        game.tick(); // snaps back to staging; transfer fires

        assertThat(game.snapshot().enemyStagingArea()).hasSize(1);
        assertThat(game.snapshot().enemyStagingArea().get(0).health()).isEqualTo(40);
    }

    @Test
    void disabledThresholdsNeverRetreat() {
        // Both thresholds = 0 → no retreat regardless of HP
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 0, 0);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        game.setInitialAttackSizeForTesting(1);

        game.tick();

        assertThat(game.retreatingUnitTags()).isEmpty();
    }

    @Test
    void retreatDoesNotFireBeforeFirstAttack() {
        // initialAttackSize = 0 (no wave launched) — guard prevents any retreat
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 50);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0);
        // DO NOT call setInitialAttackSizeForTesting — leave at 0

        game.tick();

        assertThat(game.retreatingUnitTags()).isEmpty();
    }

    @Test
    void deadUnitRemovedFromRetreatingSet() {
        // Probe vs Zealot: 5−1(armour)=4 effective damage.
        // Tick 1: probe fires, Zealot HP=5-4=1 → survives. Retreat fires (1/150<30%). In retreatingUnits.
        // Tick 2: probe cooldown=1, no fire. Zealot survives.
        // Tick 3: probe fires again. Zealot HP=1-4<0, dies. resolveCombat cleans retreatingUnits.
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 0);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 5);
        game.setEnemyShieldsForTesting(tag, 0); // 5/150=3.3% < 30% — retreats
        game.setInitialAttackSizeForTesting(1);
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));

        game.tick(); // probe deals 4 dmg → Zealot HP=1; retreat fires
        assertThat(game.retreatingUnitTags()).contains(tag);

        game.tick(); // probe cooldown=1, no fire; Zealot still alive
        assertThat(game.retreatingUnitTags()).contains(tag);

        game.tick(); // probe fires again → Zealot HP<0, dies; retreatingUnits cleaned
        assertThat(game.retreatingUnitTags()).doesNotContain(tag);
        assertThat(game.snapshot().enemyUnits().stream()
            .anyMatch(u -> u.tag().equals(tag))).isFalse();
    }

    @Test
    void alreadyRetreatingUnitSkippedByArmyCheck() {
        // Per-unit threshold fires first → unit added to retreatingUnits with target = STAGING_POS.
        // Army-wide check then fires (1/4 = 25% < 50%). The already-retreating unit must be
        // skipped — its target must NOT be overwritten, and it must not be double-added.
        EnemyAttackConfig atk = new EnemyAttackConfig(10, 9999, 30, 50);
        game.setEnemyStrategy(new EnemyStrategy(List.of(), false, 0, atk));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(14, 14));
        String tag = game.snapshot().enemyUnits().get(0).tag();
        game.setEnemyHealthForTesting(tag, 1);
        game.setEnemyShieldsForTesting(tag, 0); // 1/150 < 30% → per-unit fires
        game.setInitialAttackSizeForTesting(4); // 1/4 = 25% < 50% → army check also fires

        game.tick();

        // Unit must be in retreatingUnits exactly once (Set guarantees this)
        // and target must be STAGING_POS (set by per-unit check, not overwritten by army check)
        assertThat(game.retreatingUnitTags()).containsExactly(tag);
        // enemyUnits still contains the unit (not yet arrived at staging)
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
    }

    // ---- E7: pathfinding integration ----

    @Test
    void withPathfinding_unitEventuallyReachesTargetAcrossWall() {
        game.setMovementStrategy(new PathfindingMovement(WalkabilityGrid.emulatedMap()));
        // From nexus side (8,8) to staging side (12,22) — must cross wall at y=18 via chokepoint
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(8, 8));
        game.applyIntent(new MoveIntent(tag, new Point2d(12, 22)));
        // ~30 tiles / 0.5 per tick = ~60 ticks; use 120 for safety
        for (int i = 0; i < 120; i++) game.tick();
        Unit unit = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag)).findFirst().orElseThrow();
        assertThat(EmulatedGame.distance(unit.position(), new Point2d(12, 22))).isLessThan(2.0);
    }

    @Test
    void withPathfinding_unitDoesNotCrossWallOutsideChokepoint() {
        game.setMovementStrategy(new PathfindingMovement(WalkabilityGrid.emulatedMap()));
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(8, 8));
        game.applyIntent(new MoveIntent(tag, new Point2d(12, 22)));
        for (int i = 0; i < 80; i++) {
            game.tick();
            game.snapshot().myUnits().stream()
                .filter(u -> u.tag().equals(tag)).findFirst().ifPresent(unit -> {
                    int tileX = (int) unit.position().x();
                    int tileY = (int) unit.position().y();
                    if (tileY == 18) {
                        assertThat(tileX)
                            .as("unit at y=18 must be in gap x=[11,13], was x=%d", tileX)
                            .isBetween(11, 13);
                    }
                });
        }
    }

    @Test
    void directMovementDefaultIsUnchanged() {
        // No setMovementStrategy call — defaults to DirectMovement (straight line)
        game.applyIntent(new MoveIntent("probe-0", new Point2d(15, 9)));
        Point2d before = game.snapshot().myUnits().get(0).position();
        game.tick();
        Point2d after = game.snapshot().myUnits().get(0).position();
        assertThat(after.x()).isGreaterThan(before.x());
        assertThat(after.y()).isCloseTo(before.y(), org.assertj.core.data.Offset.offset(0.1f));
    }
}
