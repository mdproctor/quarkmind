package io.quarkmind.agent;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class GameStateTranslatorTest {

    GameStateTranslator translator = new GameStateTranslator();

    @Test
    void translatesResourcesCorrectly() {
        var state = new GameState(150, 75, 23, 14, List.of(), List.of(), List.of(), List.of(), 42L);
        Map<String, Object> map = translator.toMap(state);
        assertThat(map.get(QuarkMindCaseFile.MINERALS)).isEqualTo(150);
        assertThat(map.get(QuarkMindCaseFile.VESPENE)).isEqualTo(75);
        assertThat(map.get(QuarkMindCaseFile.SUPPLY_CAP)).isEqualTo(23);
        assertThat(map.get(QuarkMindCaseFile.SUPPLY_USED)).isEqualTo(14);
        assertThat(map.get(QuarkMindCaseFile.GAME_FRAME)).isEqualTo(42L);
        assertThat(map.get(QuarkMindCaseFile.READY)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void separatesWorkersFromArmy() {
        var probe = new Unit("p1", UnitType.PROBE, new Point2d(0,0), 45, 45, 20, 20);
        var zealot = new Unit("z1", UnitType.ZEALOT, new Point2d(1,1), 100, 100, 50, 50);
        var state = new GameState(50, 0, 15, 3, List.of(probe, zealot), List.of(), List.of(), List.of(), 0L);
        Map<String, Object> map = translator.toMap(state);
        assertThat((List<?>) map.get(QuarkMindCaseFile.WORKERS)).hasSize(1);
        assertThat((List<?>) map.get(QuarkMindCaseFile.ARMY)).hasSize(1);
    }
}
