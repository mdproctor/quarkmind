package io.quarkmind.agent;

import io.casehub.coordination.CaseEngine;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.GameStopped;
import io.quarkmind.sc2.SC2Engine;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class AgentOrchestrator {
    private static final Logger log = Logger.getLogger(AgentOrchestrator.class);

    @Inject SC2Engine engine;
    @Inject GameStateTranslator translator;
    @Inject CaseEngine caseEngine;
    @Inject Event<GameStarted> gameStartedEvent;
    @Inject Event<GameStopped> gameStoppedEvent;

    /**
     * Per-phase timing from the most recent completed tick.
     * Written after every gameTick(); read by GameLoopBenchmarkTest.
     */
    public record TickTimings(long physicsMs, long pluginsMs, long dispatchMs) {
        public long totalMs() { return physicsMs + pluginsMs + dispatchMs; }
    }

    private final AtomicReference<TickTimings> lastTickTimings = new AtomicReference<>();

    /** Returns timings from the last completed tick, or null if no tick has run yet. */
    public TickTimings getLastTickTimings() { return lastTickTimings.get(); }

    public void startGame() {
        engine.connect();
        engine.joinGame();
        gameStartedEvent.fire(new GameStarted());
        log.info("Game started");
    }

    public void stopGame() {
        engine.leaveGame();
        gameStoppedEvent.fire(new GameStopped());
        log.info("Game stopped");
    }

    @Scheduled(every = "${starcraft.tick.interval:500ms}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void gameTick() {
        if (!engine.isConnected()) return;

        long t0 = System.currentTimeMillis();
        engine.tick();
        var gameState = engine.observe();
        long t1 = System.currentTimeMillis();

        Map<String, Object> caseData = translator.toMap(gameState);
        try {
            caseEngine.createAndSolve("starcraft-game", caseData, Duration.ofSeconds(5));
        } catch (Exception e) {
            log.errorf("CaseEngine decision cycle failed at frame %d: %s", gameState.gameFrame(), e.getMessage());
        }
        long t2 = System.currentTimeMillis();

        engine.dispatch();
        long t3 = System.currentTimeMillis();

        var timings = new TickTimings(t1 - t0, t2 - t1, t3 - t2);
        lastTickTimings.set(timings);
        log.debugf("Tick %d — physics=%dms plugins=%dms dispatch=%dms total=%dms | minerals=%d supply=%d/%d",
            gameState.gameFrame(), timings.physicsMs(), timings.pluginsMs(), timings.dispatchMs(), timings.totalMs(),
            gameState.minerals(), gameState.supplyUsed(), gameState.supply());
    }
}
