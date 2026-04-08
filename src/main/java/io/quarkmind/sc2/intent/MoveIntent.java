package io.quarkmind.sc2.intent;

import io.quarkmind.domain.Point2d;

public record MoveIntent(String unitTag, Point2d targetLocation) implements Intent {}
