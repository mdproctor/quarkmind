package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.unit.Tag;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.BlinkIntent;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.Intent;
import io.quarkmind.sc2.intent.MoveIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Translates domain Intent objects into ResolvedCommands for dispatch via ActionInterface.
 *
 * Pure static functions — no CDI, no instance state. All methods are static because
 * this class is called directly by SC2BotAgent: ActionTranslator.translate(intents).
 * Mirrors ObservationTranslator in design.
 *
 * mapBuildAbility and mapTrainAbility are package-private so ActionTranslatorTest
 * can call them directly without reflection.
 */
public final class ActionTranslator {

    private static final Logger log = Logger.getLogger(ActionTranslator.class);

    private ActionTranslator() {}

    public static List<ResolvedCommand> translate(List<Intent> intents) {
        List<ResolvedCommand> commands = new ArrayList<>();
        for (Intent intent : intents) {
            try {
                ResolvedCommand cmd = switch (intent) {
                    case BuildIntent  b -> build(b);
                    case TrainIntent  t -> train(t);
                    case AttackIntent a -> attack(a);
                    case MoveIntent   m -> move(m);
                    case BlinkIntent  b -> blink(b);
                };
                if (cmd != null) commands.add(cmd);
            } catch (Exception e) {
                log.warnf("[SC2] Failed to translate intent %s: %s", intent, e.getMessage());
            }
        }
        return commands;
    }

    private static ResolvedCommand build(BuildIntent intent) {
        Abilities ability = mapBuildAbility(intent.buildingType());
        if (ability == null) {
            log.warnf("[SC2] No build ability for BuildingType.%s — intent skipped",
                      intent.buildingType());
            return null;
        }
        return new ResolvedCommand(
            toTag(intent.unitTag()),
            ability,
            Optional.of(toOcraft(intent.location()))
        );
    }

    private static ResolvedCommand train(TrainIntent intent) {
        Abilities ability = mapTrainAbility(intent.unitType());
        if (ability == null) {
            log.warnf("[SC2] No train ability for UnitType.%s — intent skipped",
                      intent.unitType());
            return null;
        }
        return new ResolvedCommand(
            toTag(intent.unitTag()),
            ability,
            Optional.empty()
        );
    }

    private static ResolvedCommand attack(AttackIntent intent) {
        return new ResolvedCommand(
            toTag(intent.unitTag()),
            Abilities.ATTACK,
            Optional.of(toOcraft(intent.targetLocation()))
        );
    }

    private static ResolvedCommand move(MoveIntent intent) {
        return new ResolvedCommand(
            toTag(intent.unitTag()),
            Abilities.MOVE,
            Optional.of(toOcraft(intent.targetLocation()))
        );
    }

    private static ResolvedCommand blink(BlinkIntent intent) {
        log.warnf("[ACTION] Blink not yet implemented for real SC2 — dropping intent for unit %s", intent.unitTag());
        return null;
    }

    private static Tag toTag(String tagStr) {
        return Tag.of(Long.parseLong(tagStr));
    }

    private static com.github.ocraft.s2client.protocol.spatial.Point2d toOcraft(
            io.quarkmind.domain.Point2d p) {
        return com.github.ocraft.s2client.protocol.spatial.Point2d.of(p.x(), p.y());
    }

    static Abilities mapBuildAbility(BuildingType type) {
        return switch (type) {
            // Protoss
            case NEXUS              -> Abilities.BUILD_NEXUS;
            case PYLON              -> Abilities.BUILD_PYLON;
            case GATEWAY            -> Abilities.BUILD_GATEWAY;
            case CYBERNETICS_CORE   -> Abilities.BUILD_CYBERNETICS_CORE;
            case ASSIMILATOR        -> Abilities.BUILD_ASSIMILATOR;
            case ROBOTICS_FACILITY  -> Abilities.BUILD_ROBOTICS_FACILITY;
            case STARGATE           -> Abilities.BUILD_STARGATE;
            case FORGE              -> Abilities.BUILD_FORGE;
            case TWILIGHT_COUNCIL   -> Abilities.BUILD_TWILIGHT_COUNCIL;
            case PHOTON_CANNON      -> Abilities.BUILD_PHOTON_CANNON;
            case SHIELD_BATTERY     -> Abilities.BUILD_SHIELD_BATTERY;
            case DARK_SHRINE        -> Abilities.BUILD_DARK_SHRINE;
            case TEMPLAR_ARCHIVES   -> null;
            case FLEET_BEACON       -> Abilities.BUILD_FLEET_BEACON;
            case ROBOTICS_BAY       -> Abilities.BUILD_ROBOTICS_BAY;
            // Terran
            case COMMAND_CENTER     -> Abilities.BUILD_COMMAND_CENTER;
            case ORBITAL_COMMAND    -> Abilities.MORPH_ORBITAL_COMMAND;
            case PLANETARY_FORTRESS -> Abilities.MORPH_PLANETARY_FORTRESS;
            case SUPPLY_DEPOT       -> Abilities.BUILD_SUPPLY_DEPOT;
            case BARRACKS           -> Abilities.BUILD_BARRACKS;
            case ENGINEERING_BAY    -> Abilities.BUILD_ENGINEERING_BAY;
            case ARMORY             -> Abilities.BUILD_ARMORY;
            case MISSILE_TURRET     -> Abilities.BUILD_MISSILE_TURRET;
            case BUNKER             -> Abilities.BUILD_BUNKER;
            case SENSOR_TOWER       -> Abilities.BUILD_SENSOR_TOWER;
            case GHOST_ACADEMY      -> Abilities.BUILD_GHOST_ACADEMY;
            case FACTORY            -> Abilities.BUILD_FACTORY;
            case STARPORT           -> Abilities.BUILD_STARPORT;
            case FUSION_CORE        -> Abilities.BUILD_FUSION_CORE;
            case REFINERY           -> Abilities.BUILD_REFINERY;
            // Zerg
            case HATCHERY           -> Abilities.BUILD_HATCHERY;
            case LAIR               -> Abilities.MORPH_LAIR;
            case HIVE               -> Abilities.MORPH_HIVE;
            case SPAWNING_POOL      -> Abilities.BUILD_SPAWNING_POOL;
            case EVOLUTION_CHAMBER  -> Abilities.BUILD_EVOLUTION_CHAMBER;
            case ROACH_WARREN       -> Abilities.BUILD_ROACH_WARREN;
            case BANELING_NEST      -> Abilities.BUILD_BANELING_NEST;
            case SPINE_CRAWLER      -> Abilities.BUILD_SPINE_CRAWLER;
            case SPORE_CRAWLER      -> Abilities.BUILD_SPORE_CRAWLER;
            case HYDRALISK_DEN      -> Abilities.BUILD_HYDRALISK_DEN;
            case LURKER_DEN         -> null;
            case INFESTATION_PIT    -> Abilities.BUILD_INFESTATION_PIT;
            case SPIRE              -> Abilities.BUILD_SPIRE;
            case GREATER_SPIRE      -> Abilities.MORPH_GREATER_SPIRE;
            case NYDUS_NETWORK      -> Abilities.BUILD_NYDUS_NETWORK;
            case NYDUS_CANAL        -> null;
            case ULTRALISK_CAVERN   -> Abilities.BUILD_ULTRALISK_CAVERN;
            case EXTRACTOR          -> Abilities.BUILD_EXTRACTOR;
            default                 -> null;
        };
    }

    static Abilities mapTrainAbility(UnitType type) {
        return switch (type) {
            case PROBE        -> Abilities.TRAIN_PROBE;
            case ZEALOT       -> Abilities.TRAIN_ZEALOT;
            case STALKER      -> Abilities.TRAIN_STALKER;
            case IMMORTAL     -> Abilities.TRAIN_IMMORTAL;
            case COLOSSUS     -> Abilities.TRAIN_COLOSSUS;
            case CARRIER      -> Abilities.TRAIN_CARRIER;
            case DARK_TEMPLAR -> Abilities.TRAIN_DARK_TEMPLAR;
            case HIGH_TEMPLAR -> Abilities.TRAIN_HIGH_TEMPLAR;
            case OBSERVER     -> Abilities.TRAIN_OBSERVER;
            case VOID_RAY     -> Abilities.TRAIN_VOIDRAY; // ocraft: no underscore
            // Archon requires two Templar — single-tag TrainIntent cannot express this
            case ARCHON       -> null;
            // Zerg and Terran types exist for scouting recognition, not Protoss training
            case ZERGLING, ROACH, HYDRALISK, MUTALISK, ULTRALISK,
                 BROOD_LORD, CORRUPTOR, INFESTOR, SWARM_HOST, VIPER, QUEEN, RAVAGER, LURKER,
                 DRONE, OVERLORD, OVERSEER, BANELING, LOCUST, BROODLING, INFESTED_TERRAN, CHANGELING,
                 MARINE, MARAUDER, MEDIVAC, SIEGE_TANK, SIEGE_TANK_SIEGED, THOR, VIKING,
                 GHOST, RAVEN, BANSHEE, BATTLECRUISER, CYCLONE, LIBERATOR, WIDOW_MINE,
                 SCV, REAPER, HELLION, HELLBAT, MULE, VIKING_ASSAULT, LIBERATOR_AG, AUTO_TURRET,
                 ADEPT, DISRUPTOR, SENTRY,
                 PHOENIX, ORACLE, TEMPEST, MOTHERSHIP, WARP_PRISM, WARP_PRISM_PHASING,
                 INTERCEPTOR, ADEPT_PHASE_SHIFT,
                 UNKNOWN      -> null;
        };
    }
}
