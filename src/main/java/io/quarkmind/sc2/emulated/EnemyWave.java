package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import java.util.List;

/**
 * A scheduled enemy spawn event. Added to EmulatedGame via configureWave();
 * consumed by EmulatedGame.tick() when spawnFrame is reached.
 */
record EnemyWave(long spawnFrame, List<UnitType> unitTypes,
                 Point2d spawnPosition, Point2d targetPosition) {}
