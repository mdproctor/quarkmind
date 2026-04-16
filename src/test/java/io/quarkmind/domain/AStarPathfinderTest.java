package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AStarPathfinderTest {

    private final AStarPathfinder pf = new AStarPathfinder();

    private TerrainGrid open(int w, int h) {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[w][h];
        for (TerrainGrid.Height[] col : g) Arrays.fill(col, TerrainGrid.Height.LOW);
        return new TerrainGrid(w, h, g);
    }

    private TerrainGrid withWall(int w, int h, int wallY, int gapX) {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[w][h];
        for (TerrainGrid.Height[] col : g) Arrays.fill(col, TerrainGrid.Height.LOW);
        for (int x = 0; x < w; x++) {
            if (x != gapX) g[x][wallY] = TerrainGrid.Height.WALL;
        }
        return new TerrainGrid(w, h, g);
    }

    @Test void pathOnOpenMap_isNonEmpty() {
        List<Point2d> path = pf.findPath(open(10, 10), new Point2d(0, 0), new Point2d(9, 9));
        assertThat(path).isNotEmpty();
    }

    @Test void pathOnOpenMap_allWaypointsWalkable() {
        TerrainGrid g = open(10, 10);
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
        TerrainGrid g = withWall(10, 10, 5, 5);
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
        TerrainGrid g = withWall(10, 10, 5, 5);
        List<Point2d> path = pf.findPath(g, new Point2d(2, 2), new Point2d(7, 8));
        assertThat(path).isNotEmpty();
        Point2d last = path.get(path.size() - 1);
        assertThat(last.x()).isEqualTo(7.5f);
        assertThat(last.y()).isEqualTo(8.5f);
    }

    @Test void sameStartAndGoal_returnsEmpty() {
        assertThat(pf.findPath(open(10, 10), new Point2d(5, 5), new Point2d(5, 5))).isEmpty();
    }

    @Test void unreachableGoal_returnsEmpty() {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[10][10];
        for (TerrainGrid.Height[] col : g) Arrays.fill(col, TerrainGrid.Height.LOW);
        for (int y = 0; y < 10; y++) g[5][y] = TerrainGrid.Height.WALL;
        TerrainGrid blocked = new TerrainGrid(10, 10, g);
        assertThat(pf.findPath(blocked, new Point2d(2, 5), new Point2d(8, 5))).isEmpty();
    }

    @Test void pathOnEmulatedMap_nexusToStaging_isNonEmpty() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        List<Point2d> path = pf.findPath(g, new Point2d(8, 8), new Point2d(26, 26));
        assertThat(path).isNotEmpty();
    }

    @Test void pathOnEmulatedMap_passesNearChokepoint() {
        TerrainGrid g = TerrainGrid.emulatedMap();
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
        TerrainGrid g = TerrainGrid.emulatedMap();
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
