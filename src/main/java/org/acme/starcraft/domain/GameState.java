package org.acme.starcraft.domain;

import java.util.List;

public record GameState(
    int minerals,
    int vespene,
    int supply,
    int supplyUsed,
    List<Unit> myUnits,
    List<Building> myBuildings,
    List<Unit> enemyUnits,
    List<Resource> geysers,
    long gameFrame
) {
    public GameState {
        myUnits    = List.copyOf(myUnits);
        myBuildings = List.copyOf(myBuildings);
        enemyUnits = List.copyOf(enemyUnits);
        geysers    = List.copyOf(geysers);
    }
}
