package io.quarkmind.domain;

import java.util.Arrays;

public final class WalkabilityGrid {

    private final boolean[][] walkable;  // [x][y], true = can walk
    private final int width;
    private final int height;

    public WalkabilityGrid(int width, int height, boolean[][] walkable) {
        this.width    = width;
        this.height   = height;
        this.walkable = new boolean[width][height];
        for (int x = 0; x < width; x++) {
            this.walkable[x] = Arrays.copyOf(walkable[x], height);
        }
    }

    public boolean isWalkable(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return false;
        return walkable[x][y];
    }

    public int width()  { return width; }
    public int height() { return height; }

    /**
     * 64x64 emulated map: all walkable except a horizontal wall at y=18
     * spanning x=0..63, with a 3-tile chokepoint gap at x=11,12,13.
     * The straight-line path from staging (26,26) to nexus (8,8) crosses
     * y=18 at x≈18, so all units must detour 5+ tiles to reach the gap.
     */
    public static WalkabilityGrid emulatedMap() {
        boolean[][] grid = new boolean[64][64];
        for (boolean[] col : grid) Arrays.fill(col, true);
        for (int x = 0; x < 64; x++) {
            if (x < 11 || x > 13) grid[x][18] = false;
        }
        return new WalkabilityGrid(64, 64, grid);
    }

    /**
     * Constructs a WalkabilityGrid from ocraft's PathingGrid bitmap.
     * Bit encoding: index = x + y*width; bit = (data[index/8] >> (7 - index%8)) & 1
     * 1 = walkable, 0 = wall. Called by ObservationTranslator for real SC2 (future phase).
     */
    public static WalkabilityGrid fromPathingGrid(byte[] data, int width, int height) {
        boolean[][] grid = new boolean[width][height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = x + y * width;
                grid[x][y] = ((data[index / 8] >> (7 - index % 8)) & 1) == 1;
            }
        }
        return new WalkabilityGrid(width, height, grid);
    }
}
