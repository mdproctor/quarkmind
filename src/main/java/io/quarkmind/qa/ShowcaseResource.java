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

        // ── TERRAN ──────────────────────────────────────────────────
        // Air (z=4)
        simulatedGame.spawnEnemyUnit(UnitType.MEDIVAC,       new Point2d(4,  4));
        simulatedGame.spawnEnemyUnit(UnitType.VIKING,         new Point2d(6,  4));
        simulatedGame.spawnEnemyUnit(UnitType.RAVEN,          new Point2d(8,  4));
        simulatedGame.spawnEnemyUnit(UnitType.BANSHEE,        new Point2d(10, 4));
        simulatedGame.spawnEnemyUnit(UnitType.LIBERATOR,      new Point2d(12, 4));
        simulatedGame.spawnEnemyUnit(UnitType.BATTLECRUISER,  new Point2d(14, 4));

        // Ground (z=7)
        simulatedGame.spawnEnemyUnit(UnitType.MARINE,         new Point2d(4,  7));
        simulatedGame.spawnEnemyUnit(UnitType.MARAUDER,       new Point2d(6,  7));
        simulatedGame.spawnEnemyUnit(UnitType.GHOST,          new Point2d(8,  7));
        simulatedGame.spawnEnemyUnit(UnitType.CYCLONE,        new Point2d(10, 7));
        simulatedGame.spawnEnemyUnit(UnitType.WIDOW_MINE,     new Point2d(12, 7));
        simulatedGame.spawnEnemyUnit(UnitType.SIEGE_TANK,     new Point2d(14, 7));
        simulatedGame.spawnEnemyUnit(UnitType.THOR,           new Point2d(16, 7));

        // ── PROTOSS ─────────────────────────────────────────────────
        // Air (z=10)
        simulatedGame.spawnEnemyUnit(UnitType.OBSERVER,       new Point2d(4,  10));
        simulatedGame.spawnEnemyUnit(UnitType.VOID_RAY,       new Point2d(6,  10));
        simulatedGame.spawnEnemyUnit(UnitType.CARRIER,        new Point2d(8,  10));

        // Ground A (z=13)
        simulatedGame.spawnEnemyUnit(UnitType.PROBE,          new Point2d(4,  13));
        simulatedGame.spawnEnemyUnit(UnitType.ZEALOT,         new Point2d(6,  13));
        simulatedGame.spawnEnemyUnit(UnitType.STALKER,        new Point2d(8,  13));
        simulatedGame.spawnEnemyUnit(UnitType.SENTRY,         new Point2d(10, 13));
        simulatedGame.spawnEnemyUnit(UnitType.ADEPT,          new Point2d(12, 13));
        simulatedGame.spawnEnemyUnit(UnitType.DARK_TEMPLAR,   new Point2d(14, 13));

        // Ground B (z=16)
        simulatedGame.spawnEnemyUnit(UnitType.HIGH_TEMPLAR,   new Point2d(4,  16));
        simulatedGame.spawnEnemyUnit(UnitType.ARCHON,         new Point2d(6,  16));
        simulatedGame.spawnEnemyUnit(UnitType.DISRUPTOR,      new Point2d(8,  16));
        simulatedGame.spawnEnemyUnit(UnitType.IMMORTAL,       new Point2d(10, 16));
        simulatedGame.spawnEnemyUnit(UnitType.COLOSSUS,       new Point2d(12, 16));

        // ── ZERG ────────────────────────────────────────────────────
        // Ground (z=19)
        simulatedGame.spawnEnemyUnit(UnitType.ZERGLING,       new Point2d(3,  19));
        simulatedGame.spawnEnemyUnit(UnitType.ROACH,          new Point2d(5,  19));
        simulatedGame.spawnEnemyUnit(UnitType.HYDRALISK,      new Point2d(7,  19));
        simulatedGame.spawnEnemyUnit(UnitType.RAVAGER,        new Point2d(9,  19));
        simulatedGame.spawnEnemyUnit(UnitType.LURKER,         new Point2d(11, 19));
        simulatedGame.spawnEnemyUnit(UnitType.INFESTOR,       new Point2d(13, 19));
        simulatedGame.spawnEnemyUnit(UnitType.SWARM_HOST,     new Point2d(15, 19));
        simulatedGame.spawnEnemyUnit(UnitType.QUEEN,          new Point2d(17, 19));
        simulatedGame.spawnEnemyUnit(UnitType.ULTRALISK,      new Point2d(19, 19));

        // Air (z=22)
        simulatedGame.spawnEnemyUnit(UnitType.MUTALISK,       new Point2d(4,  22));
        simulatedGame.spawnEnemyUnit(UnitType.CORRUPTOR,      new Point2d(6,  22));
        simulatedGame.spawnEnemyUnit(UnitType.VIPER,          new Point2d(8,  22));
        simulatedGame.spawnEnemyUnit(UnitType.BROOD_LORD,     new Point2d(10, 22));

        engine.observe();

        return Response.ok(Map.of(
            "status",  "showcase seeded",
            "enemies", "40 units: Terran(13) + Protoss(14) + Zerg(13)"
        )).build();
    }
}
