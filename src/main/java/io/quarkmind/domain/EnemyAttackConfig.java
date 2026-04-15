package io.quarkmind.domain;

public record EnemyAttackConfig(
    int armyThreshold,
    int attackIntervalFrames,
    int retreatHealthPercent,   // 0 = disabled; retreat when HP+shields < X% of max
    int retreatArmyPercent      // 0 = disabled; retreat when < X% of launched units alive
) {}
