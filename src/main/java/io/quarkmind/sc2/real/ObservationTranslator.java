package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import io.quarkmind.domain.*;

import java.util.List;
import java.util.Set;

/**
 * Pure function — translates an ocraft ObservationInterface snapshot into our GameState.
 * No CDI, no framework dependencies. Unit-testable without a live SC2 connection.
 */
public final class ObservationTranslator {

    // All Protoss building types — used to distinguish units from structures
    private static final Set<Units> PROTOSS_BUILDINGS = Set.of(
        Units.PROTOSS_NEXUS, Units.PROTOSS_PYLON, Units.PROTOSS_GATEWAY,
        Units.PROTOSS_CYBERNETICS_CORE, Units.PROTOSS_ASSIMILATOR,
        Units.PROTOSS_ROBOTICS_FACILITY, Units.PROTOSS_STARGATE,
        Units.PROTOSS_FORGE, Units.PROTOSS_TWILIGHT_COUNCIL,
        Units.PROTOSS_DARK_SHRINE, Units.PROTOSS_TEMPLAR_ARCHIVE,
        Units.PROTOSS_FLEET_BEACON, Units.PROTOSS_ROBOTICS_BAY
    );

    private ObservationTranslator() {}

    public static GameState translate(ObservationInterface obs) {
        var allUnits   = obs.getUnits();
        var selfUnits  = allUnits.stream()
            .filter(u -> u.unit() != null && u.unit().getAlliance() == Alliance.SELF)
            .toList();
        var enemyUnits = allUnits.stream()
            .filter(u -> u.unit() != null && u.unit().getAlliance() == Alliance.ENEMY)
            .toList();

        List<Unit> myUnits = selfUnits.stream()
            .filter(u -> !isBuilding(toUnitsEnum(u)))
            .map(ObservationTranslator::toUnit)
            .toList();

        List<Building> myBuildings = selfUnits.stream()
            .filter(u -> isBuilding(toUnitsEnum(u)))
            .map(ObservationTranslator::toBuilding)
            .toList();

        List<Unit> enemies = enemyUnits.stream()
            .map(ObservationTranslator::toUnit)
            .toList();

        return new GameState(
            obs.getMinerals(),
            obs.getVespene(),
            obs.getFoodCap(),
            obs.getFoodUsed(),
            myUnits,
            myBuildings,
            enemies,
            List.of(),   // enemyStagingArea — not applicable for real SC2
            List.of(),   // geysers: neutral unit detection deferred to Phase 3+
            obs.getGameLoop()
        );
    }

    static boolean isBuilding(Units type) {
        return PROTOSS_BUILDINGS.contains(type);
    }

    static UnitType mapUnitType(Units type) {
        return switch (type) {
            case PROTOSS_PROBE        -> UnitType.PROBE;
            case PROTOSS_ZEALOT       -> UnitType.ZEALOT;
            case PROTOSS_STALKER      -> UnitType.STALKER;
            case PROTOSS_IMMORTAL     -> UnitType.IMMORTAL;
            case PROTOSS_COLOSSUS     -> UnitType.COLOSSUS;
            case PROTOSS_CARRIER      -> UnitType.CARRIER;
            case PROTOSS_DARK_TEMPLAR -> UnitType.DARK_TEMPLAR;
            case PROTOSS_HIGH_TEMPLAR -> UnitType.HIGH_TEMPLAR;
            case PROTOSS_ARCHON       -> UnitType.ARCHON;
            case PROTOSS_OBSERVER     -> UnitType.OBSERVER;
            case PROTOSS_VOIDRAY      -> UnitType.VOID_RAY;
            default                   -> UnitType.UNKNOWN;
        };
    }

    static BuildingType mapBuildingType(Units type) {
        return switch (type) {
            case PROTOSS_NEXUS            -> BuildingType.NEXUS;
            case PROTOSS_PYLON            -> BuildingType.PYLON;
            case PROTOSS_GATEWAY          -> BuildingType.GATEWAY;
            case PROTOSS_CYBERNETICS_CORE -> BuildingType.CYBERNETICS_CORE;
            case PROTOSS_ASSIMILATOR      -> BuildingType.ASSIMILATOR;
            case PROTOSS_ROBOTICS_FACILITY -> BuildingType.ROBOTICS_FACILITY;
            case PROTOSS_STARGATE         -> BuildingType.STARGATE;
            case PROTOSS_FORGE            -> BuildingType.FORGE;
            case PROTOSS_TWILIGHT_COUNCIL -> BuildingType.TWILIGHT_COUNCIL;
            default                       -> BuildingType.UNKNOWN;
        };
    }

    private static Units toUnitsEnum(UnitInPool uip) {
        var rawType = uip.unit().getType();
        return rawType instanceof Units u ? u : Units.INVALID;
    }

    private static Unit toUnit(UnitInPool uip) {
        var u = uip.unit();
        var pos = u.getPosition();
        return new Unit(
            String.valueOf(u.getTag().getValue()),
            mapUnitType(toUnitsEnum(uip)),
            new Point2d(pos.getX(), pos.getY()),
            u.getHealth().map(Float::intValue).orElse(0),
            u.getHealthMax().map(Float::intValue).orElse(0),
            u.getShield().map(Float::intValue).orElse(0),
            u.getShieldMax().map(Float::intValue).orElse(0),
            0,  // TODO #70: map (int)(u.getWeaponCooldown().orElse(0f) / 0.5f) when real SC2 mode is exercised
            0   // blinkCooldownTicks — not yet tracked in real SC2 mode
        );
    }

    private static Building toBuilding(UnitInPool uip) {
        var u = uip.unit();
        var pos = u.getPosition();
        return new Building(
            String.valueOf(u.getTag().getValue()),
            mapBuildingType(toUnitsEnum(uip)),
            new Point2d(pos.getX(), pos.getY()),
            u.getHealth().map(Float::intValue).orElse(0),
            u.getHealthMax().map(Float::intValue).orElse(0),
            u.getBuildProgress() >= 1.0f
        );
    }
}
