package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.sc2.mock.SimulatedGame;

import java.util.Map;

/**
 * Seeds a showcase layout — one of every sprite type spread across the map.
 * Shows all 7 unit types (Protoss + Terran) in both friendly and enemy team colours.
 * Dev/test only.
 */
@UnlessBuildProfile("prod")
@Path("/sc2/showcase")
@Produces(MediaType.APPLICATION_JSON)
public class ShowcaseResource {

    @Inject AgentOrchestrator orchestrator;
    @Inject SC2Engine engine;
    @Inject SimulatedGame simulatedGame;

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response seedShowcase() {
        // Reset game state directly — avoids firing GameStarted which starts the AI scheduler.
        // With AI off, no Pylons/Gateways accumulate and units stay frozen for the showcase.
        simulatedGame.reset();

        // Nexus is at tile (8,8), geysers nearby. The default isometric camera
        // is positioned in the +x/+z corner, so tiles 8-20 appear in the foreground.
        // Place units around the Nexus so they're immediately visible.

        // Row 1 (y=12): Protoss enemies spread east of Nexus
        simulatedGame.spawnEnemyUnit(UnitType.PROBE,    new Point2d(12, 12));
        simulatedGame.spawnEnemyUnit(UnitType.ZEALOT,   new Point2d(16, 12));
        simulatedGame.spawnEnemyUnit(UnitType.STALKER,  new Point2d(20, 12));

        // Row 2 (y=16): Terran enemies
        simulatedGame.spawnEnemyUnit(UnitType.MARINE,   new Point2d(12, 16));
        simulatedGame.spawnEnemyUnit(UnitType.MARAUDER, new Point2d(16, 16));
        simulatedGame.spawnEnemyUnit(UnitType.MEDIVAC,  new Point2d(20, 16));  // floats higher

        // Row 3 (y=20): Zergling fallback blob
        simulatedGame.spawnEnemyUnit(UnitType.ZERGLING, new Point2d(16, 20));

        // Push state to all connected browser sessions.
        // Engine stays running so new browser connections immediately get state.
        engine.observe();

        return Response.ok(Map.of(
            "status",  "showcase seeded",
            "centre",  "tiles 8-20, world coords -16 to -8",
            "enemies", "Row1(y=12): Probe/Zealot/Stalker | Row2(y=16): Marine/Marauder/Medivac | Row3(y=20): Zergling"
        )).build();
    }
}
