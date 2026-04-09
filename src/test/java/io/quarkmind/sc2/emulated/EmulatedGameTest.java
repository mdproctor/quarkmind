package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
        // Zealot deals 5 dmg/tick → shields take hit first (20→15), HP unchanged.
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.health()).isEqualTo(SC2Data.maxHealth(UnitType.PROBE)); // HP untouched
        assertThat(probe.shields()).isLessThan(SC2Data.maxShields(UnitType.PROBE)); // shields hit
    }

    @Test
    void damageOverflowsFromShieldsToHp() {
        // probe-0 with 3 shields. Zealot deals 5 dmg → 3 absorbed by shields, 2 overflow to HP (45→43).
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.setShieldsForTesting("probe-0", 3);
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.shields()).isEqualTo(0);
        assertThat(probe.health()).isEqualTo(SC2Data.maxHealth(UnitType.PROBE) - 2); // 45 - 2 = 43
    }

    @Test
    void unitDiesWhenHpReachesZero() {
        // probe-0 at 3 HP, 0 shields. Zealot deals 5 dmg → HP goes to -2 → unit removed.
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
        // Stalker deals 5 dmg → probe-0 shields: 20→15.
        game.spawnEnemyForTesting(UnitType.STALKER, new Point2d(6f, 9));
        game.tick();

        Unit probe = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals("probe-0")).findFirst().orElseThrow();
        assertThat(probe.shields()).isEqualTo(
            SC2Data.maxShields(UnitType.PROBE) - SC2Data.damagePerTick(UnitType.STALKER));
    }

    @Test
    void combatIsSimultaneous() {
        // probe-0 gets AttackIntent toward enemy → probe attacks enemy too.
        // probe-0 at 5 HP, 0 shields → Zealot's 5 dmg kills it.
        // Zealot has 50 shields; probe deals 3 dmg → shields drop 50→47 (simultaneous resolution).
        // Both damage computations happen before either is applied (simultaneous).
        game.applyIntent(new AttackIntent("probe-0", new Point2d(9.3f, 9)));
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(9.3f, 9));
        game.setHealthForTesting("probe-0", 5);
        game.setShieldsForTesting("probe-0", 0);

        game.tick();

        // probe-0 should be dead (5 HP - 5 dmg = 0)
        assertThat(game.snapshot().myUnits().stream()
            .anyMatch(u -> u.tag().equals("probe-0"))).isFalse();
        // Enemy should still be alive but damaged (shields 50→47 from probe's 3 dmg)
        assertThat(game.snapshot().enemyUnits()).hasSize(1);
        assertThat(game.snapshot().enemyUnits().get(0).shields())
            .isLessThan(SC2Data.maxShields(UnitType.ZEALOT));
    }
}
