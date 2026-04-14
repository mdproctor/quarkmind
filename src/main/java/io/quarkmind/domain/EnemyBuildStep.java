package io.quarkmind.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EnemyBuildStep(
    @JsonProperty("unitType") UnitType unitType
) {}
