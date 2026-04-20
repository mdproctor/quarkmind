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
            case NEXUS             -> Abilities.BUILD_NEXUS;
            case PYLON             -> Abilities.BUILD_PYLON;
            case GATEWAY           -> Abilities.BUILD_GATEWAY;
            case CYBERNETICS_CORE  -> Abilities.BUILD_CYBERNETICS_CORE;
            case ASSIMILATOR       -> Abilities.BUILD_ASSIMILATOR;
            case ROBOTICS_FACILITY -> Abilities.BUILD_ROBOTICS_FACILITY;
            case STARGATE          -> Abilities.BUILD_STARGATE;
            case FORGE             -> Abilities.BUILD_FORGE;
            case TWILIGHT_COUNCIL  -> Abilities.BUILD_TWILIGHT_COUNCIL;
            case UNKNOWN           -> null;
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
                 MARINE, MARAUDER, MEDIVAC, SIEGE_TANK, THOR, VIKING,
                 GHOST, RAVEN, BANSHEE, BATTLECRUISER, CYCLONE, LIBERATOR, WIDOW_MINE,
                 ADEPT, DISRUPTOR, SENTRY,
                 UNKNOWN      -> null;
        };
    }
}
