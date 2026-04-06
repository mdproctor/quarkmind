package org.acme.starcraft.domain;

import java.util.List;

public record GameMap(String name, int width, int height, List<Point2d> expansionLocations) {
    public GameMap {
        expansionLocations = List.copyOf(expansionLocations);
    }
}
