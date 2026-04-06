package org.acme.starcraft.sc2.intent;

import org.acme.starcraft.domain.BuildingType;
import org.acme.starcraft.domain.Point2d;

public record BuildIntent(String unitTag, BuildingType buildingType, Point2d location) implements Intent {}
