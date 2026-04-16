package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityGridTest {

    VisibilityGrid grid;
    TerrainGrid terrain;

    @BeforeEach
    void setUp() {
        grid    = new VisibilityGrid();
        terrain = TerrainGrid.emulatedMap(); // 64x64: y<18=LOW, y>18=HIGH, y=18 x=11-13=RAMP
    }

    // ---- Initial state ----

    @Test
    void allTilesStartUnseen() {
        assertThat(grid.at(0, 0)).isEqualTo(TileVisibility.UNSEEN);
        assertThat(grid.at(32, 32)).isEqualTo(TileVisibility.UNSEEN);
        assertThat(grid.at(63, 63)).isEqualTo(TileVisibility.UNSEEN);
    }

    @Test
    void isVisibleReturnsFalseForUnseenTile() {
        assertThat(grid.isVisible(new Point2d(10, 10))).isFalse();
    }

    // ---- Flat circular vision (no terrain) ----

    @Test
    void recompute_noTerrain_tileWithinRangeBecomesVisible() {
        Unit probe = unit(10, 10, UnitType.PROBE); // sightRange=8
        grid.recompute(List.of(probe), List.of(), null);
        assertThat(grid.at(10, 10)).isEqualTo(TileVisibility.VISIBLE);
        assertThat(grid.at(17, 10)).isEqualTo(TileVisibility.VISIBLE); // exactly range=8 away (approx)
    }

    @Test
    void recompute_noTerrain_tileOutsideRangeRemainsUnseen() {
        Unit probe = unit(10, 10, UnitType.PROBE); // sightRange=8
        grid.recompute(List.of(probe), List.of(), null);
        assertThat(grid.at(20, 10)).isEqualTo(TileVisibility.UNSEEN); // 10 tiles away, range=8
    }

    @Test
    void recompute_building_illuminatesTilesInRange() {
        Building nexus = building(8, 8, BuildingType.NEXUS); // sightRange=9
        grid.recompute(List.of(), List.of(nexus), null);
        assertThat(grid.at(8, 8)).isEqualTo(TileVisibility.VISIBLE);
        assertThat(grid.at(8, 16)).isEqualTo(TileVisibility.VISIBLE); // 8 tiles away, range=9
    }

    @Test
    void recompute_isVisible_trueForVisibleTile() {
        Unit probe = unit(10, 10, UnitType.PROBE);
        grid.recompute(List.of(probe), List.of(), null);
        assertThat(grid.isVisible(new Point2d(10, 10))).isTrue();
    }

    // ---- MEMORY accumulation ----

    @Test
    void visibleTileBecomesMemotyWhenObserverLeaves() {
        Unit probe = unit(10, 10, UnitType.PROBE);
        grid.recompute(List.of(probe), List.of(), null);
        assertThat(grid.at(10, 10)).isEqualTo(TileVisibility.VISIBLE);

        // move probe far away
        Unit probeElsewhere = unit(60, 5, UnitType.PROBE);
        grid.recompute(List.of(probeElsewhere), List.of(), null);
        assertThat(grid.at(10, 10)).isEqualTo(TileVisibility.MEMORY);
    }

    @Test
    void memoryNeverRevertsToUnseen() {
        Unit probe = unit(10, 10, UnitType.PROBE);
        grid.recompute(List.of(probe), List.of(), null);
        grid.recompute(List.of(unit(60, 5, UnitType.PROBE)), List.of(), null);
        grid.recompute(List.of(unit(60, 5, UnitType.PROBE)), List.of(), null);
        assertThat(grid.at(10, 10)).isEqualTo(TileVisibility.MEMORY);
    }

    @Test
    void visibleTileStaysVisibleWhenObserverStays() {
        Unit probe = unit(10, 10, UnitType.PROBE);
        grid.recompute(List.of(probe), List.of(), null);
        grid.recompute(List.of(probe), List.of(), null);
        assertThat(grid.at(10, 10)).isEqualTo(TileVisibility.VISIBLE);
    }

    // ---- High ground rule ----

    @Test
    void lowObserver_cannotSeeHighTiles() {
        // probe at (10,10) is on LOW (y<18). HIGH tiles are y>18.
        Unit probe = unit(10, 10, UnitType.PROBE); // sightRange=8, LOW observer
        grid.recompute(List.of(probe), List.of(), terrain);
        // tile at (10,17) is LOW (y<18), within range=7 — should be visible
        assertThat(grid.at(10, 17)).isEqualTo(TileVisibility.VISIBLE);
        // Use a stalker (range=10) to confirm height blocking:
        Unit stalker = unit(10, 10, UnitType.STALKER); // sightRange=10
        grid.reset();
        grid.recompute(List.of(stalker), List.of(), terrain);
        // (10,19) is HIGH, distance=9, within stalker range=10 — but blocked by high-ground rule
        assertThat(grid.at(10, 19)).isEqualTo(TileVisibility.UNSEEN);
    }

    @Test
    void highObserver_canSeeHighTiles() {
        // probe at (10,25) is on HIGH (y>18). Can illuminate HIGH tiles.
        Unit probe = unit(10, 25, UnitType.PROBE); // sightRange=8, HIGH observer
        grid.recompute(List.of(probe), List.of(), terrain);
        assertThat(grid.at(10, 27)).isEqualTo(TileVisibility.VISIBLE); // HIGH, 2 tiles away
    }

    @Test
    void highObserver_canSeeDownToLow() {
        // probe at (10,25) HIGH can see LOW tiles below
        Unit probe = unit(10, 25, UnitType.PROBE); // sightRange=8
        grid.recompute(List.of(probe), List.of(), terrain);
        assertThat(grid.at(10, 17)).isEqualTo(TileVisibility.VISIBLE); // LOW, 8 tiles away
    }

    @Test
    void wallTilesAreNeverVisible() {
        // WALL tiles: y=18, x outside 11-13
        Unit stalker = unit(10, 10, UnitType.STALKER); // sightRange=10, close to wall row
        grid.recompute(List.of(stalker), List.of(), terrain);
        assertThat(grid.at(10, 18)).isEqualTo(TileVisibility.UNSEEN); // x=10 is WALL
        assertThat(grid.at(11, 18)).isEqualTo(TileVisibility.VISIBLE); // x=11 is RAMP
    }

    // ---- Encode ----

    @Test
    void encodeProduces4096CharString() {
        assertThat(grid.encode()).hasSize(4096);
    }

    @Test
    void encodeAllUnseen_allZeros() {
        String encoded = grid.encode();
        assertThat(encoded).matches("[0]{4096}");
    }

    @Test
    void encodeReflectsVisibleAndMemoryTiles() {
        Unit probe = unit(10, 10, UnitType.PROBE);
        grid.recompute(List.of(probe), List.of(), null);
        String first = grid.encode();
        assertThat(first).contains("2"); // at least one VISIBLE tile

        // move away — VISIBLE -> MEMORY
        grid.recompute(List.of(unit(60, 5, UnitType.PROBE)), List.of(), null);
        String second = grid.encode();
        assertThat(second).contains("1"); // at least one MEMORY tile
        assertThat(second.charAt(10 * 64 + 10)).isEqualTo('1'); // tile (10,10) is MEMORY
    }

    // ---- Reset ----

    @Test
    void resetClearsAllTilesToUnseen() {
        Unit probe = unit(10, 10, UnitType.PROBE);
        grid.recompute(List.of(probe), List.of(), null);
        grid.reset();
        assertThat(grid.at(10, 10)).isEqualTo(TileVisibility.UNSEEN);
    }

    // ---- Helpers ----

    private Unit unit(float x, float y, UnitType type) {
        return new Unit("u-" + x + "-" + y, type, new Point2d(x, y), 100, 100, 50, 50);
    }

    private Building building(float x, float y, BuildingType type) {
        return new Building("b-" + x + "-" + y, type, new Point2d(x, y), 1000, 1000, true);
    }
}
