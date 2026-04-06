package org.acme.starcraft.sc2.replay;

import org.acme.starcraft.domain.BuildingType;
import org.acme.starcraft.domain.GameState;
import org.acme.starcraft.domain.UnitType;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.BuildIntent;
import org.acme.starcraft.domain.Point2d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ReplayEngine — exercises the SC2Engine contract against
 * Nothing_4720936.SC2Replay without CDI.
 */
class ReplayEngineTest {

    private static final String REPLAY = "replays/aiarena_protoss/Nothing_4720936.SC2Replay";

    ReplayEngine engine;
    IntentQueue intentQueue;

    @BeforeEach
    void setUp() throws Exception {
        intentQueue = new IntentQueue();
        engine = new ReplayEngine();
        // Inject fields manually (no CDI in unit tests)
        setField(engine, "replayFile", REPLAY);
        setField(engine, "watchedPlayerId", 1);
        setField(engine, "intentQueue", intentQueue);
    }

    @Test
    void connectLoadsReplayAndSetsConnected() {
        engine.connect();
        assertThat(engine.isConnected()).isTrue();
    }

    @Test
    void joinGameResetsToInitialState() {
        engine.connect();
        engine.joinGame();
        GameState state = engine.observe();
        assertThat(state.gameFrame()).isEqualTo(0L);
        assertThat(state.myBuildings().stream().anyMatch(b -> b.type() == BuildingType.NEXUS)).isTrue();
        assertThat(state.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count()).isEqualTo(12);
    }

    @Test
    void tickAdvancesGameFrame() {
        engine.connect();
        engine.joinGame();
        engine.tick();
        assertThat(engine.observe().gameFrame()).isEqualTo(1L);
    }

    @Test
    void observeFiresFrameListeners() {
        engine.connect();
        engine.joinGame();
        var captured = new java.util.ArrayList<GameState>();
        engine.addFrameListener(captured::add);
        engine.observe();
        assertThat(captured).hasSize(1);
    }

    @Test
    void dispatchDrainsIntentQueueWithoutMutatingState() {
        engine.connect();
        engine.joinGame();
        GameState before = engine.observe();
        int buildingsBefore = before.myBuildings().size();

        // Add an intent that would build a Pylon if applied
        String probeTag = before.myUnits().get(0).tag();
        intentQueue.add(new BuildIntent(probeTag, BuildingType.PYLON, new Point2d(20, 20)));
        assertThat(intentQueue.pending()).hasSize(1);

        engine.dispatch(); // observe-only: drains but does not apply

        // Queue drained
        assertThat(intentQueue.pending()).isEmpty();
        // State unchanged — no Pylon was built
        assertThat(engine.observe().myBuildings().size()).isEqualTo(buildingsBefore);
    }

    @Test
    void leaveGameSetsDisconnected() {
        engine.connect();
        engine.leaveGame();
        assertThat(engine.isConnected()).isFalse();
    }

    @Test
    void isCompleteAfterAllEventsProcessed() {
        engine.connect();
        engine.joinGame();
        // Advance past the full 8m21s game (8*60 + 21 = 501 seconds × 22 loops = ~11022 loops)
        // 501s / (22 loops/tick ÷ 22.4 loops/sec) = ~501 ticks; use 600 to be safe
        for (int i = 0; i < 600; i++) engine.tick();
        assertThat(engine.isReplayComplete()).isTrue();
    }

    @Test
    void mineralsBecomeNonZeroAfterSeveralTicks() {
        engine.connect();
        engine.joinGame();
        for (int i = 0; i < 5; i++) engine.tick();
        assertThat(engine.observe().minerals()).isGreaterThan(0);
    }

    // --- Helpers ---

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), name);
            throw e;
        }
    }
}
