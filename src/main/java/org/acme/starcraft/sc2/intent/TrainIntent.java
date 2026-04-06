package org.acme.starcraft.sc2.intent;

import org.acme.starcraft.domain.UnitType;

public record TrainIntent(String unitTag, UnitType unitType) implements Intent {}
