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
    long gameFrame
) {}
