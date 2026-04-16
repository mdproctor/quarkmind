package io.quarkmind.qa;

import io.quarkmind.domain.TerrainGrid;
import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;

/**
 * Returns the emulated map's terrain as a sparse wall list.
 * Fetched once by the visualizer at startup; terrain never changes during a game.
 */
@UnlessBuildProfile("prod")
@Path("/qa/emulated/terrain")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatedTerrainResource {

    @GET
    public TerrainResponse getTerrain() {
        TerrainGrid grid = TerrainGrid.emulatedMap();
        List<int[]> walls = new ArrayList<>();
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                if (!grid.isWalkable(x, y)) walls.add(new int[]{x, y});
            }
        }
        return new TerrainResponse(grid.width(), grid.height(), walls);
    }

    public record TerrainResponse(int width, int height, List<int[]> walls) {}
}
