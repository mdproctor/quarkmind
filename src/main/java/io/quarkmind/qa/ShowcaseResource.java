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
        simulatedGame.reset();

        // Row 1 (y=11): Protoss ground
        simulatedGame.spawnEnemyUnit(UnitType.PROBE,    new Point2d(10, 11));
        simulatedGame.spawnEnemyUnit(UnitType.ZEALOT,   new Point2d(12, 11));
        simulatedGame.spawnEnemyUnit(UnitType.STALKER,  new Point2d(14, 11));

        // Row 2 (y=13): original Terran ground/air
        simulatedGame.spawnEnemyUnit(UnitType.MARINE,   new Point2d(10, 13));
        simulatedGame.spawnEnemyUnit(UnitType.MARAUDER, new Point2d(12, 13));
        simulatedGame.spawnEnemyUnit(UnitType.MEDIVAC,  new Point2d(14, 13));

        // Row 3 (y=14-15): Zerg
        simulatedGame.spawnEnemyUnit(UnitType.ZERGLING,  new Point2d(10, 15));
        simulatedGame.spawnEnemyUnit(UnitType.ROACH,     new Point2d(12, 15));
        simulatedGame.spawnEnemyUnit(UnitType.HYDRALISK, new Point2d(10, 14));
        simulatedGame.spawnEnemyUnit(UnitType.MUTALISK,  new Point2d(12, 14));

        // Column (x=16, y=9-12) + (15,12): new Terran ground
        simulatedGame.spawnEnemyUnit(UnitType.GHOST,      new Point2d(16, 9));
        simulatedGame.spawnEnemyUnit(UnitType.CYCLONE,    new Point2d(16, 10));
        simulatedGame.spawnEnemyUnit(UnitType.WIDOW_MINE, new Point2d(16, 11));
        simulatedGame.spawnEnemyUnit(UnitType.SIEGE_TANK, new Point2d(16, 12));
        simulatedGame.spawnEnemyUnit(UnitType.THOR,       new Point2d(15, 12));

        // Row (y=8, x=10-14): new Terran air
        simulatedGame.spawnEnemyUnit(UnitType.VIKING,        new Point2d(10, 8));
        simulatedGame.spawnEnemyUnit(UnitType.RAVEN,         new Point2d(11, 8));
        simulatedGame.spawnEnemyUnit(UnitType.BANSHEE,       new Point2d(12, 8));
        simulatedGame.spawnEnemyUnit(UnitType.LIBERATOR,     new Point2d(13, 8));
        simulatedGame.spawnEnemyUnit(UnitType.BATTLECRUISER, new Point2d(14, 8));

        engine.observe();

        return Response.ok(Map.of(
            "status",  "showcase seeded",
            "enemies", "20 units: Protoss(3) + Terran-orig(3) + Zerg(4) + Terran-new-ground(5) + Terran-new-air(5)"
        )).build();
    }
}
