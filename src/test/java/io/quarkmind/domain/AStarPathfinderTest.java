package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AStarPathfinderTest {

    private final AStarPathfinder pf = new AStarPathfinder();

    private WalkabilityGrid open(int w, int h) {
        boolean[][] g = new boolean[w][h];
        for (boolean[] col : g) Arrays.fill(col, true);
        return new WalkabilityGrid(w, h, g);
    }

    private WalkabilityGrid withWall(int w, int h, int wallY, int gapX) {
        boolean[][] g = new boolean[w][h];
        for (boolean[] col : g) Arrays.fill(col, true);
        for (int x = 0; x < w; x++) {
            if (x != gapX) g[x][wallY] = false;
        }
        return new WalkabilityGrid(w, h, g);
    }

    @Test void pathOnOpenMap_isNonEmpty() {
        List<Point2d> path = pf.findPath(open(10, 10), new Point2d(0, 0), new Point2d(9, 9));
        assertThat(path).isNotEmpty();
    }

    @Test void pathOnOpenMap_allWaypointsWalkable() {
        WalkabilityGrid g = open(10, 10);
        List<Point2d> path = pf.findPath(g, new Point2d(0, 0), new Point2d(9, 9));
        for (Point2d wp : path) {
            assertThat(g.isWalkable((int) wp.x(), (int) wp.y()))
                .as("waypoint %s should be walkable", wp).isTrue();
        }
    }

    @Test void pathOnOpenMap_endsNearGoal() {
        List<Point2d> path = pf.findPath(open(10, 10), new Point2d(0, 0), new Point2d(9, 9));
        Point2d last = path.get(path.size() - 1);
        assertThat(last.x()).isBetween(9f, 10f);
        assertThat(last.y()).isBetween(9f, 10f);
    }

    @Test void pathAroundWall_doesNotCrossWall() {
        WalkabilityGrid g = withWall(10, 10, 5, 5);
        List<Point2d> path = pf.findPath(g, new Point2d(2, 2), new Point2d(7, 8));
        for (Point2d wp : path) {
            int tx = (int) wp.x();
            int ty = (int) wp.y();
            if (ty == 5) {
                assertThat(tx).as("must cross wall only at gap (x=5)").isEqualTo(5);
            }
        }
    }

    @Test void pathAroundWall_reachesGoal() {
        WalkabilityGrid g = withWall(10, 10, 5, 5);
        List<Point2d> path = pf.findPath(g, new Point2d(2, 2), new Point2d(7, 8));
        assertThat(path).isNotEmpty();
        Point2d last = path.get(path.size() - 1);
        assertThat((int) last.x()).isEqualTo(7);
        assertThat((int) last.y()).isEqualTo(8);
    }

    @Test void sameStartAndGoal_returnsEmpty() {
        assertThat(pf.findPath(open(10, 10), new Point2d(5, 5), new Point2d(5, 5))).isEmpty();
    }

    @Test void unreachableGoal_returnsEmpty() {
        boolean[][] g = new boolean[10][10];
        for (boolean[] col : g) Arrays.fill(col, true);
        for (int y = 0; y < 10; y++) g[5][y] = false;
        WalkabilityGrid blocked = new WalkabilityGrid(10, 10, g);
        assertThat(pf.findPath(blocked, new Point2d(2, 5), new Point2d(8, 5))).isEmpty();
    }

    @Test void pathOnEmulatedMap_nexusToStaging_isNonEmpty() {
        WalkabilityGrid g = WalkabilityGrid.emulatedMap();
        List<Point2d> path = pf.findPath(g, new Point2d(8, 8), new Point2d(26, 26));
        assertThat(path).isNotEmpty();
    }

    @Test void pathOnEmulatedMap_passesNearChokepoint() {
        WalkabilityGrid g = WalkabilityGrid.emulatedMap();
        List<Point2d> path = pf.findPath(g, new Point2d(8, 8), new Point2d(26, 26));
        for (Point2d wp : path) {
            int tx = (int) wp.x();
            int ty = (int) wp.y();
            if (ty == 18) {
                assertThat(tx).as("must cross wall only through gap x=[11,13]").isBetween(11, 13);
            }
        }
    }

    @Test void pathOnEmulatedMap_stagingToNexus() {
        WalkabilityGrid g = WalkabilityGrid.emulatedMap();
        List<Point2d> path = pf.findPath(g, new Point2d(26, 26), new Point2d(8, 8));
        assertThat(path).isNotEmpty();
        for (Point2d wp : path) {
            int tx = (int) wp.x();
            int ty = (int) wp.y();
            if (ty == 18) {
                assertThat(tx).isBetween(11, 13);
            }
        }
    }
}
