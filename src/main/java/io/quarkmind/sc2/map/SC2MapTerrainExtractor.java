package io.quarkmind.sc2.map;

import hu.belicza.andras.mpq.MpqParser;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.domain.TerrainGrid.Height;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

public final class SC2MapTerrainExtractor {

    private static final String CLIFF_FILE = "t3SyncCliffLevel";

    private SC2MapTerrainExtractor() {}

    public static TerrainGrid extract(Path sc2mapPath) {
        byte[] raw;
        try (MpqParser mpq = new MpqParser(sc2mapPath)) {
            raw = mpq.getFile(CLIFF_FILE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot open SC2Map: " + sc2mapPath, e);
        }
        if (raw == null) {
            throw new IllegalArgumentException(
                "t3SyncCliffLevel not found in " + sc2mapPath + " — not a playable SC2Map?");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(8); // skip magic(4) + version(4)
        int w = buf.getInt();
        int h = buf.getInt();
        Height[][] grid = new Height[w][h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int cliff = buf.getShort() & 0xFFFF;
                grid[x][y] = toHeight(cliff);
            }
        }
        return new TerrainGrid(w, h, grid);
    }

    private static Height toHeight(int cliff) {
        if (cliff == 0 || cliff >= 320) return Height.WALL;
        if (cliff % 64 != 0)           return Height.RAMP;
        if (cliff == 64)               return Height.LOW;
        return Height.HIGH;
    }
}
