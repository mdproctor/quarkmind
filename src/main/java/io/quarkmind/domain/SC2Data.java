package io.quarkmind.domain;

import java.util.Set;
import static io.quarkmind.domain.UnitAttribute.*;

public final class SC2Data {

    private SC2Data() {}

    /** Minerals generated per mining probe per game tick at Faster speed (22.4 loops/sec). */
    public static final double MINERALS_PER_PROBE_PER_TICK = 50.0 / 60.0 / 22.4; // ≈ 0.0372

    public static final int INITIAL_MINERALS  = 50;
    public static final int INITIAL_VESPENE   = 0;
    public static final int INITIAL_SUPPLY    = 15;
    public static final int INITIAL_SUPPLY_USED = 12;
    public static final int INITIAL_PROBES    = 12;

    public static int trainTimeInTicks(UnitType type) {
        return switch (type) {
            case PROBE    -> 12;
            case ZEALOT   -> 28;
            case STALKER  -> 31;
            case IMMORTAL -> 40;
            case OBSERVER -> 22;
            default       -> 30;
        };
    }

    public static int buildTimeInTicks(BuildingType type) {
        return switch (type) {
            // Protoss
            case PYLON             -> 18;
            case GATEWAY           -> 47;
            case CYBERNETICS_CORE  -> 37;
            case ASSIMILATOR       -> 21;
            case ROBOTICS_FACILITY -> 47;
            case STARGATE          -> 44;
            case FORGE             -> 30;
            case TWILIGHT_COUNCIL  -> 37;
            case PHOTON_CANNON     -> 25;
            case SHIELD_BATTERY    -> 22;
            case DARK_SHRINE       -> 71;
            case TEMPLAR_ARCHIVES  -> 37;
            case FLEET_BEACON      -> 37;
            case ROBOTICS_BAY      -> 30;
            // Terran
            case COMMAND_CENTER    -> 60;
            case ORBITAL_COMMAND   -> 25;
            case PLANETARY_FORTRESS -> 30;
            case SUPPLY_DEPOT      -> 18;
            case BARRACKS          -> 40;
            case ENGINEERING_BAY   -> 22;
            case ARMORY            -> 40;
            case MISSILE_TURRET    -> 16;
            case BUNKER            -> 25;
            case SENSOR_TOWER      -> 16;
            case GHOST_ACADEMY     -> 25;
            case FACTORY           -> 37;
            case STARPORT          -> 25;
            case FUSION_CORE       -> 40;
            case REFINERY          -> 18;
            // Zerg
            case HATCHERY          -> 60;
            case LAIR              -> 57;
            case HIVE              -> 57;
            case SPAWNING_POOL     -> 46;
            case EVOLUTION_CHAMBER -> 25;
            case ROACH_WARREN      -> 46;
            case BANELING_NEST     -> 43;
            case SPINE_CRAWLER     -> 36;
            case SPORE_CRAWLER     -> 21;
            case HYDRALISK_DEN     -> 29;
            case LURKER_DEN        -> 57;
            case INFESTATION_PIT   -> 46;
            case SPIRE             -> 71;
            case GREATER_SPIRE     -> 71;
            case NYDUS_NETWORK     -> 21;
            case NYDUS_CANAL       -> 11;
            case ULTRALISK_CAVERN  -> 46;
            case EXTRACTOR         -> 18;
            default                -> 40;
        };
    }

    public static int supplyCost(UnitType type) {
        return switch (type) {
            case PROBE    -> 1;
            case ZEALOT   -> 2;
            case STALKER  -> 2;
            case IMMORTAL -> 4;
            default       -> 2;
        };
    }

    public static int supplyBonus(BuildingType type) {
        return switch (type) {
            case PYLON         -> 8;
            case SUPPLY_DEPOT  -> 8;
            case HATCHERY, LAIR, HIVE -> 6;
            default            -> 0;
        };
    }

    public static int maxHealth(UnitType type) {
        return switch (type) {
            case PROBE     ->  45;
            case ZEALOT    -> 100;
            case STALKER   ->  80;
            case IMMORTAL  -> 200;
            case MARINE    ->  45;
            case MARAUDER  -> 125;
            case ROACH     -> 145;
            case HYDRALISK ->  90;
            default        -> 100;
        };
    }

    public static Set<UnitAttribute> unitAttributes(UnitType type) {
        return switch (type) {
            case PROBE     -> Set.of(LIGHT, MECHANICAL);
            case ZEALOT    -> Set.of(LIGHT, BIOLOGICAL);
            case STALKER   -> Set.of(ARMORED, MECHANICAL);
            case IMMORTAL  -> Set.of(ARMORED, MECHANICAL, MASSIVE);
            case OBSERVER  -> Set.of(ARMORED, MECHANICAL);
            case MARINE    -> Set.of(LIGHT, BIOLOGICAL);
            case MARAUDER  -> Set.of(BIOLOGICAL, ARMORED);
            case ROACH     -> Set.of(ARMORED, BIOLOGICAL);
            case HYDRALISK -> Set.of(LIGHT, BIOLOGICAL);
            default        -> Set.of();
        };
    }

    public static int armour(UnitType type) {
        return switch (type) {
            case ZEALOT, STALKER, IMMORTAL, MARAUDER, ROACH -> 1;
            default -> 0;
        };
    }

    public static int bonusDamageVs(UnitType attackerType, UnitAttribute targetAttribute) {
        return switch (attackerType) {
            case STALKER  -> targetAttribute == ARMORED ? 4  : 0;
            case IMMORTAL -> targetAttribute == ARMORED ? 3  : 0;
            case MARAUDER -> targetAttribute == ARMORED ? 10 : 0;
            default       -> 0;
        };
    }

    public static boolean hasHardenedShield(UnitType type) {
        return type == UnitType.IMMORTAL;
    }

    public static int maxShields(UnitType type) {
        return switch (type) {
            case PROBE    -> 20;
            case ZEALOT   -> 50;
            case STALKER  -> 80;
            case IMMORTAL -> 100;
            case OBSERVER -> 20;
            case VOID_RAY -> 100;
            default       -> 0;   // Terran/Zerg have no shields
        };
    }

    public static int maxBuildingHealth(BuildingType type) {
        return switch (type) {
            // Protoss
            case NEXUS              -> 1500;
            case PYLON              -> 200;
            case GATEWAY            -> 500;
            case CYBERNETICS_CORE   -> 500;
            case ASSIMILATOR        -> 450;
            case ROBOTICS_FACILITY  -> 500;
            case STARGATE           -> 600;
            case FORGE              -> 550;
            case TWILIGHT_COUNCIL   -> 500;
            case PHOTON_CANNON      -> 150;
            case SHIELD_BATTERY     -> 200;
            case DARK_SHRINE        -> 500;
            case TEMPLAR_ARCHIVES   -> 500;
            case FLEET_BEACON       -> 500;
            case ROBOTICS_BAY       -> 500;
            // Terran
            case COMMAND_CENTER     -> 1500;
            case ORBITAL_COMMAND    -> 1500;
            case PLANETARY_FORTRESS -> 1500;
            case SUPPLY_DEPOT       -> 400;
            case BARRACKS           -> 1000;
            case ENGINEERING_BAY    -> 850;
            case ARMORY             -> 750;
            case MISSILE_TURRET     -> 250;
            case BUNKER             -> 400;
            case SENSOR_TOWER       -> 200;
            case GHOST_ACADEMY      -> 1250;
            case FACTORY            -> 1250;
            case STARPORT           -> 1000;
            case FUSION_CORE        -> 750;
            case REFINERY           -> 500;
            // Zerg
            case HATCHERY           -> 1500;
            case LAIR               -> 2000;
            case HIVE               -> 2500;
            case SPAWNING_POOL      -> 750;
            case EVOLUTION_CHAMBER  -> 750;
            case ROACH_WARREN       -> 850;
            case BANELING_NEST      -> 850;
            case SPINE_CRAWLER      -> 300;
            case SPORE_CRAWLER      -> 400;
            case HYDRALISK_DEN      -> 850;
            case LURKER_DEN         -> 850;
            case INFESTATION_PIT    -> 850;
            case SPIRE              -> 850;
            case GREATER_SPIRE      -> 1000;
            case NYDUS_NETWORK      -> 850;
            case NYDUS_CANAL        -> 250;
            case ULTRALISK_CAVERN   -> 850;
            case EXTRACTOR          -> 500;
            default                 -> 500;
        };
    }

    public static int mineralCost(UnitType type) {
        return switch (type) {
            case PROBE    -> 50;
            case ZEALOT   -> 100;
            case STALKER  -> 125;
            case IMMORTAL -> 250;
            case OBSERVER -> 25;
            default       -> 100;
        };
    }

    public static int mineralCost(BuildingType type) {
        return switch (type) {
            // Protoss
            case NEXUS              -> 400;
            case PYLON              -> 100;
            case GATEWAY            -> 150;
            case CYBERNETICS_CORE   -> 150;
            case ASSIMILATOR        -> 75;
            case ROBOTICS_FACILITY  -> 200;
            case STARGATE           -> 150;
            case FORGE              -> 150;
            case TWILIGHT_COUNCIL   -> 150;
            case PHOTON_CANNON      -> 150;
            case SHIELD_BATTERY     -> 100;
            case DARK_SHRINE        -> 150;
            case TEMPLAR_ARCHIVES   -> 150;
            case FLEET_BEACON       -> 300;
            case ROBOTICS_BAY       -> 200;
            // Terran
            case COMMAND_CENTER     -> 400;
            case ORBITAL_COMMAND    -> 150;
            case PLANETARY_FORTRESS -> 150;
            case SUPPLY_DEPOT       -> 100;
            case BARRACKS           -> 150;
            case ENGINEERING_BAY    -> 125;
            case ARMORY             -> 150;
            case MISSILE_TURRET     -> 100;
            case BUNKER             -> 100;
            case SENSOR_TOWER       -> 125;
            case GHOST_ACADEMY      -> 150;
            case FACTORY            -> 150;
            case STARPORT           -> 150;
            case FUSION_CORE        -> 150;
            case REFINERY           -> 75;
            // Zerg
            case HATCHERY           -> 300;
            case LAIR               -> 150;
            case HIVE               -> 200;
            case SPAWNING_POOL      -> 200;
            case EVOLUTION_CHAMBER  -> 75;
            case ROACH_WARREN       -> 150;
            case BANELING_NEST      -> 100;
            case SPINE_CRAWLER      -> 100;
            case SPORE_CRAWLER      -> 75;
            case HYDRALISK_DEN      -> 100;
            case LURKER_DEN         -> 150;
            case INFESTATION_PIT    -> 100;
            case SPIRE              -> 200;
            case GREATER_SPIRE      -> 100;
            case NYDUS_NETWORK      -> 150;
            case NYDUS_CANAL        -> 50;
            case ULTRALISK_CAVERN   -> 150;
            case EXTRACTOR          -> 25;
            default                 -> 100;
        };
    }

    public static int gasCost(UnitType type) {
        return switch (type) {
            case STALKER  -> 50;
            case IMMORTAL -> 100;
            case OBSERVER -> 75;
            default       -> 0;
        };
    }

    /**
     * Damage dealt per attack event (replaces damagePerTick from E3).
     * Phase E4: units fire at cooldown intervals, not every tick.
     *
     * <p><b>Note: these are simplified/balanced values, not exact SC2 numbers.</b>
     * Real SC2 stats (e.g. Immortal: 50+100 vs Armored, Stalker: 13+5 vs Armored)
     * are scaled down to keep simulation legible at 500ms/tick. Values are tuned
     * for realistic fight outcomes, not stat-sheet accuracy.
     */
    public static int damagePerAttack(UnitType type) {
        return switch (type) {
            case PROBE     ->  5;
            case ZEALOT    ->  8;
            case STALKER   -> 13;
            case IMMORTAL  -> 20;
            case MARINE    ->  6;
            case MARAUDER  -> 10;
            case ROACH     ->  9;
            case HYDRALISK -> 12;
            default        ->  5;
        };
    }

    /** Ticks between attacks (cooldown reset after firing). 1 tick = 500ms at Faster speed. */
    public static int attackCooldownInTicks(UnitType type) {
        return switch (type) {
            case MARINE, HYDRALISK, STALKER                -> 1;
            case PROBE, ZEALOT, IMMORTAL, MARAUDER, ROACH -> 2;
            default                                        -> 2;
        };
    }

    /** Attack range in tiles. Zealots are melee (0.5 tiles). */
    public static float attackRange(UnitType type) {
        return switch (type) {
            case ZEALOT    -> 0.5f;
            case PROBE     -> 3.0f;
            case STALKER   -> 5.0f;
            case IMMORTAL  -> 5.5f;
            case MARINE    -> 5.0f;
            case MARAUDER  -> 5.0f;
            case ROACH     -> 4.0f;
            case HYDRALISK -> 5.0f;
            default        -> 3.0f;
        };
    }

    /** Official SC2 sight radius in tiles. Friendly units only — we compute friendly vision. */
    public static int sightRange(UnitType type) {
        return switch (type) {
            case PROBE   -> 8;
            case ZEALOT  -> 9;
            case STALKER -> 10;
            default      -> 9;
        };
    }

    /** Official SC2 sight radius in tiles for buildings. */
    public static int sightRange(BuildingType type) {
        return switch (type) {
            case ASSIMILATOR -> 6;
            default          -> 9;  // NEXUS, GATEWAY, FORGE, etc.
        };
    }

    /** Blink range in tiles (STALKER only). */
    public static float blinkRange(UnitType type) {
        return type == UnitType.STALKER ? 8.0f : 0.0f;
    }

    /** Ticks before blink can be used again. 21 ticks ≈ 10.5s at 500ms/tick. */
    public static int blinkCooldownInTicks(UnitType type) {
        return type == UnitType.STALKER ? 21 : 0;
    }

    /** Shields restored on blink (capped at maxShields at call site). */
    public static int blinkShieldRestore(UnitType type) {
        return type == UnitType.STALKER ? 40 : 0;
    }
}
