package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Abilities;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.Intent;
import org.jboss.logging.Logger;

import java.util.List;

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
        throw new UnsupportedOperationException("not yet implemented");
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
            case ZERGLING, ROACH, HYDRALISK,
                 MARINE, MARAUDER, MEDIVAC,
                 UNKNOWN      -> null;
        };
    }
}
