package io.quarkmind.domain;

import java.util.List;

public record GameState(
    int minerals,
    int vespene,
    int supply,
    int supplyUsed,
    List<Unit> myUnits,
    List<Building> myBuildings,
    List<Unit> enemyUnits,
    List<Unit> enemyStagingArea,
    List<Resource> geysers,
    List<Resource> mineralPatches,
    long gameFrame
) {
    public GameState {
        myUnits          = List.copyOf(myUnits);
        myBuildings      = List.copyOf(myBuildings);
        enemyUnits       = List.copyOf(enemyUnits);
        enemyStagingArea = List.copyOf(enemyStagingArea);
        geysers          = List.copyOf(geysers);
        mineralPatches   = List.copyOf(mineralPatches);
    }
}
