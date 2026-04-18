package io.quarkmind.plugin.tactics;

import io.quarkmind.domain.*;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KiteStrategyTest {
    private static Unit unit(String tag, Point2d pos) {
        return new Unit(tag, UnitType.STALKER, pos, 80, 80, 80, 80, 0, 0);
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
    @Test void direct_unitOnTopOfEnemy_returnsCurrentPosition() {
        Point2d pos = new Point2d(5, 5);
        Point2d result = new DirectKiteStrategy().retreatTarget(
            unit("u-0", pos),
            List.of(unit("e-0", pos)), // same position
            null);
        assertThat(result).isEqualTo(pos);
    }
    @Test void direct_ignoresNonNullTerrain() {
        TerrainGrid terrain = TerrainGrid.emulatedMap();
        DirectKiteStrategy s = new DirectKiteStrategy();
        List<Unit> enemies = List.of(unit("e-0", new Point2d(7,5)));
        assertThat(s.retreatTarget(unit("u-0", new Point2d(5,5)), enemies, terrain))
            .isEqualTo(s.retreatTarget(unit("u-0", new Point2d(5,5)), enemies, null));
    }

    @Test void terrainAware_openTerrain_sameAsDirect() {
        TerrainGrid terrain = TerrainGrid.emulatedMap();
        List<Unit> enemies = List.of(unit("e-0", new Point2d(7, 5)));
        Unit u = unit("u-0", new Point2d(5, 5));
        assertThat(new TerrainAwareKiteStrategy().retreatTarget(u, enemies, terrain))
            .isEqualTo(new DirectKiteStrategy().retreatTarget(u, enemies, null));
    }

    @Test void terrainAware_wallAhead_findsWalkableAlternative() {
        // unit at (10,17), enemy at (10,15) → ideal retreat is straight up to (10,18) = WALL at x=10
        TerrainGrid terrain = TerrainGrid.emulatedMap();
        Point2d result = new TerrainAwareKiteStrategy().retreatTarget(
            unit("u-0", new Point2d(10, 17)),
            List.of(unit("e-0", new Point2d(10, 15))),
            terrain);
        assertThat(terrain.isWalkable((int) result.x(), (int) result.y())).isTrue();
        assertThat(result).isNotEqualTo(new Point2d(10, 18));
    }

    @Test void terrainAware_nullTerrain_behavesLikeDirect() {
        List<Unit> enemies = List.of(unit("e-0", new Point2d(7, 5)));
        Unit u = unit("u-0", new Point2d(5, 5));
        assertThat(new TerrainAwareKiteStrategy().retreatTarget(u, enemies, null))
            .isEqualTo(new DirectKiteStrategy().retreatTarget(u, enemies, null));
    }
}
