package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Abilities;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActionTranslatorTest {

    // ─── Group 1: Ability maps ────────────────────────────────────────────────

    @Test
    void allProtossBuildAbilitiesMapCorrectly() {
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.NEXUS))
            .isEqualTo(Abilities.BUILD_NEXUS);
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.PYLON))
            .isEqualTo(Abilities.BUILD_PYLON);
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.GATEWAY))
            .isEqualTo(Abilities.BUILD_GATEWAY);
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.CYBERNETICS_CORE))
            .isEqualTo(Abilities.BUILD_CYBERNETICS_CORE);
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.ASSIMILATOR))
            .isEqualTo(Abilities.BUILD_ASSIMILATOR);
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.ROBOTICS_FACILITY))
            .isEqualTo(Abilities.BUILD_ROBOTICS_FACILITY);
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.STARGATE))
            .isEqualTo(Abilities.BUILD_STARGATE);
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.FORGE))
            .isEqualTo(Abilities.BUILD_FORGE);
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.TWILIGHT_COUNCIL))
            .isEqualTo(Abilities.BUILD_TWILIGHT_COUNCIL);
    }

    @Test
    void unknownBuildingTypeHasNullBuildAbility() {
        assertThat(ActionTranslator.mapBuildAbility(BuildingType.UNKNOWN)).isNull();
    }
}
