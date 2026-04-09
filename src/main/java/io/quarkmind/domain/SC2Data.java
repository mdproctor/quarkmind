package io.quarkmind.domain;

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
            case PYLON             -> 18;
            case GATEWAY           -> 47;
            case CYBERNETICS_CORE  -> 37;
            case ASSIMILATOR       -> 21;
            case ROBOTICS_FACILITY -> 47;
            case STARGATE          -> 44;
            case FORGE             -> 30;
            case TWILIGHT_COUNCIL  -> 37;
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
        return type == BuildingType.PYLON ? 8 : 0;
    }

    public static int maxHealth(UnitType type) {
        return switch (type) {
            case PROBE   -> 45;
            case ZEALOT  -> 100;
            case STALKER -> 80;
            default      -> 100;
        };
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
            case NEXUS    -> 1500;
            case PYLON    -> 200;
            case GATEWAY  -> 500;
            default       -> 500;
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
            case NEXUS             -> 400;
            case PYLON             -> 100;
            case GATEWAY           -> 150;
            case CYBERNETICS_CORE  -> 150;
            case ASSIMILATOR       -> 75;
            case ROBOTICS_FACILITY -> 200;
            case STARGATE          -> 150;
            case FORGE             -> 150;
            case TWILIGHT_COUNCIL  -> 150;
            default                -> 100;
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
}
