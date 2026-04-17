package io.quarkmind.plugin.tactics;

import io.quarkmind.domain.*;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KiteStrategyTest {
    private static Unit unit(String tag, Point2d pos) {
        return new Unit(tag, UnitType.STALKER, pos, 80, 80, 80, 80, 0);
    }
    private static double dist(Point2d a, Point2d b) {
        double dx = a.x()-b.x(), dy = a.y()-b.y();
        return Math.sqrt(dx*dx+dy*dy);
    }

    @Test void direct_retreatsAwayFromEnemy() {
        Point2d result = new DirectKiteStrategy().retreatTarget(
            unit("u-0", new Point2d(5,5)), List.of(unit("e-0", new Point2d(7,5))), null);
        assertThat(result.x()).isLessThan(5f);
        assertThat(result.y()).isCloseTo(5f, Offset.offset(0.01f));
    }
    @Test void direct_stepSizeIsOnePoint0() {
        Point2d from = new Point2d(5,5);
        Point2d result = new DirectKiteStrategy().retreatTarget(
            unit("u-0", from), List.of(unit("e-0", new Point2d(7,5))), null);
        assertThat(dist(from, result)).isCloseTo(1.0, Offset.offset(0.01));
    }
    @Test void direct_picksNearestEnemy() {
        Point2d result = new DirectKiteStrategy().retreatTarget(
            unit("u-0", new Point2d(5,5)),
            List.of(unit("e-near", new Point2d(6,5)), unit("e-far", new Point2d(10,5))), null);
        assertThat(result.x()).isLessThan(5f);
    }
    @Test void direct_ignoresNonNullTerrain() {
        TerrainGrid terrain = TerrainGrid.emulatedMap();
        DirectKiteStrategy s = new DirectKiteStrategy();
        List<Unit> enemies = List.of(unit("e-0", new Point2d(7,5)));
        assertThat(s.retreatTarget(unit("u-0", new Point2d(5,5)), enemies, terrain))
            .isEqualTo(s.retreatTarget(unit("u-0", new Point2d(5,5)), enemies, null));
    }
}
