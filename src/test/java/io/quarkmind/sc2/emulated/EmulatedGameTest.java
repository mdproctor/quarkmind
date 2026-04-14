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
        // Zealot has 50 shields; probe deals 5 dmg → shields drop 50→45 (simultaneous resolution).
        // Both damage computations happen before either is applied (simultaneous).
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.setHealthForTesting("probe-0", 5);
        game.setShieldsForTesting("probe-0", 0);

        game.tick();

        // probe-0 should be dead (5 HP - 8 dmg → health ≤ 0)
        assertThat(game.snapshot().myUnits().stream()
            .anyMatch(u -> u.tag().equals("probe-0"))).isFalse();
        // Enemy should still be alive but damaged (shields 50→45 from probe's 5 dmg)
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
        assertThat(game.snapshot().enemyUnits().get(0).shields())
            .isLessThan(SC2Data.maxShields(UnitType.ZEALOT));
    }

    // ---- E4: attack cooldowns ----

    @Test
    void firstAttackFiresImmediately() {
        // Initial cooldown = 0 (absent from map) — attack fires on first tick
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));

        game.tick();

        Unit zealot = game.snapshot().enemyUnits().get(0);
        assertThat(zealot.shields())
            .isLessThan(SC2Data.maxShields(UnitType.ZEALOT))
            .isEqualTo(SC2Data.maxShields(UnitType.ZEALOT) - SC2Data.damagePerAttack(UnitType.PROBE));
        // 50 - 5 = 45
    }

    @Test
    void attackCooldownPreventsRepeatOnNextTick() {
        // After attack fires, probe cooldown resets to 2 — no damage on tick 2
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));

        game.tick(); // tick 1: probe fires, cooldown → 2
        int shieldsAfterTick1 = game.snapshot().enemyUnits().get(0).shields(); // 45

        game.tick(); // tick 2: cooldown = 1, probe does NOT fire
        int shieldsAfterTick2 = game.snapshot().enemyUnits().get(0).shields();

        assertThat(shieldsAfterTick2).isEqualTo(shieldsAfterTick1);
    }

    @Test
    void cooldownExpiresAndAttackFiresAgain() {
        // PROBE cooldown = 2: fires tick 1, skips tick 2, fires tick 3
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));

        game.tick(); // tick 1: attack (shields 50→45)
        game.tick(); // tick 2: cooldown 1 — no attack
        game.tick(); // tick 3: cooldown 0 — attack fires again (45→40)

        Unit zealot = game.snapshot().enemyUnits().get(0);
        assertThat(zealot.shields())
            .isEqualTo(SC2Data.maxShields(UnitType.ZEALOT) - 2 * SC2Data.damagePerAttack(UnitType.PROBE));
        // 50 - 2*5 = 40
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
            List.of(), false, 5, new EnemyAttackConfig(10, 9999)));

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
            new EnemyAttackConfig(10, 9999)));

        for (int i = 0; i < 5; i++) game.tick();

        assertThat(game.snapshot().enemyStagingArea()).hasSize(1);
        assertThat(game.snapshot().enemyStagingArea().get(0).type()).isEqualTo(UnitType.ZEALOT);
    }

    @Test
    void enemyDoesNotTrainWhenInsufficientMinerals() {
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            false, 0,   // no mineral income
            new EnemyAttackConfig(10, 9999)));

        for (int i = 0; i < 10; i++) game.tick();

        assertThat(game.snapshot().enemyStagingArea()).isEmpty();
    }

    @Test
    void enemySendsAttackWhenArmyThresholdMet() {
        // threshold=1, 20 minerals/tick → after 5 ticks: 1 Zealot trained → attack fires
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            true, 20,
            new EnemyAttackConfig(1, 9999)));

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
            new EnemyAttackConfig(100, 5)));

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
            new EnemyAttackConfig(3, 5)));

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
            new EnemyAttackConfig(1, 9999)));

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
            new EnemyAttackConfig(10, 9999)));

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
            new EnemyAttackConfig(10, 9999)));

        for (int i = 0; i < 10; i++) game.tick();

        assertThat(game.snapshot().enemyStagingArea()).hasSize(2); // exactly 2, not more
    }

    @Test
    void enemyUnitsStayAtSpawnUntilAttack() {
        // threshold=5 (won't fire with 3 units), timer=9999 — no attack
        game.setEnemyStrategy(new EnemyStrategy(
            List.of(new EnemyBuildStep(UnitType.ZEALOT)),
            true, 100,
            new EnemyAttackConfig(5, 9999)));

        for (int i = 0; i < 3; i++) game.tick();

        game.snapshot().enemyStagingArea().forEach(u ->
            assertThat(u.position()).isEqualTo(new Point2d(26, 26)));
        assertThat(game.snapshot().enemyUnits()).isEmpty();
    }
}
