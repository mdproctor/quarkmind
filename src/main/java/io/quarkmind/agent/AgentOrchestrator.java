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

@ApplicationScoped
public class AgentOrchestrator {
    private static final Logger log = Logger.getLogger(AgentOrchestrator.class);

    @Inject SC2Engine engine;
    @Inject GameStateTranslator translator;
    @Inject CaseEngine caseEngine;
    @Inject Event<GameStarted> gameStartedEvent;
    @Inject Event<GameStopped> gameStoppedEvent;

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

        engine.tick();

        var gameState = engine.observe();
        Map<String, Object> caseData = translator.toMap(gameState);

        try {
            caseEngine.createAndSolve("starcraft-game", caseData, Duration.ofSeconds(5));
        } catch (Exception e) {
            log.errorf("CaseEngine decision cycle failed at frame %d: %s", gameState.gameFrame(), e.getMessage());
        }

        engine.dispatch();
        log.debugf("Tick complete — frame=%d minerals=%d supplyUsed=%d/%d",
            gameState.gameFrame(), gameState.minerals(), gameState.supplyUsed(), gameState.supply());
    }
}
