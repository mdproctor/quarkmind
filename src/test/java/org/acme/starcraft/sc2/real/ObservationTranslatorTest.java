package org.acme.starcraft.sc2.real;

import com.github.ocraft.s2client.protocol.data.Units;
import org.acme.starcraft.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ObservationTranslatorTest {

    @Test
    void mapsProtossUnitTypes() {
        assertThat(ObservationTranslator.mapUnitType(Units.PROTOSS_PROBE))
            .isEqualTo(UnitType.PROBE);
        assertThat(ObservationTranslator.mapUnitType(Units.PROTOSS_ZEALOT))
            .isEqualTo(UnitType.ZEALOT);
        assertThat(ObservationTranslator.mapUnitType(Units.PROTOSS_STALKER))
            .isEqualTo(UnitType.STALKER);
        assertThat(ObservationTranslator.mapUnitType(Units.PROTOSS_NEXUS))
            .isEqualTo(UnitType.UNKNOWN); // buildings go through mapBuildingType, not mapUnitType
    }

    @Test
    void mapsProtossBuildingTypes() {
        assertThat(ObservationTranslator.mapBuildingType(Units.PROTOSS_NEXUS))
            .isEqualTo(BuildingType.NEXUS);
        assertThat(ObservationTranslator.mapBuildingType(Units.PROTOSS_PYLON))
            .isEqualTo(BuildingType.PYLON);
        assertThat(ObservationTranslator.mapBuildingType(Units.PROTOSS_PROBE))
            .isEqualTo(BuildingType.UNKNOWN); // units go through mapUnitType
    }

    @Test
    void knowsWhichTypesAreBuildings() {
        assertThat(ObservationTranslator.isBuilding(Units.PROTOSS_NEXUS)).isTrue();
        assertThat(ObservationTranslator.isBuilding(Units.PROTOSS_PYLON)).isTrue();
        assertThat(ObservationTranslator.isBuilding(Units.PROTOSS_PROBE)).isFalse();
        assertThat(ObservationTranslator.isBuilding(Units.PROTOSS_ZEALOT)).isFalse();
    }
}
