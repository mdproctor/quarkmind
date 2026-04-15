package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class PathfindingMovementTest {

    private WalkabilityGrid open() {
        boolean[][] g = new boolean[32][32];
        for (boolean[] col : g) Arrays.fill(col, true);
        return new WalkabilityGrid(32, 32, g);
    }

    @Test void unitMovesTowardFirstWaypoint() {
        PathfindingMovement pm = new PathfindingMovement(open());
        Point2d start  = new Point2d(2f, 2f);
        Point2d target = new Point2d(20f, 20f);
        Point2d after  = pm.advance("u1", start, target, 0.5);
        assertThat(EmulatedGame.distance(after, target))
            .isLessThan(EmulatedGame.distance(start, target));
    }

    @Test void unitArrivalAfterManyTicks() {
        PathfindingMovement pm = new PathfindingMovement(open());
        Point2d target = new Point2d(10f, 10f);
        Point2d pos = new Point2d(2f, 2f);
        for (int i = 0; i < 100; i++) {
            pos = pm.advance("u1", pos, target, 0.5);
            if (EmulatedGame.distance(pos, target) < 1.0) break;
        }
        assertThat(EmulatedGame.distance(pos, target)).isLessThan(1.0);
    }

    @Test void targetChangeClearsOldPath() {
        PathfindingMovement pm = new PathfindingMovement(open());
        Point2d target1 = new Point2d(20f, 2f);
        Point2d target2 = new Point2d(2f, 20f);
        Point2d pos = new Point2d(2f, 2f);
        for (int i = 0; i < 4; i++) pos = pm.advance("u1", pos, target1, 0.5);
        double distBefore = EmulatedGame.distance(pos, target2);
        Point2d next = pm.advance("u1", pos, target2, 0.5);
        assertThat(EmulatedGame.distance(next, target2)).isLessThanOrEqualTo(distBefore);
    }

    @Test void clearUnitAllowsCleanRestart() {
        PathfindingMovement pm = new PathfindingMovement(open());
        Point2d target = new Point2d(20f, 20f);
        Point2d pos = new Point2d(2f, 2f);
        for (int i = 0; i < 5; i++) pos = pm.advance("u1", pos, target, 0.5);
        pm.clearUnit("u1");
        Point2d fresh = new Point2d(5f, 5f);
        Point2d after = pm.advance("u1", fresh, target, 0.5);
        assertThat(EmulatedGame.distance(after, target))
            .isLessThan(EmulatedGame.distance(fresh, target));
    }

    @Test void unreachableTargetFallsBackToStepToward() {
        boolean[][] g = new boolean[10][10];
        for (boolean[] col : g) Arrays.fill(col, true);
        for (int y = 0; y < 10; y++) g[5][y] = false; // vertical wall — right side unreachable
        PathfindingMovement pm = new PathfindingMovement(new WalkabilityGrid(10, 10, g));
        Point2d result = pm.advance("u1", new Point2d(2f, 5f), new Point2d(8f, 5f), 0.5);
        assertThat(result).isNotNull(); // must not throw
    }

    @Test void resetClearsAllUnitState() {
        PathfindingMovement pm = new PathfindingMovement(open());
        pm.advance("u1", new Point2d(2f, 2f), new Point2d(20f, 20f), 0.5);
        pm.advance("u2", new Point2d(3f, 3f), new Point2d(18f, 18f), 0.5);
        pm.reset();
        // Both units get fresh paths — no exception
        assertThat(pm.advance("u1", new Point2d(1f, 1f), new Point2d(10f, 10f), 0.5)).isNotNull();
        assertThat(pm.advance("u2", new Point2d(2f, 2f), new Point2d(12f, 12f), 0.5)).isNotNull();
    }
}
