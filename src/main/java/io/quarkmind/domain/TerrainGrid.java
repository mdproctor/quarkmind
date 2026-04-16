package io.quarkmind.domain;

import java.util.Arrays;

public final class TerrainGrid {

    public enum Height { HIGH, LOW, RAMP, WALL }

    private final Height[][] grid;  // [x][y]
    private final int width;
    private final int height;

    public TerrainGrid(int width, int height, Height[][] grid) {
        this.width  = width;
        this.height = height;
        this.grid   = new Height[width][height];
        for (int x = 0; x < width; x++) {
            this.grid[x] = Arrays.copyOf(grid[x], height);
        }
    }

    public Height heightAt(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return Height.WALL;
        return grid[x][y];
    }

    public boolean isWalkable(int x, int y) {
        return heightAt(x, y) != Height.WALL;
    }

    public int width()  { return width; }
    public int height() { return height; }

    /**
     * 64x64 emulated map.
     * y > 18: HIGH ground (enemy staging side, visually top of screen)
     * y < 18: LOW ground (nexus/defender side, visually bottom)
     * y = 18, x = 11-13: RAMP (chokepoint gap)
     * y = 18, x = 0-10 and x = 14-63: WALL
     */
    public static TerrainGrid emulatedMap() {
        Height[][] grid = new Height[64][64];
        for (Height[] col : grid) Arrays.fill(col, Height.LOW);
        for (int x = 0; x < 64; x++) {
            for (int y = 19; y < 64; y++) {
                grid[x][y] = Height.HIGH;
            }
        }
        for (int x = 0; x < 64; x++) {
            grid[x][18] = (x >= 11 && x <= 13) ? Height.RAMP : Height.WALL;
        }
        return new TerrainGrid(64, 64, grid);
    }

    /**
     * Constructs a TerrainGrid from ocraft's PathingGrid bitmap.
     * All walkable tiles return LOW; height data integration deferred to real SC2 phase.
     * Bit encoding: index = x + y*width; bit = (data[index/8] >> (7 - index%8)) & 1
     */
    public static TerrainGrid fromPathingGrid(byte[] data, int width, int height) {
        Height[][] grid = new Height[width][height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = x + y * width;
                boolean walkable = ((data[index / 8] >> (7 - index % 8)) & 1) == 1;
                grid[x][y] = walkable ? Height.LOW : Height.WALL;
            }
        }
        return new TerrainGrid(width, height, grid);
    }
}
