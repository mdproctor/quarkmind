package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Point2d;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DroolsScoutingTask pure-logic methods.
 * Same package as production class to access package-private static helpers.
 */
class DroolsScoutingTaskTest {

    // ---- estimatedEnemyBase: SC2 map (256x256) ----

    @Test
    void estimatedEnemyBase_sc2Map_lowerLeftBase_returnsUpperRightCorner() {
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 256))
                .isEqualTo(new Point2d(224, 224));
    }

    @Test
    void estimatedEnemyBase_sc2Map_midBase_returnsUpperRightCorner() {
        // Threshold is mapWidth/4 = 64; base at (50,50) is in lower-left zone
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(50, 50), 256))
                .isEqualTo(new Point2d(224, 224));
    }

    @Test
    void estimatedEnemyBase_sc2Map_upperRightBase_returnsLowerLeftCorner() {
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(200, 200), 256))
                .isEqualTo(new Point2d(32, 32));
    }

    @Test
    void estimatedEnemyBase_sc2Map_aboveThresholdBase_returnsLowerLeftCorner() {
        // Equivalent to BasicScoutingTask's (100,100) → (32,32) case; threshold is 64
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(100, 100), 256))
                .isEqualTo(new Point2d(32, 32));
    }

    // ---- estimatedEnemyBase: emulated map (64x64) ----

    @Test
    void estimatedEnemyBase_emulatedMap_lowerLeftBase_returnsUpperRightCorner() {
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 64))
                .isEqualTo(new Point2d(56, 56));
    }

    @Test
    void estimatedEnemyBase_emulatedMap_result_isWithinMapBounds() {
        // The bug: old code returned (224,224) which the engine clamped to (63,63)
        Point2d result = DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 64);
        assertThat(result.x()).isLessThan(64).isGreaterThan(0);
        assertThat(result.y()).isLessThan(64).isGreaterThan(0);
    }

    @Test
    void estimatedEnemyBase_emulatedMap_result_isNotOldClampedValue() {
        // Explicit regression against the old wrong value
        Point2d result = DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 64);
        assertThat(result).isNotEqualTo(new Point2d(63, 63));
        assertThat(result).isNotEqualTo(new Point2d(224, 224));
    }
}
