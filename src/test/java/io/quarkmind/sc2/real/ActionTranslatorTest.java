package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
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

    // ─── Group 2: Intent dispatch ─────────────────────────────────────────────

    @Test
    void buildIntentProducesPositionTargetedCommandWithCorrectTagAbilityAndLocation() {
        var intent = new BuildIntent("456", BuildingType.GATEWAY,
                                    new io.quarkmind.domain.Point2d(30f, 40f));

        var commands = ActionTranslator.translate(List.of(intent));

        assertThat(commands).hasSize(1);
        var cmd = commands.get(0);
        assertThat(cmd.tag()).isEqualTo(Tag.of(456L));
        assertThat(cmd.ability()).isEqualTo(Abilities.BUILD_GATEWAY);
        assertThat(cmd.target()).contains(Point2d.of(30f, 40f));
    }

    @Test
    void trainIntentProducesCommandWithCorrectTagAndAbilityAndNoPosition() {
        var intent = new TrainIntent("789", UnitType.PROBE);

        var commands = ActionTranslator.translate(List.of(intent));

        assertThat(commands).hasSize(1);
        var cmd = commands.get(0);
        assertThat(cmd.tag()).isEqualTo(Tag.of(789L));
        assertThat(cmd.ability()).isEqualTo(Abilities.TRAIN_PROBE);
        assertThat(cmd.target()).isEmpty();
    }

    @Test
    void attackIntentProducesAttackCommandAtTargetLocation() {
        var intent = new AttackIntent("111", new io.quarkmind.domain.Point2d(50f, 60f));

        var commands = ActionTranslator.translate(List.of(intent));

        assertThat(commands).hasSize(1);
        var cmd = commands.get(0);
        assertThat(cmd.tag()).isEqualTo(Tag.of(111L));
        assertThat(cmd.ability()).isEqualTo(Abilities.ATTACK);
        assertThat(cmd.target()).contains(Point2d.of(50f, 60f));
    }

    @Test
    void moveIntentProducesMoveCommandAtTargetLocation() {
        var intent = new MoveIntent("222", new io.quarkmind.domain.Point2d(10f, 20f));

        var commands = ActionTranslator.translate(List.of(intent));

        assertThat(commands).hasSize(1);
        var cmd = commands.get(0);
        assertThat(cmd.tag()).isEqualTo(Tag.of(222L));
        assertThat(cmd.ability()).isEqualTo(Abilities.MOVE);
        assertThat(cmd.target()).contains(Point2d.of(10f, 20f));
    }
}
