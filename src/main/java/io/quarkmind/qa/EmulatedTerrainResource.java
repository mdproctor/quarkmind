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
 * Returns the emulated map's terrain as sparse typed tile lists.
 * Fetched once by the visualizer at startup; terrain never changes during a game.
 * LOW tiles are the default — not included in any list.
 */
@UnlessBuildProfile("prod")
@Path("/qa/emulated/terrain")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatedTerrainResource {

    @GET
    public TerrainResponse getTerrain() {
        TerrainGrid grid = TerrainGrid.emulatedMap();
        List<int[]> walls      = new ArrayList<>();
        List<int[]> highGround = new ArrayList<>();
        List<int[]> ramps      = new ArrayList<>();
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                switch (grid.heightAt(x, y)) {
                    case WALL -> walls.add(new int[]{x, y});
                    case HIGH -> highGround.add(new int[]{x, y});
                    case RAMP -> ramps.add(new int[]{x, y});
                    case LOW  -> {} // default — omitted
                }
            }
        }
        return new TerrainResponse(grid.width(), grid.height(), walls, highGround, ramps);
    }

    public record TerrainResponse(
        int width,
        int height,
        List<int[]> walls,
        List<int[]> highGround,
        List<int[]> ramps
    ) {}
}
