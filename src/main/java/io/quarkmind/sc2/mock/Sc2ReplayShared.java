package io.quarkmind.sc2.mock;

import io.quarkmind.domain.*;
import java.util.Set;

/** Shared constants and lookup tables for replay-based SimulatedGame implementations. */
final class Sc2ReplayShared {
    private Sc2ReplayShared() {}

    static final int LOOPS_PER_TICK = 22;

    static final Set<String> BUILDING_NAMES = Set.of(
        // Protoss
        "Nexus", "Pylon", "Gateway", "WarpGate", "CyberneticsCore", "Assimilator",
        "RoboticsFacility", "Stargate", "Forge", "TwilightCouncil",
        "PhotonCannon", "ShieldBattery", "RoboticsBay", "FleetBeacon",
        "TemplarArchives", "DarkShrine",
        // Terran
        "CommandCenter", "OrbitalCommand", "PlanetaryFortress",
        "SupplyDepot", "SupplyDepotLowered", "Barracks", "BarracksTechLab", "BarracksReactor",
        "EngineeringBay", "Armory", "MissileTurret", "Bunker", "SensorTower",
        "GhostAcademy", "Factory", "FactoryTechLab", "FactoryReactor",
        "Starport", "StarportTechLab", "StarportReactor", "FusionCore", "Refinery",
        // Terran flying variants
        "OrbitalCommandFlying", "BarracksFlying", "FactoryFlying", "StarportFlying",
        // Zerg
        "Hatchery", "Lair", "Hive", "SpawningPool", "EvolutionChamber",
        "RoachWarren", "BanelingNest", "SpineCrawler", "SpineCrawlerUprooted",
        "SporeCrawler", "SporeCrawlerUprooted", "HydraliskDen", "LurkerDen",
        "InfestationPit", "Spire", "GreaterSpire", "NydusNetwork", "NydusCanal",
        "UltraliskCavern", "Extractor"
    );

    static UnitType toUnitType(String name) {
        return switch (name) {
            // Protoss
            case "Probe"                                -> UnitType.PROBE;
            case "Zealot"                               -> UnitType.ZEALOT;
            case "Stalker"                              -> UnitType.STALKER;
            case "Immortal"                             -> UnitType.IMMORTAL;
            case "Colossus"                             -> UnitType.COLOSSUS;
            case "Carrier"                              -> UnitType.CARRIER;
            case "DarkTemplar"                          -> UnitType.DARK_TEMPLAR;
            case "HighTemplar"                          -> UnitType.HIGH_TEMPLAR;
            case "Archon"                               -> UnitType.ARCHON;
            case "Observer"                             -> UnitType.OBSERVER;
            case "VoidRay"                              -> UnitType.VOID_RAY;
            case "Adept"                                -> UnitType.ADEPT;
            case "Disruptor"                            -> UnitType.DISRUPTOR;
            case "Sentry"                               -> UnitType.SENTRY;
            case "Phoenix"                              -> UnitType.PHOENIX;
            case "Oracle"                               -> UnitType.ORACLE;
            case "Tempest"                              -> UnitType.TEMPEST;
            case "Mothership"                           -> UnitType.MOTHERSHIP;
            case "WarpPrism"                            -> UnitType.WARP_PRISM;
            case "WarpPrismPhasing"                     -> UnitType.WARP_PRISM_PHASING;
            case "Interceptor"                          -> UnitType.INTERCEPTOR;
            case "AdeptPhaseShift"                      -> UnitType.ADEPT_PHASE_SHIFT;
            // Terran
            case "Marine"                               -> UnitType.MARINE;
            case "Marauder"                             -> UnitType.MARAUDER;
            case "Medivac"                              -> UnitType.MEDIVAC;
            case "SiegeTank"                            -> UnitType.SIEGE_TANK;
            case "SiegeTankSieged"                      -> UnitType.SIEGE_TANK_SIEGED;
            case "Thor", "ThorAP"                       -> UnitType.THOR;
            case "VikingFighter"                        -> UnitType.VIKING;
            case "VikingAssault"                        -> UnitType.VIKING_ASSAULT;
            case "Ghost"                                -> UnitType.GHOST;
            case "Raven"                                -> UnitType.RAVEN;
            case "Banshee"                              -> UnitType.BANSHEE;
            case "Battlecruiser"                        -> UnitType.BATTLECRUISER;
            case "Cyclone"                              -> UnitType.CYCLONE;
            case "Liberator"                            -> UnitType.LIBERATOR;
            case "LiberatorAG"                          -> UnitType.LIBERATOR_AG;
            case "WidowMine", "WidowMineBurrowed"       -> UnitType.WIDOW_MINE;
            case "SCV"                                  -> UnitType.SCV;
            case "Reaper"                               -> UnitType.REAPER;
            case "Hellion"                              -> UnitType.HELLION;
            case "Hellbat"                              -> UnitType.HELLBAT;
            case "MULE"                                 -> UnitType.MULE;
            case "AutoTurret"                           -> UnitType.AUTO_TURRET;
            // Zerg
            case "Zergling"                             -> UnitType.ZERGLING;
            case "Roach"                                -> UnitType.ROACH;
            case "Hydralisk"                            -> UnitType.HYDRALISK;
            case "Mutalisk"                             -> UnitType.MUTALISK;
            case "Ultralisk"                            -> UnitType.ULTRALISK;
            case "BroodLord"                            -> UnitType.BROOD_LORD;
            case "Corruptor"                            -> UnitType.CORRUPTOR;
            case "Infestor"                             -> UnitType.INFESTOR;
            case "SwarmHostMP"                          -> UnitType.SWARM_HOST;
            case "Viper"                                -> UnitType.VIPER;
            case "Queen"                                -> UnitType.QUEEN;
            case "Ravager"                              -> UnitType.RAVAGER;
            case "Lurker", "LurkerMP"                   -> UnitType.LURKER;
            case "Drone"                                -> UnitType.DRONE;
            case "Overlord"                             -> UnitType.OVERLORD;
            case "Overseer"                             -> UnitType.OVERSEER;
            case "Baneling"                             -> UnitType.BANELING;
            case "LocustMP", "LocustMPFlying"           -> UnitType.LOCUST;
            case "Broodling"                            -> UnitType.BROODLING;
            case "InfestedTerranEgg", "InfestedTerran"  -> UnitType.INFESTED_TERRAN;
            case "Changeling", "ChangelingZealot",
                 "ChangelingZealotShield", "ChangelingMarine",
                 "ChangelingMarineShield", "ChangelingZergling",
                 "ChangelingZerg"                       -> UnitType.CHANGELING;
            default                                     -> UnitType.UNKNOWN;
        };
    }

    static BuildingType toBuildingType(String name) {
        return switch (name) {
            // Protoss
            case "Nexus"                          -> BuildingType.NEXUS;
            case "Pylon"                          -> BuildingType.PYLON;
            case "Gateway", "WarpGate"            -> BuildingType.GATEWAY;
            case "CyberneticsCore"                -> BuildingType.CYBERNETICS_CORE;
            case "Assimilator"                    -> BuildingType.ASSIMILATOR;
            case "RoboticsFacility"               -> BuildingType.ROBOTICS_FACILITY;
            case "Stargate"                       -> BuildingType.STARGATE;
            case "Forge"                          -> BuildingType.FORGE;
            case "TwilightCouncil"                -> BuildingType.TWILIGHT_COUNCIL;
            case "PhotonCannon"                   -> BuildingType.PHOTON_CANNON;
            case "ShieldBattery"                  -> BuildingType.SHIELD_BATTERY;
            case "DarkShrine"                     -> BuildingType.DARK_SHRINE;
            case "TemplarArchives"                -> BuildingType.TEMPLAR_ARCHIVES;
            case "FleetBeacon"                    -> BuildingType.FLEET_BEACON;
            case "RoboticsBay"                    -> BuildingType.ROBOTICS_BAY;
            // Terran
            case "CommandCenter"                  -> BuildingType.COMMAND_CENTER;
            case "OrbitalCommand",
                 "OrbitalCommandFlying"           -> BuildingType.ORBITAL_COMMAND;
            case "PlanetaryFortress"              -> BuildingType.PLANETARY_FORTRESS;
            case "SupplyDepot",
                 "SupplyDepotLowered"             -> BuildingType.SUPPLY_DEPOT;
            case "Barracks", "BarracksTechLab",
                 "BarracksReactor", "BarracksFlying" -> BuildingType.BARRACKS;
            case "EngineeringBay"                 -> BuildingType.ENGINEERING_BAY;
            case "Armory"                         -> BuildingType.ARMORY;
            case "MissileTurret"                  -> BuildingType.MISSILE_TURRET;
            case "Bunker"                         -> BuildingType.BUNKER;
            case "SensorTower"                    -> BuildingType.SENSOR_TOWER;
            case "GhostAcademy"                   -> BuildingType.GHOST_ACADEMY;
            case "Factory", "FactoryTechLab",
                 "FactoryReactor", "FactoryFlying" -> BuildingType.FACTORY;
            case "Starport", "StarportTechLab",
                 "StarportReactor", "StarportFlying" -> BuildingType.STARPORT;
            case "FusionCore"                     -> BuildingType.FUSION_CORE;
            case "Refinery"                       -> BuildingType.REFINERY;
            // Zerg
            case "Hatchery"                       -> BuildingType.HATCHERY;
            case "Lair"                           -> BuildingType.LAIR;
            case "Hive"                           -> BuildingType.HIVE;
            case "SpawningPool"                   -> BuildingType.SPAWNING_POOL;
            case "EvolutionChamber"               -> BuildingType.EVOLUTION_CHAMBER;
            case "RoachWarren"                    -> BuildingType.ROACH_WARREN;
            case "BanelingNest"                   -> BuildingType.BANELING_NEST;
            case "SpineCrawler",
                 "SpineCrawlerUprooted"           -> BuildingType.SPINE_CRAWLER;
            case "SporeCrawler",
                 "SporeCrawlerUprooted"           -> BuildingType.SPORE_CRAWLER;
            case "HydraliskDen"                   -> BuildingType.HYDRALISK_DEN;
            case "LurkerDen"                      -> BuildingType.LURKER_DEN;
            case "InfestationPit"                 -> BuildingType.INFESTATION_PIT;
            case "Spire"                          -> BuildingType.SPIRE;
            case "GreaterSpire"                   -> BuildingType.GREATER_SPIRE;
            case "NydusNetwork"                   -> BuildingType.NYDUS_NETWORK;
            case "NydusCanal"                     -> BuildingType.NYDUS_CANAL;
            case "UltraliskCavern"                -> BuildingType.ULTRALISK_CAVERN;
            case "Extractor"                      -> BuildingType.EXTRACTOR;
            default                               -> BuildingType.UNKNOWN;
        };
    }

    static int defaultUnitHealth(UnitType type) {
        return switch (type) {
            case PROBE        ->  45;
            case ZEALOT       -> 100;
            case STALKER      ->  80;
            case IMMORTAL     -> 200;
            case COLOSSUS     -> 200;
            case OBSERVER     ->  40;
            case MARINE       ->  45;
            case MARAUDER     -> 125;
            case MEDIVAC      -> 150;
            case SIEGE_TANK   -> 175;
            case ROACH        -> 145;
            case HYDRALISK    ->  90;
            case ZERGLING     ->  35;
            case MUTALISK     -> 120;
            case QUEEN        -> 175;
            default           -> 100;
        };
    }

    static int defaultBuildingHealth(BuildingType type) {
        return switch (type) {
            case NEXUS             -> 1500;
            case PYLON             ->  200;
            case GATEWAY           ->  500;
            case CYBERNETICS_CORE  ->  550;
            case ASSIMILATOR       ->  450;
            case ROBOTICS_FACILITY ->  500;
            case STARGATE          ->  600;
            case FORGE             ->  400;
            case TWILIGHT_COUNCIL  ->  500;
            default                ->  400;
        };
    }
}
