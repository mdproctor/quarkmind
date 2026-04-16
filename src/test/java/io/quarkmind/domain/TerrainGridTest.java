package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TerrainGridTest {

    // ---- isWalkable — same contract as WalkabilityGrid ----

    @Test void emulatedMapNexusTileIsWalkable() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(8, 8)).isTrue();
    }
    @Test void emulatedMapStagingTileIsWalkable() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(26, 26)).isTrue();
    }
    @Test void emulatedMapWallTileIsBlocked() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(20, 18)).isFalse();
    }
    @Test void emulatedMapChokeGapIsWalkable() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(12, 18)).isTrue();
    }
    @Test void emulatedMapChokeEdgesAreBlocked() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(10, 18)).isFalse();
        assertThat(TerrainGrid.emulatedMap().isWalkable(14, 18)).isFalse();
    }
    @Test void outOfBoundsIsNotWalkable() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.isWalkable(-1,  0)).isFalse();
        assertThat(g.isWalkable(64,  0)).isFalse();
        assertThat(g.isWalkable( 0, 64)).isFalse();
    }

    // ---- heightAt ----

    @Test void emulatedMapHighGroundCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(0, 19)).isEqualTo(TerrainGrid.Height.HIGH);
        assertThat(g.heightAt(26, 26)).isEqualTo(TerrainGrid.Height.HIGH);
    }
    @Test void emulatedMapLowGroundCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(8, 8)).isEqualTo(TerrainGrid.Height.LOW);
        assertThat(g.heightAt(0, 17)).isEqualTo(TerrainGrid.Height.LOW);
    }
    @Test void emulatedMapRampCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(11, 18)).isEqualTo(TerrainGrid.Height.RAMP);
        assertThat(g.heightAt(12, 18)).isEqualTo(TerrainGrid.Height.RAMP);
        assertThat(g.heightAt(13, 18)).isEqualTo(TerrainGrid.Height.RAMP);
    }
    @Test void emulatedMapWallsCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(10, 18)).isEqualTo(TerrainGrid.Height.WALL);
        assertThat(g.heightAt(14, 18)).isEqualTo(TerrainGrid.Height.WALL);
        assertThat(g.heightAt( 0, 18)).isEqualTo(TerrainGrid.Height.WALL);
    }
    @Test void isWalkableMatchesHeight() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.isWalkable( 0, 19)).isTrue();   // HIGH → walkable
        assertThat(g.isWalkable( 8,  8)).isTrue();   // LOW  → walkable
        assertThat(g.isWalkable(12, 18)).isTrue();   // RAMP → walkable
        assertThat(g.isWalkable(20, 18)).isFalse();  // WALL → blocked
    }
    @Test void outOfBoundsHeightIsWall() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(-1,  0)).isEqualTo(TerrainGrid.Height.WALL);
        assertThat(g.heightAt(64,  0)).isEqualTo(TerrainGrid.Height.WALL);
    }

    // ---- dimensions ----

    @Test void widthAndHeightCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.width()).isEqualTo(64);
        assertThat(g.height()).isEqualTo(64);
    }

    // ---- fromPathingGrid ----

    @Test void fromPathingGrid_walkableTileIsLow() {
        TerrainGrid g = TerrainGrid.fromPathingGrid(new byte[]{(byte) 0xFF}, 8, 1);
        assertThat(g.heightAt(0, 0)).isEqualTo(TerrainGrid.Height.LOW);
        assertThat(g.isWalkable(0, 0)).isTrue();
    }
    @Test void fromPathingGrid_nonWalkableTileIsWall() {
        TerrainGrid g = TerrainGrid.fromPathingGrid(new byte[]{(byte) 0x00}, 8, 1);
        assertThat(g.heightAt(0, 0)).isEqualTo(TerrainGrid.Height.WALL);
        assertThat(g.isWalkable(0, 0)).isFalse();
    }
    @Test void fromPathingGrid_mixedBitsDecodeCorrectly() {
        // 0xB2 = 1011_0010: bit7=1(walk), bit6=0(wall), bit0=0(wall)
        TerrainGrid g = TerrainGrid.fromPathingGrid(new byte[]{(byte) 0xB2}, 8, 1);
        assertThat(g.isWalkable(0, 0)).isTrue();   // bit 7 = 1
        assertThat(g.isWalkable(1, 0)).isFalse();  // bit 6 = 0
        assertThat(g.isWalkable(7, 0)).isFalse();  // bit 0 = 0
    }
}
