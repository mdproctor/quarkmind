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

    @Test
    void allProtossTrainAbilitiesMapCorrectly() {
        assertThat(ActionTranslator.mapTrainAbility(UnitType.PROBE))
            .isEqualTo(Abilities.TRAIN_PROBE);
        assertThat(ActionTranslator.mapTrainAbility(UnitType.ZEALOT))
            .isEqualTo(Abilities.TRAIN_ZEALOT);
        assertThat(ActionTranslator.mapTrainAbility(UnitType.STALKER))
            .isEqualTo(Abilities.TRAIN_STALKER);
        assertThat(ActionTranslator.mapTrainAbility(UnitType.IMMORTAL))
            .isEqualTo(Abilities.TRAIN_IMMORTAL);
        assertThat(ActionTranslator.mapTrainAbility(UnitType.COLOSSUS))
            .isEqualTo(Abilities.TRAIN_COLOSSUS);
        assertThat(ActionTranslator.mapTrainAbility(UnitType.CARRIER))
            .isEqualTo(Abilities.TRAIN_CARRIER);
        assertThat(ActionTranslator.mapTrainAbility(UnitType.DARK_TEMPLAR))
            .isEqualTo(Abilities.TRAIN_DARK_TEMPLAR);
        assertThat(ActionTranslator.mapTrainAbility(UnitType.HIGH_TEMPLAR))
            .isEqualTo(Abilities.TRAIN_HIGH_TEMPLAR);
        assertThat(ActionTranslator.mapTrainAbility(UnitType.OBSERVER))
            .isEqualTo(Abilities.TRAIN_OBSERVER);
        // Note: ocraft spells this TRAIN_VOIDRAY (no underscore), not TRAIN_VOID_RAY
        assertThat(ActionTranslator.mapTrainAbility(UnitType.VOID_RAY))
            .isEqualTo(Abilities.TRAIN_VOIDRAY);
    }

    @Test
    void archonTrainAbilityIsNullBecauseItRequiresTwoTemplar() {
        // ARCHON is formed by merging two High or Dark Templar — the single-tag
        // TrainIntent model cannot express a two-unit merge. Log warn and skip.
        assertThat(ActionTranslator.mapTrainAbility(UnitType.ARCHON)).isNull();
    }

    @Test
    void enemyAndUnknownUnitTypesHaveNullTrainAbility() {
        // Zerg and Terran types exist in UnitType for scouting recognition only.
        // They are not trainable by a Protoss player.
        assertThat(ActionTranslator.mapTrainAbility(UnitType.ZERGLING)).isNull();
        assertThat(ActionTranslator.mapTrainAbility(UnitType.ROACH)).isNull();
        assertThat(ActionTranslator.mapTrainAbility(UnitType.HYDRALISK)).isNull();
        assertThat(ActionTranslator.mapTrainAbility(UnitType.MARINE)).isNull();
        assertThat(ActionTranslator.mapTrainAbility(UnitType.MARAUDER)).isNull();
        assertThat(ActionTranslator.mapTrainAbility(UnitType.MEDIVAC)).isNull();
        assertThat(ActionTranslator.mapTrainAbility(UnitType.UNKNOWN)).isNull();
    }
}
