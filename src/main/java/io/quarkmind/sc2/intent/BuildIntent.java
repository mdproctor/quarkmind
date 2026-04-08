package io.quarkmind.sc2.intent;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;

public record BuildIntent(String unitTag, BuildingType buildingType, Point2d location) implements Intent {}
