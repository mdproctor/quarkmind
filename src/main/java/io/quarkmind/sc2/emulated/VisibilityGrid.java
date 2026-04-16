package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;

import java.util.List;

/**
 * 64x64 per-tile visibility state for the emulated map.
 * Recomputed each tick by EmulatedGame from friendly unit positions and TerrainGrid.
 * MEMORY state accumulates — tiles never revert from MEMORY to UNSEEN.
 */
public class VisibilityGrid {

    static final int SIZE = 64;

    private final TileVisibility[][] tiles = new TileVisibility[SIZE][SIZE];

    public VisibilityGrid() {
        reset();
    }

    public void reset() {
        for (int x = 0; x < SIZE; x++)
            for (int y = 0; y < SIZE; y++)
                tiles[x][y] = TileVisibility.UNSEEN;
    }

    public TileVisibility at(int x, int y) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE) return TileVisibility.UNSEEN;
        return tiles[x][y];
    }

    public boolean isVisible(Point2d pos) {
        return at((int) pos.x(), (int) pos.y()) == TileVisibility.VISIBLE;
    }

    /**
     * Recomputes visibility from current friendly unit and building positions.
     * Step 1: demote VISIBLE to MEMORY.
     * Step 2: illuminate tiles within each observer's sight radius, applying the
     *         high-ground rule when terrain is non-null.
     *
     * @param terrain null is allowed (e.g. tests without terrain) — treated as all-LOW.
     */
    public void recompute(List<Unit> friendly, List<Building> buildings, TerrainGrid terrain) {
        for (int x = 0; x < SIZE; x++)
            for (int y = 0; y < SIZE; y++)
                if (tiles[x][y] == TileVisibility.VISIBLE)
                    tiles[x][y] = TileVisibility.MEMORY;

        for (Unit u : friendly)
            illuminate((int) u.position().x(), (int) u.position().y(),
                       SC2Data.sightRange(u.type()), terrain);
        for (Building b : buildings)
            illuminate((int) b.position().x(), (int) b.position().y(),
                       SC2Data.sightRange(b.type()), terrain);
    }

    private void illuminate(int cx, int cy, int range, TerrainGrid terrain) {
        TerrainGrid.Height observerH = terrain != null
            ? terrain.heightAt(cx, cy)
            : TerrainGrid.Height.LOW;

        int rangeSq = range * range;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                if (dx * dx + dy * dy > rangeSq) continue;
                int tx = cx + dx;
                int ty = cy + dy;
                if (tx < 0 || tx >= SIZE || ty < 0 || ty >= SIZE) continue;

                TerrainGrid.Height tileH = terrain != null
                    ? terrain.heightAt(tx, ty)
                    : TerrainGrid.Height.LOW;

                if (tileH == TerrainGrid.Height.WALL) continue;

                // SC2 high-ground rule: LOW or RAMP observer cannot illuminate HIGH tiles
                if (tileH == TerrainGrid.Height.HIGH
                        && observerH != TerrainGrid.Height.HIGH) continue;

                tiles[tx][ty] = TileVisibility.VISIBLE;
            }
        }
    }

    /**
     * Encodes the grid as a 4096-char flat string (row-major y*64+x).
     * '0'=UNSEEN, '1'=MEMORY, '2'=VISIBLE.
     */
    public String encode() {
        char[] chars = new char[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++)
                chars[y * SIZE + x] = switch (tiles[x][y]) {
                    case UNSEEN  -> '0';
                    case MEMORY  -> '1';
                    case VISIBLE -> '2';
                };
        return new String(chars);
    }
}
