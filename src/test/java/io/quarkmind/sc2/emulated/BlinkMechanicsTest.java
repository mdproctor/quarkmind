package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.BlinkIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlinkMechanicsTest {

    EmulatedGame game;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.configureWave(9999, 1, UnitType.ZEALOT); // defer wave
        game.reset();
        // Spawn one Stalker for blink tests (default reset gives probes)
        game.spawnFriendlyUnitForTesting(UnitType.STALKER, new Point2d(10.0f, 10.0f));
    }

    private Unit stalker() {
        return game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.STALKER)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No Stalker found"));
    }

    @Test
    void blinkSetsBlinkCooldownInSnapshot() {
        String tag = stalker().tag();
        game.applyIntent(new BlinkIntent(tag));
        assertThat(stalker().blinkCooldownTicks())
            .isEqualTo(SC2Data.blinkCooldownInTicks(UnitType.STALKER)); // 21
    }

    @Test
    void blinkRestoresShieldsCappedAtMaxShields() {
        // 50 shields + 40 restore = 90, capped at maxShields(STALKER) = 80
        String tag = stalker().tag();
        game.setShieldsForTesting(tag, 50);
        game.applyIntent(new BlinkIntent(tag));
        assertThat(stalker().shields()).isEqualTo(SC2Data.maxShields(UnitType.STALKER));
    }

    @Test
    void blinkRestoresPartialShieldsWhenRoomExists() {
        // 10 shields + 40 restore = 50, under 80 max → gets full 40
        String tag = stalker().tag();
        game.setShieldsForTesting(tag, 10);
        game.applyIntent(new BlinkIntent(tag));
        assertThat(stalker().shields()).isEqualTo(50);
    }

    @Test
    void blinkCooldownDecrementsEachTick() {
        String tag = stalker().tag();
        game.applyIntent(new BlinkIntent(tag));
        assertThat(stalker().blinkCooldownTicks()).isEqualTo(21);
        game.tick();
        assertThat(stalker().blinkCooldownTicks()).isEqualTo(20);
        game.tick();
        assertThat(stalker().blinkCooldownTicks()).isEqualTo(19);
    }

    @Test
    void blinkMovesUnitAtLeastBlinkRangeFromStart() {
        // Need an enemy so blinkRetreatTarget has a direction
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(10.0f, 10.3f));
        String tag = stalker().tag();
        Point2d before = stalker().position();
        game.applyIntent(new BlinkIntent(tag));
        Point2d after = stalker().position();
        double dx = after.x() - before.x();
        double dy = after.y() - before.y();
        double dist = Math.sqrt(dx * dx + dy * dy);
        assertThat(dist).isGreaterThanOrEqualTo(SC2Data.blinkRange(UnitType.STALKER) - 0.01);
    }

    @Test
    void blinkCancelsAttackMode() {
        // Place enemy adjacent so the stalker is in attack range (5 tiles)
        game.spawnEnemyForTesting(UnitType.ZEALOT, new Point2d(10.0f, 10.3f));
        String tag = stalker().tag();
        // Blink — moves unit 8 tiles away, out of range, attack mode cleared
        game.applyIntent(new BlinkIntent(tag));
        // Tick — stalker is now 8 tiles away from the zealot (range 5); should not deal damage
        int enemyHpBefore = game.snapshot().enemyUnits().get(0).health() +
                            game.snapshot().enemyUnits().get(0).shields();
        game.tick();
        int enemyHpAfter = game.snapshot().enemyUnits().isEmpty() ? 0 :
            game.snapshot().enemyUnits().get(0).health() +
            game.snapshot().enemyUnits().get(0).shields();
        // Enemy health unchanged — stalker out of range after blink
        assertThat(enemyHpAfter).isEqualTo(enemyHpBefore);
    }
}
