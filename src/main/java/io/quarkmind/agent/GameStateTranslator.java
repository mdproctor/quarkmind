package io.quarkmind.agent;

import jakarta.enterprise.context.ApplicationScoped;
import io.quarkmind.domain.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GameStateTranslator {

    public Map<String, Object> toMap(GameState state) {
        Map<String, Object> data = new HashMap<>();
        data.put(QuarkMindCaseFile.MINERALS,    state.minerals());
        data.put(QuarkMindCaseFile.VESPENE,     state.vespene());
        data.put(QuarkMindCaseFile.SUPPLY_CAP,  state.supply());
        data.put(QuarkMindCaseFile.SUPPLY_USED, state.supplyUsed());
        data.put(QuarkMindCaseFile.GAME_FRAME,  state.gameFrame());
        data.put(QuarkMindCaseFile.READY,       Boolean.TRUE);

        List<Unit> workers = state.myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).toList();
        List<Unit> army = state.myUnits().stream()
            .filter(u -> u.type() != UnitType.PROBE).toList();

        data.put(QuarkMindCaseFile.WORKERS,         workers);
        data.put(QuarkMindCaseFile.ARMY,            army);
        data.put(QuarkMindCaseFile.MY_BUILDINGS,    state.myBuildings());
        data.put(QuarkMindCaseFile.GEYSERS,         state.geysers());
        data.put(QuarkMindCaseFile.ENEMY_UNITS,     state.enemyUnits());
        data.put(QuarkMindCaseFile.RESOURCE_BUDGET, new ResourceBudget(state.minerals(), state.vespene()));
        return data;
    }
}
