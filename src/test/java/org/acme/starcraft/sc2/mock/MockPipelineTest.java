package org.acme.starcraft.sc2.mock;

import org.acme.starcraft.domain.UnitType;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MockPipelineTest {

    SimulatedGame game;
    MockGameObserver observer;
    MockCommandDispatcher dispatcher;
    IntentQueue intentQueue;

    @BeforeEach
    void setUp() {
        game = new SimulatedGame();
        game.reset();
        intentQueue = new IntentQueue();
        observer = new MockGameObserver(game);
        dispatcher = new MockCommandDispatcher(game, intentQueue);
    }

    @Test
    void observerReflectsInitialGameState() {
        var state = observer.observe();
        assertThat(state.minerals()).isEqualTo(50);
        assertThat(state.myUnits().stream().filter(u -> u.type() == UnitType.PROBE).count()).isEqualTo(12);
    }

    @Test
    void dispatchDrainsIntentQueueAndMutatesGame() {
        String nexusTag = game.snapshot().myBuildings().get(0).tag();
        intentQueue.add(new TrainIntent(nexusTag, UnitType.ZEALOT));
        assertThat(intentQueue.pending()).hasSize(1);

        dispatcher.dispatch();
        game.tick(); // complete training

        assertThat(intentQueue.pending()).isEmpty();
        assertThat(intentQueue.recentlyDispatched()).hasSize(1);
        assertThat(game.snapshot().supplyUsed()).isEqualTo(14);
    }

    @Test
    void observerFrameListenerCalledOnObserve() {
        var captured = new java.util.ArrayList<>();
        observer.addFrameListener(captured::add);
        observer.observe();
        assertThat(captured).hasSize(1);
    }
}
