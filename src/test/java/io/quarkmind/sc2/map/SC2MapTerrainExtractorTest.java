package io.quarkmind.sc2.map;

import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.domain.TerrainGrid.Height;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SC2MapTerrainExtractorTest {

    private static final Path MAP = Path.of("src/test/resources/maps/TorchesAIE_v4.SC2Map");

    @Test
    void extractsDimensionsFromTorchesAIE() {
        TerrainGrid grid = SC2MapTerrainExtractor.extract(MAP);
        assertThat(grid.width()).isEqualTo(160);
        assertThat(grid.height()).isEqualTo(208);
    }

    @Test
    void voidCellAtTopLeftIsWall() {
        TerrainGrid grid = SC2MapTerrainExtractor.extract(MAP);
        // x=0, y=0 has cliff=0 (void)
        assertThat(grid.heightAt(0, 0)).isEqualTo(Height.WALL);
    }

    @Test
    void borderCliffIsWall() {
        TerrainGrid grid = SC2MapTerrainExtractor.extract(MAP);
        // x=0, y=1 has cliff=320 (impassable border)
        assertThat(grid.heightAt(0, 1)).isEqualTo(Height.WALL);
    }

    @Test
    void baseTileIsHigh() {
        TerrainGrid grid = SC2MapTerrainExtractor.extract(MAP);
        // x=10, y=1 has cliff=192 (HIGH — player base area)
        assertThat(grid.heightAt(10, 1)).isEqualTo(Height.HIGH);
    }

    @Test
    void lowGroundTileIsLow() {
        TerrainGrid grid = SC2MapTerrainExtractor.extract(MAP);
        // x=80, y=104 has cliff=64 (LOW — center open ground)
        assertThat(grid.heightAt(80, 104)).isEqualTo(Height.LOW);
    }

    @Test
    void rampCellsExist() {
        TerrainGrid grid = SC2MapTerrainExtractor.extract(MAP);
        int rampCount = 0;
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                if (grid.heightAt(x, y) == Height.RAMP) rampCount++;
            }
        }
        assertThat(rampCount).isGreaterThan(0);
    }

    @Test
    void throwsIfMapFileNotFound() {
        assertThatThrownBy(() -> SC2MapTerrainExtractor.extract(Path.of("nonexistent.SC2Map")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nonexistent.SC2Map");
    }

    @Test
    void throwsIfCliffLevelFileAbsent() {
        assertThatThrownBy(() -> SC2MapTerrainExtractor.extract(
                Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("t3SyncCliffLevel");
    }
}
