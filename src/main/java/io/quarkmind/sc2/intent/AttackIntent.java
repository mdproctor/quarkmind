package io.quarkmind.sc2.intent;

import io.quarkmind.domain.Point2d;

public record AttackIntent(String unitTag, Point2d targetLocation) implements Intent {}
