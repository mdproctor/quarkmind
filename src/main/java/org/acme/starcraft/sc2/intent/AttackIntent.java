package org.acme.starcraft.sc2.intent;

import org.acme.starcraft.domain.Point2d;

public record AttackIntent(String unitTag, Point2d targetLocation) implements Intent {}
