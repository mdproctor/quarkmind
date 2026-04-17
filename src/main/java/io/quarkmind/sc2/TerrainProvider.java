package io.quarkmind.sc2;

import io.quarkmind.domain.TerrainGrid;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TerrainProvider {
    private TerrainGrid terrain;

    public void setTerrain(TerrainGrid terrain) { this.terrain = terrain; }

    /** Returns the active terrain, or null in mock/replay contexts. */
    public TerrainGrid get() { return terrain; }
}
