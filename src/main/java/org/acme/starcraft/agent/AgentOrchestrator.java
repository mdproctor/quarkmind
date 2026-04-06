package org.acme.starcraft.agent;

import io.casehub.coordination.CaseEngine;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.sc2.CommandDispatcher;
import org.acme.starcraft.sc2.GameObserver;
import org.acme.starcraft.sc2.SC2Client;
import org.acme.starcraft.sc2.mock.SimulatedGame;
import org.jboss.logging.Logger;
import java.time.Duration;
import java.util.Map;

@ApplicationScoped
public class AgentOrchestrator {
    private static final Logger log = Logger.getLogger(AgentOrchestrator.class);

    @Inject SC2Client sc2Client;
    @Inject GameObserver gameObserver;
    @Inject GameStateTranslator translator;
    @Inject CaseEngine caseEngine;
    @Inject CommandDispatcher commandDispatcher;
    @Inject SimulatedGame simulatedGame;

    public void startGame() {
        simulatedGame.reset();
        sc2Client.connect();
        sc2Client.joinGame();
        log.info("Game started — mock SC2 ready");
    }

    public void stopGame() {
        sc2Client.leaveGame();
        log.info("Game stopped");
    }

    @Scheduled(every = "${starcraft.tick.interval:500ms}")
    void gameTick() {
        if (!sc2Client.isConnected()) return;

        simulatedGame.tick();
        var gameState = gameObserver.observe();
        Map<String, Object> caseData = translator.toMap(gameState);

        try {
            caseEngine.createAndSolve("starcraft-game", caseData, Duration.ofSeconds(5));
        } catch (Exception e) {
            // Catches CaseCreationException and timeout exceptions.
            // Narrow this catch once the exact checked exceptions from CaseEngine are confirmed.
            log.errorf("CaseEngine decision cycle failed at frame %d: %s", gameState.gameFrame(), e.getMessage());
        }

        commandDispatcher.dispatch();
        log.debugf("Tick complete — frame=%d minerals=%d supplyUsed=%d/%d",
            gameState.gameFrame(), gameState.minerals(), gameState.supplyUsed(), gameState.supply());
    }
}
