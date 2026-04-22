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
 * Shows all 11 unit types (Protoss + Terran + Zerg) in enemy team colour.
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

        // Nexus at tile (8,8), starting probes near (9,9). Sight ranges: Nexus=9, Probe=8.
        // All units placed within distance 8.5 of Nexus so they are visible in emulated mode.
        // Camera default target is world (-16,0,-16) = tile (9,9) — units at x/y 10-14 are
        // in the foreground of the default isometric view.

        // Row 1 (y=11): Protoss — max dist from Nexus(8,8) = 6.7 tiles
        simulatedGame.spawnEnemyUnit(UnitType.PROBE,    new Point2d(10, 11));
        simulatedGame.spawnEnemyUnit(UnitType.ZEALOT,   new Point2d(12, 11));
        simulatedGame.spawnEnemyUnit(UnitType.STALKER,  new Point2d(14, 11));

        // Row 2 (y=13): Terran — max dist = 7.8 tiles
        simulatedGame.spawnEnemyUnit(UnitType.MARINE,   new Point2d(10, 13));
        simulatedGame.spawnEnemyUnit(UnitType.MARAUDER, new Point2d(12, 13));
        simulatedGame.spawnEnemyUnit(UnitType.MEDIVAC,  new Point2d(14, 13));  // floats higher

        // Row 3 (y=14-15): Zerg — max dist = 8.1 tiles
        simulatedGame.spawnEnemyUnit(UnitType.ZERGLING,  new Point2d(10, 15));
        simulatedGame.spawnEnemyUnit(UnitType.ROACH,     new Point2d(12, 15));
        simulatedGame.spawnEnemyUnit(UnitType.HYDRALISK, new Point2d(10, 14));
        simulatedGame.spawnEnemyUnit(UnitType.MUTALISK,  new Point2d(12, 14));  // floats higher

        // Push state to all connected browser sessions.
        // Engine stays running so new browser connections immediately get state.
        engine.observe();

        return Response.ok(Map.of(
            "status",  "showcase seeded",
            "centre",  "tiles 8-20, world coords -16 to -8",
            "enemies", "Row1(y=11): Probe/Zealot/Stalker | Row2(y=13): Marine/Marauder/Medivac | Row3(y=14-15): Zergling/Roach/Hydralisk/Mutalisk"
        )).build();
    }
}
