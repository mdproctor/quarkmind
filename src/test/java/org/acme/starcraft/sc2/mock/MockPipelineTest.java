package org.acme.starcraft.sc2.mock;

import org.acme.starcraft.domain.UnitType;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MockPipelineTest {

    SimulatedGame game;
    IntentQueue intentQueue;
    MockEngine engine;

    @BeforeEach
    void setUp() {
        game = new SimulatedGame();
        game.reset();
        intentQueue = new IntentQueue();
        engine = new MockEngine(game, intentQueue);
        engine.connect();
        engine.joinGame();
    }

    @Test
    void observeReflectsInitialGameState() {
        var state = engine.observe();
        assertThat(state.minerals()).isEqualTo(50);
        assertThat(state.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count()).isEqualTo(12);
    }

    @Test
    void dispatchDrainsIntentQueueAndMutatesGame() {
        String nexusTag = engine.observe().myBuildings().get(0).tag();
        intentQueue.add(new TrainIntent(nexusTag, UnitType.ZEALOT));
        assertThat(intentQueue.pending()).hasSize(1);

        engine.dispatch();
        engine.tick(); // complete training

        assertThat(intentQueue.pending()).isEmpty();
        assertThat(intentQueue.recentlyDispatched()).hasSize(1);
        assertThat(engine.observe().supplyUsed()).isEqualTo(14);
    }

    @Test
    void frameListenerCalledOnObserve() {
        var captured = new ArrayList<>();
        engine.addFrameListener(captured::add);
        engine.observe();
        assertThat(captured).hasSize(1);
    }
}
