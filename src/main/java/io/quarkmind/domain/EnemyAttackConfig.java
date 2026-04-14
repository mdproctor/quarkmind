package io.quarkmind.domain;

public record EnemyAttackConfig(
    int armyThreshold,
    int attackIntervalFrames
) {}
