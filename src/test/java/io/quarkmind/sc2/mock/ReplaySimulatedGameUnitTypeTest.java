package io.quarkmind.sc2.mock;

import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReplaySimulatedGameUnitTypeTest {

    @Test void marineIsMapped()    { assertThat(ReplaySimulatedGame.toUnitType("Marine")).isEqualTo(UnitType.MARINE); }
    @Test void marauderIsMapped()  { assertThat(ReplaySimulatedGame.toUnitType("Marauder")).isEqualTo(UnitType.MARAUDER); }
    @Test void medivacIsMapped()   { assertThat(ReplaySimulatedGame.toUnitType("Medivac")).isEqualTo(UnitType.MEDIVAC); }
    @Test void siegeTankIsMapped() { assertThat(ReplaySimulatedGame.toUnitType("SiegeTank")).isEqualTo(UnitType.SIEGE_TANK); }
    @Test void thorIsMapped()      { assertThat(ReplaySimulatedGame.toUnitType("Thor")).isEqualTo(UnitType.THOR); }
    @Test void vikingIsMapped()    { assertThat(ReplaySimulatedGame.toUnitType("VikingFighter")).isEqualTo(UnitType.VIKING); }
    @Test void widowMineIsMapped() { assertThat(ReplaySimulatedGame.toUnitType("WidowMine")).isEqualTo(UnitType.WIDOW_MINE); }

    @Test void zerglingIsMapped()  { assertThat(ReplaySimulatedGame.toUnitType("Zergling")).isEqualTo(UnitType.ZERGLING); }
    @Test void roachIsMapped()     { assertThat(ReplaySimulatedGame.toUnitType("Roach")).isEqualTo(UnitType.ROACH); }
    @Test void hydraliskIsMapped() { assertThat(ReplaySimulatedGame.toUnitType("Hydralisk")).isEqualTo(UnitType.HYDRALISK); }
    @Test void queenIsMapped()     { assertThat(ReplaySimulatedGame.toUnitType("Queen")).isEqualTo(UnitType.QUEEN); }
    @Test void mutaliskIsMapped()  { assertThat(ReplaySimulatedGame.toUnitType("Mutalisk")).isEqualTo(UnitType.MUTALISK); }
    @Test void ultraliskIsMapped() { assertThat(ReplaySimulatedGame.toUnitType("Ultralisk")).isEqualTo(UnitType.ULTRALISK); }

    @Test void unknownPassesThrough() { assertThat(ReplaySimulatedGame.toUnitType("MineralField")).isEqualTo(UnitType.UNKNOWN); }
}
