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
 * Seeds a showcase layout — one of every sprite type (40 units total, all 3 races).
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

        // 6-column × 7-row grid: x ∈ {4,6,8,10,12,14}, z ∈ {2,4,6,8,10,12,14}
        // All positions ≤ 8.49 tiles from Nexus at (8,8) — within sight range.

        // z=2: Terran air
        simulatedGame.spawnEnemyUnit(UnitType.MEDIVAC,       new Point2d(4,  2));
        simulatedGame.spawnEnemyUnit(UnitType.VIKING,        new Point2d(6,  2));
        simulatedGame.spawnEnemyUnit(UnitType.RAVEN,         new Point2d(8,  2));
        simulatedGame.spawnEnemyUnit(UnitType.BANSHEE,       new Point2d(10, 2));
        simulatedGame.spawnEnemyUnit(UnitType.LIBERATOR,     new Point2d(12, 2));
        simulatedGame.spawnEnemyUnit(UnitType.BATTLECRUISER, new Point2d(14, 2));

        // z=4: Terran ground
        simulatedGame.spawnEnemyUnit(UnitType.MARINE,        new Point2d(4,  4));
        simulatedGame.spawnEnemyUnit(UnitType.MARAUDER,      new Point2d(6,  4));
        simulatedGame.spawnEnemyUnit(UnitType.GHOST,         new Point2d(8,  4));
        simulatedGame.spawnEnemyUnit(UnitType.CYCLONE,       new Point2d(10, 4));
        simulatedGame.spawnEnemyUnit(UnitType.WIDOW_MINE,    new Point2d(12, 4));
        simulatedGame.spawnEnemyUnit(UnitType.SIEGE_TANK,    new Point2d(14, 4));

        // z=6: Thor + Protoss air
        simulatedGame.spawnEnemyUnit(UnitType.THOR,          new Point2d(4,  6));
        simulatedGame.spawnEnemyUnit(UnitType.OBSERVER,      new Point2d(6,  6));
        simulatedGame.spawnEnemyUnit(UnitType.VOID_RAY,      new Point2d(8,  6));
        simulatedGame.spawnEnemyUnit(UnitType.CARRIER,       new Point2d(10, 6));
        simulatedGame.spawnEnemyUnit(UnitType.PROBE,         new Point2d(12, 6));
        simulatedGame.spawnEnemyUnit(UnitType.ZEALOT,        new Point2d(14, 6));

        // z=8: Protoss ground A
        simulatedGame.spawnEnemyUnit(UnitType.STALKER,       new Point2d(4,  8));
        simulatedGame.spawnEnemyUnit(UnitType.SENTRY,        new Point2d(6,  8));
        simulatedGame.spawnEnemyUnit(UnitType.ADEPT,         new Point2d(8,  8));
        simulatedGame.spawnEnemyUnit(UnitType.DARK_TEMPLAR,  new Point2d(10, 8));
        simulatedGame.spawnEnemyUnit(UnitType.HIGH_TEMPLAR,  new Point2d(12, 8));
        simulatedGame.spawnEnemyUnit(UnitType.ARCHON,        new Point2d(14, 8));

        // z=10: Protoss ground B + Zerg ground start
        simulatedGame.spawnEnemyUnit(UnitType.DISRUPTOR,     new Point2d(4,  10));
        simulatedGame.spawnEnemyUnit(UnitType.IMMORTAL,      new Point2d(6,  10));
        simulatedGame.spawnEnemyUnit(UnitType.COLOSSUS,      new Point2d(8,  10));
        simulatedGame.spawnEnemyUnit(UnitType.ZERGLING,      new Point2d(10, 10));
        simulatedGame.spawnEnemyUnit(UnitType.ROACH,         new Point2d(12, 10));
        simulatedGame.spawnEnemyUnit(UnitType.HYDRALISK,     new Point2d(14, 10));

        // z=12: Zerg ground
        simulatedGame.spawnEnemyUnit(UnitType.RAVAGER,       new Point2d(4,  12));
        simulatedGame.spawnEnemyUnit(UnitType.LURKER,        new Point2d(6,  12));
        simulatedGame.spawnEnemyUnit(UnitType.INFESTOR,      new Point2d(8,  12));
        simulatedGame.spawnEnemyUnit(UnitType.SWARM_HOST,    new Point2d(10, 12));
        simulatedGame.spawnEnemyUnit(UnitType.QUEEN,         new Point2d(12, 12));
        simulatedGame.spawnEnemyUnit(UnitType.ULTRALISK,     new Point2d(14, 12));

        // z=14: Zerg air
        simulatedGame.spawnEnemyUnit(UnitType.MUTALISK,      new Point2d(4,  14));
        simulatedGame.spawnEnemyUnit(UnitType.CORRUPTOR,     new Point2d(6,  14));
        simulatedGame.spawnEnemyUnit(UnitType.VIPER,         new Point2d(8,  14));
        simulatedGame.spawnEnemyUnit(UnitType.BROOD_LORD,    new Point2d(10, 14));

        engine.observe();

        return Response.ok(Map.of(
            "status",  "showcase seeded",
            "enemies", "40 units: Terran(13) + Protoss(14) + Zerg(13)"
        )).build();
    }
}
