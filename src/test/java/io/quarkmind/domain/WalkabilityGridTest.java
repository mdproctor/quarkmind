package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WalkabilityGridTest {

    @Test void emulatedMapNexusTileIsWalkable() {
        assertThat(WalkabilityGrid.emulatedMap().isWalkable(8, 8)).isTrue();
    }
    @Test void emulatedMapStagingTileIsWalkable() {
        assertThat(WalkabilityGrid.emulatedMap().isWalkable(26, 26)).isTrue();
    }
    @Test void emulatedMapWallTileIsBlocked() {
        assertThat(WalkabilityGrid.emulatedMap().isWalkable(20, 18)).isFalse();
    }
    @Test void emulatedMapChokeGapIsWalkable() {
        assertThat(WalkabilityGrid.emulatedMap().isWalkable(12, 18)).isTrue();
    }
    @Test void emulatedMapChokeEdgesAreBlocked() {
        assertThat(WalkabilityGrid.emulatedMap().isWalkable(10, 18)).isFalse();
        assertThat(WalkabilityGrid.emulatedMap().isWalkable(14, 18)).isFalse();
    }
    @Test void outOfBoundsReturnsFalse() {
        WalkabilityGrid g = WalkabilityGrid.emulatedMap();
        assertThat(g.isWalkable(-1,  0)).isFalse();
        assertThat(g.isWalkable(64,  0)).isFalse();
        assertThat(g.isWalkable( 0, 64)).isFalse();
    }
    @Test void fromPathingGrid_bit1IsWalkable() {
        WalkabilityGrid g = WalkabilityGrid.fromPathingGrid(new byte[]{(byte)0xFF}, 8, 1);
        assertThat(g.isWalkable(0, 0)).isTrue();
        assertThat(g.isWalkable(7, 0)).isTrue();
    }
    @Test void fromPathingGrid_bit0IsBlocked() {
        WalkabilityGrid g = WalkabilityGrid.fromPathingGrid(new byte[]{(byte)0x00}, 8, 1);
        assertThat(g.isWalkable(0, 0)).isFalse();
        assertThat(g.isWalkable(7, 0)).isFalse();
    }
    @Test void fromPathingGrid_mixedBitsDecodeCorrectly() {
        // 0xB2 = 1011_0010: bits 7,5,4,1 set; bits 6,3,2,0 clear
        // isWalkable(x,0): bit = (data[0] >> (7-x)) & 1
        // x=0 → bit7 → 1 → walkable; x=1 → bit6 → 0 → blocked; x=7 → bit0 → 0 → blocked
        WalkabilityGrid g = WalkabilityGrid.fromPathingGrid(new byte[]{(byte)0xB2}, 8, 1);
        assertThat(g.isWalkable(0, 0)).isTrue();   // bit 7 = 1
        assertThat(g.isWalkable(1, 0)).isFalse();  // bit 6 = 0
        assertThat(g.isWalkable(7, 0)).isFalse();  // bit 0 = 0
    }
    @Test void widthAndHeightCorrect() {
        WalkabilityGrid g = WalkabilityGrid.emulatedMap();
        assertThat(g.width()).isEqualTo(64);
        assertThat(g.height()).isEqualTo(64);
    }
}
