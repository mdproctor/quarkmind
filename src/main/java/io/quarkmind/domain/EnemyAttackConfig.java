package io.quarkmind.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EnemyAttackConfig(
    @JsonProperty("armyThreshold")       int armyThreshold,
    @JsonProperty("attackIntervalFrames") int attackIntervalFrames
) {}
