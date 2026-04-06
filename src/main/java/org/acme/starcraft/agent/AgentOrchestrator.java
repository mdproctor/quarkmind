package org.acme.starcraft.agent;

import io.casehub.coordination.CaseEngine;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.sc2.SC2Engine;
import org.jboss.logging.Logger;
import java.time.Duration;
import java.util.Map;

@ApplicationScoped
public class AgentOrchestrator {
    private static final Logger log = Logger.getLogger(AgentOrchestrator.class);

    @Inject SC2Engine engine;
    @Inject GameStateTranslator translator;
    @Inject CaseEngine caseEngine;

    public void startGame() {
        engine.connect();
        engine.joinGame();
        log.info("Game started");
    }

    public void stopGame() {
        engine.leaveGame();
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
