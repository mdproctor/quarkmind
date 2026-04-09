package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.intent.MoveIntent;
import io.quarkmind.domain.Point2d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EmulatedGameTest {

    EmulatedGame game;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.reset();
    }

    @Test
    void initialMineralsAreFifty() {
        assertThat(game.snapshot().minerals()).isEqualTo(50);
    }

    @Test
    void mineralAccumulatesWithMiningProbes() {
        int ticks = 100;
        for (int i = 0; i < ticks; i++) game.tick();
        double expected = SC2Data.INITIAL_MINERALS + (SC2Data.INITIAL_PROBES * SC2Data.MINERALS_PER_PROBE_PER_TICK * ticks);
        assertThat(game.snapshot().minerals()).isCloseTo((int) expected, within(1));
    }

    @Test
    void zeroProbesYieldsNoMineralGain() {
        game.setMiningProbes(0);
        for (int i = 0; i < 100; i++) game.tick();
        assertThat(game.snapshot().minerals()).isEqualTo(50);
    }

    @Test
    void snapshotFrameDoesNotChangeAfterTick() {
        GameState before = game.snapshot();
        game.tick();
        assertThat(before.gameFrame()).isEqualTo(0L);
        assertThat(game.snapshot().gameFrame()).isEqualTo(1L);
    }

    @Test
    void resetRestoresInitialState() {
        for (int i = 0; i < 100; i++) game.tick();
        game.reset();
        assertThat(game.snapshot().minerals()).isEqualTo(50);
        assertThat(game.snapshot().gameFrame()).isEqualTo(0L);
    }

    @Test
    void applyIntentIsNoOp() {
        game.applyIntent(new MoveIntent("probe-0", new Point2d(10, 10)));
        assertThat(game.snapshot().minerals()).isEqualTo(50);
        assertThat(game.snapshot().myUnits()).hasSize(12);
    }
}
