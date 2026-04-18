package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GameStateTest {

    @Test
    void initialProtossGameState() {
        var probe = new Unit("tag-1", UnitType.PROBE, new Point2d(10.0f, 10.0f), 45, 45, 20, 20, 0, 0);
        var nexus = new Building("tag-100", BuildingType.NEXUS, new Point2d(8.0f, 8.0f), 1500, 1500, true);
        var state = new GameState(50, 0, 15, 1, List.of(probe), List.of(nexus), List.of(), List.of(), List.of(), 0L);

        assertThat(state.minerals()).isEqualTo(50);
        assertThat(state.supply()).isEqualTo(15);
        assertThat(state.supplyUsed()).isEqualTo(1);
        assertThat(state.myUnits()).hasSize(1);
        assertThat(state.myUnits().get(0).type()).isEqualTo(UnitType.PROBE);
        assertThat(state.myBuildings()).hasSize(1);
        assertThat(state.myBuildings().get(0).type()).isEqualTo(BuildingType.NEXUS);
    }

    @Test
    void probeCount() {
        var probes = List.of(
            new Unit("t1", UnitType.PROBE, new Point2d(0, 0), 45, 45, 20, 20, 0, 0),
            new Unit("t2", UnitType.PROBE, new Point2d(1, 1), 45, 45, 20, 20, 0, 0),
            new Unit("t3", UnitType.ZEALOT, new Point2d(2, 2), 100, 100, 50, 50, 0, 0)
        );
        var state = new GameState(50, 0, 15, 3, probes, List.of(), List.of(), List.of(), List.of(), 0L);
        long probeCount = state.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count();
        assertThat(probeCount).isEqualTo(2);
    }
}
