package org.acme.starcraft.agent;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.domain.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GameStateTranslator {

    public Map<String, Object> toMap(GameState state) {
        Map<String, Object> data = new HashMap<>();
        data.put(StarCraftCaseFile.MINERALS,    state.minerals());
        data.put(StarCraftCaseFile.VESPENE,     state.vespene());
        data.put(StarCraftCaseFile.SUPPLY_CAP,  state.supply());
        data.put(StarCraftCaseFile.SUPPLY_USED, state.supplyUsed());
        data.put(StarCraftCaseFile.GAME_FRAME,  state.gameFrame());
        data.put(StarCraftCaseFile.READY,       Boolean.TRUE);

        List<Unit> workers = state.myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).toList();
        List<Unit> army = state.myUnits().stream()
            .filter(u -> u.type() != UnitType.PROBE).toList();

        data.put(StarCraftCaseFile.WORKERS,      workers);
        data.put(StarCraftCaseFile.ARMY,         army);
        data.put(StarCraftCaseFile.MY_BUILDINGS, state.myBuildings());
        data.put(StarCraftCaseFile.GEYSERS,      state.geysers());
        data.put(StarCraftCaseFile.ENEMY_UNITS,  state.enemyUnits());
        return data;
    }
}
