package io.quarkmind.sc2.mock;

import io.quarkmind.sc2.replay.GameEventStream;
import io.quarkmind.sc2.replay.UnitOrder;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReplaySimulatedGameMovementTest {

    static final Path REPLAY = Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");

    private ReplaySimulatedGame gameWithOrders() {
        var game = new ReplaySimulatedGame(REPLAY, 1);
        List<UnitOrder> orders = GameEventStream.parse(REPLAY);
        game.loadOrders(orders);
        return game;
    }

    @Test
    void seekToReachesCorrectFrame() {
        var game = gameWithOrders();
        game.seekTo(440); // 440 loops ÷ 22 LOOPS_PER_TICK = frame 20
        assertThat(game.snapshot().gameFrame()).isBetween(15L, 25L);
    }

    @Test
    void seekToCompletesQuickly() {
        var game = gameWithOrders();
        long start = System.currentTimeMillis();
        game.seekTo(5000);
        assertThat(System.currentTimeMillis() - start).isLessThan(2000);
    }

    @Test
    void totalLoopsIsPositive() {
        var game = new ReplaySimulatedGame(REPLAY, 1);
        assertThat(game.totalLoops()).isGreaterThan(0);
    }

    @Test
    void allUnitPositionsWithinMapBoundsAfter200Ticks() {
        var game = gameWithOrders();
        for (int i = 0; i < 200; i++) game.tick();
        var state = game.snapshot();
        for (var u : state.myUnits()) {
            assertThat(u.position().x()).as("unit %s x", u.tag()).isBetween(0f, 161f);
            assertThat(u.position().y()).as("unit %s y", u.tag()).isBetween(0f, 209f);
        }
        for (var u : state.enemyUnits()) {
            assertThat(u.position().x()).as("enemy %s x", u.tag()).isBetween(0f, 161f);
            assertThat(u.position().y()).as("enemy %s y", u.tag()).isBetween(0f, 209f);
        }
    }
}
