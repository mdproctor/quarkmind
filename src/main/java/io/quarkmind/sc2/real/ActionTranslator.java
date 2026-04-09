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
        throw new UnsupportedOperationException("not yet implemented");
    }
}
