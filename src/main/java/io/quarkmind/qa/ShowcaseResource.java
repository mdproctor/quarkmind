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
 * Seeds a showcase layout — one of every sprite type (65 units total, all 3 races).
 * Four friendly Probes are scattered as observers to provide fog-of-war coverage.
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

        // 7-column × 10-row grid: x ∈ {2,4,6,8,10,12,14}, z ∈ {2..20 step 2}
        // Four friendly Probe observers at (4,5),(11,5),(4,15),(11,15) provide
        // fog-of-war coverage — all grid positions within 6 tiles of a probe.
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(4,  5));
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(11, 5));
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(4,  15));
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(11, 15));

        // z=2: Terran air
        simulatedGame.spawnEnemyUnit(UnitType.MEDIVAC,          new Point2d(2,  2));
        simulatedGame.spawnEnemyUnit(UnitType.VIKING,           new Point2d(4,  2));
        simulatedGame.spawnEnemyUnit(UnitType.RAVEN,            new Point2d(6,  2));
        simulatedGame.spawnEnemyUnit(UnitType.BANSHEE,          new Point2d(8,  2));
        simulatedGame.spawnEnemyUnit(UnitType.LIBERATOR,        new Point2d(10, 2));
        simulatedGame.spawnEnemyUnit(UnitType.LIBERATOR_AG,     new Point2d(12, 2));
        simulatedGame.spawnEnemyUnit(UnitType.BATTLECRUISER,    new Point2d(14, 2));

        // z=4: Terran ground A
        simulatedGame.spawnEnemyUnit(UnitType.MARINE,           new Point2d(2,  4));
        simulatedGame.spawnEnemyUnit(UnitType.MARAUDER,         new Point2d(4,  4));
        simulatedGame.spawnEnemyUnit(UnitType.GHOST,            new Point2d(6,  4));
        simulatedGame.spawnEnemyUnit(UnitType.CYCLONE,          new Point2d(8,  4));
        simulatedGame.spawnEnemyUnit(UnitType.WIDOW_MINE,       new Point2d(10, 4));
        simulatedGame.spawnEnemyUnit(UnitType.SIEGE_TANK,       new Point2d(12, 4));
        simulatedGame.spawnEnemyUnit(UnitType.SIEGE_TANK_SIEGED, new Point2d(14, 4));

        // z=6: Terran ground B
        simulatedGame.spawnEnemyUnit(UnitType.THOR,             new Point2d(2,  6));
        simulatedGame.spawnEnemyUnit(UnitType.SCV,              new Point2d(4,  6));
        simulatedGame.spawnEnemyUnit(UnitType.REAPER,           new Point2d(6,  6));
        simulatedGame.spawnEnemyUnit(UnitType.HELLION,          new Point2d(8,  6));
        simulatedGame.spawnEnemyUnit(UnitType.HELLBAT,          new Point2d(10, 6));
        simulatedGame.spawnEnemyUnit(UnitType.MULE,             new Point2d(12, 6));
        simulatedGame.spawnEnemyUnit(UnitType.VIKING_ASSAULT,   new Point2d(14, 6));

        // z=8: Auto Turret + Protoss air A
        simulatedGame.spawnEnemyUnit(UnitType.AUTO_TURRET,      new Point2d(2,  8));
        simulatedGame.spawnEnemyUnit(UnitType.OBSERVER,         new Point2d(4,  8));
        simulatedGame.spawnEnemyUnit(UnitType.VOID_RAY,         new Point2d(6,  8));
        simulatedGame.spawnEnemyUnit(UnitType.CARRIER,          new Point2d(8,  8));
        simulatedGame.spawnEnemyUnit(UnitType.PHOENIX,          new Point2d(10, 8));
        simulatedGame.spawnEnemyUnit(UnitType.ORACLE,           new Point2d(12, 8));
        simulatedGame.spawnEnemyUnit(UnitType.TEMPEST,          new Point2d(14, 8));

        // z=10: Protoss air B + ground start
        simulatedGame.spawnEnemyUnit(UnitType.MOTHERSHIP,       new Point2d(2,  10));
        simulatedGame.spawnEnemyUnit(UnitType.WARP_PRISM,       new Point2d(4,  10));
        simulatedGame.spawnEnemyUnit(UnitType.WARP_PRISM_PHASING, new Point2d(6, 10));
        simulatedGame.spawnEnemyUnit(UnitType.INTERCEPTOR,      new Point2d(8,  10));
        simulatedGame.spawnEnemyUnit(UnitType.PROBE,            new Point2d(10, 10));
        simulatedGame.spawnEnemyUnit(UnitType.ZEALOT,           new Point2d(12, 10));
        simulatedGame.spawnEnemyUnit(UnitType.STALKER,          new Point2d(14, 10));

        // z=12: Protoss ground A
        simulatedGame.spawnEnemyUnit(UnitType.SENTRY,           new Point2d(2,  12));
        simulatedGame.spawnEnemyUnit(UnitType.ADEPT,            new Point2d(4,  12));
        simulatedGame.spawnEnemyUnit(UnitType.DARK_TEMPLAR,     new Point2d(6,  12));
        simulatedGame.spawnEnemyUnit(UnitType.HIGH_TEMPLAR,     new Point2d(8,  12));
        simulatedGame.spawnEnemyUnit(UnitType.ARCHON,           new Point2d(10, 12));
        simulatedGame.spawnEnemyUnit(UnitType.DISRUPTOR,        new Point2d(12, 12));
        simulatedGame.spawnEnemyUnit(UnitType.IMMORTAL,         new Point2d(14, 12));

        // z=14: Protoss ground B + Zerg start
        simulatedGame.spawnEnemyUnit(UnitType.COLOSSUS,         new Point2d(2,  14));
        simulatedGame.spawnEnemyUnit(UnitType.ADEPT_PHASE_SHIFT, new Point2d(4, 14));
        simulatedGame.spawnEnemyUnit(UnitType.ZERGLING,         new Point2d(6,  14));
        simulatedGame.spawnEnemyUnit(UnitType.ROACH,            new Point2d(8,  14));
        simulatedGame.spawnEnemyUnit(UnitType.HYDRALISK,        new Point2d(10, 14));
        simulatedGame.spawnEnemyUnit(UnitType.MUTALISK,         new Point2d(12, 14));
        simulatedGame.spawnEnemyUnit(UnitType.RAVAGER,          new Point2d(14, 14));

        // z=16: Zerg ground
        simulatedGame.spawnEnemyUnit(UnitType.LURKER,           new Point2d(2,  16));
        simulatedGame.spawnEnemyUnit(UnitType.INFESTOR,         new Point2d(4,  16));
        simulatedGame.spawnEnemyUnit(UnitType.SWARM_HOST,       new Point2d(6,  16));
        simulatedGame.spawnEnemyUnit(UnitType.QUEEN,            new Point2d(8,  16));
        simulatedGame.spawnEnemyUnit(UnitType.ULTRALISK,        new Point2d(10, 16));
        simulatedGame.spawnEnemyUnit(UnitType.DRONE,            new Point2d(12, 16));
        simulatedGame.spawnEnemyUnit(UnitType.BANELING,         new Point2d(14, 16));

        // z=18: Zerg ground B + spawned
        simulatedGame.spawnEnemyUnit(UnitType.BROODLING,        new Point2d(2,  18));
        simulatedGame.spawnEnemyUnit(UnitType.INFESTED_TERRAN,  new Point2d(4,  18));
        simulatedGame.spawnEnemyUnit(UnitType.CHANGELING,       new Point2d(6,  18));
        simulatedGame.spawnEnemyUnit(UnitType.OVERLORD,         new Point2d(8,  18));
        simulatedGame.spawnEnemyUnit(UnitType.OVERSEER,         new Point2d(10, 18));
        simulatedGame.spawnEnemyUnit(UnitType.CORRUPTOR,        new Point2d(12, 18));
        simulatedGame.spawnEnemyUnit(UnitType.VIPER,            new Point2d(14, 18));

        // z=20: Zerg air remainder
        simulatedGame.spawnEnemyUnit(UnitType.BROOD_LORD,       new Point2d(2,  20));
        simulatedGame.spawnEnemyUnit(UnitType.LOCUST,           new Point2d(4,  20));

        engine.observe();

        return Response.ok(Map.of(
            "status",  "showcase seeded",
            "enemies", "65 units: Terran(22) + Protoss(22) + Zerg(21)"
        )).build();
    }
}
