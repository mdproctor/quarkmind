package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.Resource;
import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.sc2.mock.SimulatedGame;

import java.util.Map;

/**
 * Seeds a showcase layout — one of every sprite type (65 units + 9 buildings, all 3 races).
 * Probe observers at (4,5),(11,5),(4,15),(11,15) cover the unit grid (z=2..20).
 * Two more at (6,22),(14,22) cover the building row (z=22).
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

        // Building rows use z=22..34 (units occupy z=2..20).
        // Probes at (6,22),(14,22) cover the z=22 Protoss row.
        // Probes at (5,27),(13,27),(5,33),(13,33) cover z=24..34 rows.
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(6,  22));
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(14, 22));
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(5,  27));
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(13, 27));
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(5,  33));
        simulatedGame.spawnFriendlyUnitForTesting(UnitType.PROBE, new Point2d(13, 33));

        // z=22: Original 9 Protoss buildings
        simulatedGame.spawnBuildingForTesting(BuildingType.NEXUS,             new Point2d(2,  22));
        simulatedGame.spawnBuildingForTesting(BuildingType.PYLON,             new Point2d(4,  22));
        simulatedGame.spawnBuildingForTesting(BuildingType.GATEWAY,           new Point2d(6,  22));
        simulatedGame.spawnBuildingForTesting(BuildingType.CYBERNETICS_CORE,  new Point2d(8,  22));
        simulatedGame.spawnBuildingForTesting(BuildingType.ASSIMILATOR,       new Point2d(10, 22));
        simulatedGame.spawnBuildingForTesting(BuildingType.ROBOTICS_FACILITY, new Point2d(12, 22));
        simulatedGame.spawnBuildingForTesting(BuildingType.STARGATE,          new Point2d(14, 22));
        simulatedGame.spawnBuildingForTesting(BuildingType.FORGE,             new Point2d(16, 22));
        simulatedGame.spawnBuildingForTesting(BuildingType.TWILIGHT_COUNCIL,  new Point2d(18, 22));

        // z=24: New Protoss buildings (6)
        simulatedGame.spawnBuildingForTesting(BuildingType.PHOTON_CANNON,     new Point2d(2,  24));
        simulatedGame.spawnBuildingForTesting(BuildingType.SHIELD_BATTERY,    new Point2d(4,  24));
        simulatedGame.spawnBuildingForTesting(BuildingType.DARK_SHRINE,       new Point2d(6,  24));
        simulatedGame.spawnBuildingForTesting(BuildingType.TEMPLAR_ARCHIVES,  new Point2d(8,  24));
        simulatedGame.spawnBuildingForTesting(BuildingType.FLEET_BEACON,      new Point2d(10, 24));
        simulatedGame.spawnBuildingForTesting(BuildingType.ROBOTICS_BAY,      new Point2d(12, 24));

        // z=26: Terran row 1 (8 buildings)
        simulatedGame.spawnBuildingForTesting(BuildingType.COMMAND_CENTER,    new Point2d(2,  26));
        simulatedGame.spawnBuildingForTesting(BuildingType.ORBITAL_COMMAND,   new Point2d(4,  26));
        simulatedGame.spawnBuildingForTesting(BuildingType.PLANETARY_FORTRESS,new Point2d(6,  26));
        simulatedGame.spawnBuildingForTesting(BuildingType.SUPPLY_DEPOT,      new Point2d(8,  26));
        simulatedGame.spawnBuildingForTesting(BuildingType.BARRACKS,          new Point2d(10, 26));
        simulatedGame.spawnBuildingForTesting(BuildingType.ENGINEERING_BAY,   new Point2d(12, 26));
        simulatedGame.spawnBuildingForTesting(BuildingType.ARMORY,            new Point2d(14, 26));
        simulatedGame.spawnBuildingForTesting(BuildingType.MISSILE_TURRET,    new Point2d(16, 26));

        // z=28: Terran row 2 (7 buildings)
        simulatedGame.spawnBuildingForTesting(BuildingType.BUNKER,            new Point2d(2,  28));
        simulatedGame.spawnBuildingForTesting(BuildingType.SENSOR_TOWER,      new Point2d(4,  28));
        simulatedGame.spawnBuildingForTesting(BuildingType.GHOST_ACADEMY,     new Point2d(6,  28));
        simulatedGame.spawnBuildingForTesting(BuildingType.FACTORY,           new Point2d(8,  28));
        simulatedGame.spawnBuildingForTesting(BuildingType.STARPORT,          new Point2d(10, 28));
        simulatedGame.spawnBuildingForTesting(BuildingType.FUSION_CORE,       new Point2d(12, 28));
        simulatedGame.spawnBuildingForTesting(BuildingType.REFINERY,          new Point2d(14, 28));

        // z=32: Zerg row 1 (9 buildings)
        simulatedGame.spawnBuildingForTesting(BuildingType.HATCHERY,          new Point2d(2,  32));
        simulatedGame.spawnBuildingForTesting(BuildingType.LAIR,              new Point2d(4,  32));
        simulatedGame.spawnBuildingForTesting(BuildingType.HIVE,              new Point2d(6,  32));
        simulatedGame.spawnBuildingForTesting(BuildingType.SPAWNING_POOL,     new Point2d(8,  32));
        simulatedGame.spawnBuildingForTesting(BuildingType.EVOLUTION_CHAMBER, new Point2d(10, 32));
        simulatedGame.spawnBuildingForTesting(BuildingType.ROACH_WARREN,      new Point2d(12, 32));
        simulatedGame.spawnBuildingForTesting(BuildingType.BANELING_NEST,     new Point2d(14, 32));
        simulatedGame.spawnBuildingForTesting(BuildingType.SPINE_CRAWLER,     new Point2d(16, 32));
        simulatedGame.spawnBuildingForTesting(BuildingType.SPORE_CRAWLER,     new Point2d(18, 32));

        // z=34: Zerg row 2 (9 buildings)
        simulatedGame.spawnBuildingForTesting(BuildingType.HYDRALISK_DEN,     new Point2d(2,  34));
        simulatedGame.spawnBuildingForTesting(BuildingType.LURKER_DEN,        new Point2d(4,  34));
        simulatedGame.spawnBuildingForTesting(BuildingType.INFESTATION_PIT,   new Point2d(6,  34));
        simulatedGame.spawnBuildingForTesting(BuildingType.SPIRE,             new Point2d(8,  34));
        simulatedGame.spawnBuildingForTesting(BuildingType.GREATER_SPIRE,     new Point2d(10, 34));
        simulatedGame.spawnBuildingForTesting(BuildingType.NYDUS_NETWORK,     new Point2d(12, 34));
        simulatedGame.spawnBuildingForTesting(BuildingType.NYDUS_CANAL,       new Point2d(14, 34));
        simulatedGame.spawnBuildingForTesting(BuildingType.ULTRALISK_CAVERN,  new Point2d(16, 34));
        simulatedGame.spawnBuildingForTesting(BuildingType.EXTRACTOR,         new Point2d(18, 34));

        // z=36: 8 mineral patches in a row (within map bounds: worldZ=36*0.7-22.4=2.8)
        for (int i = 0; i < 8; i++) {
            simulatedGame.spawnMineralPatchForTesting(new Point2d(2 + i * 2, 36), 1500);
        }

        // z=38: 1 enemy Hatchery (demonstrates enemy building + creep rendering)
        // x≥12 ensures the CREEP_RADIUS=10 circle stays within worldX ≥ -23
        simulatedGame.spawnEnemyBuildingForTesting(BuildingType.HATCHERY, new Point2d(12, 38));

        engine.observe();

        return Response.ok(Map.of(
            "status",         "showcase seeded",
            "enemies",        "65 units: Terran(22) + Protoss(22) + Zerg(21)",
            "buildings",      "49 buildings: 1 Nexus (reset) + 9 Protoss + 6 new Protoss + 15 Terran + 18 Zerg",
            "minerals",       "8 mineral patches at z=36",
            "enemyBuildings", "1 Hatchery at z=38"
        )).build();
    }
}
