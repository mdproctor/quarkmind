package org.acme.starcraft.sc2.real;

import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.protocol.game.BattlenetMap;
import com.github.ocraft.s2client.protocol.game.Difficulty;
import com.github.ocraft.s2client.protocol.game.Race;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.SC2Client;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@IfBuildProfile("sc2")
@ApplicationScoped
public class RealSC2Client implements SC2Client {
    private static final Logger log = Logger.getLogger(RealSC2Client.class);

    @Inject
    IntentQueue intentQueue;

    @Inject
    RealGameObserver gameObserver;

    @ConfigProperty(name = "starcraft.sc2.map", defaultValue = "Simple128")
    String mapName;

    @ConfigProperty(name = "starcraft.sc2.difficulty", defaultValue = "VERY_EASY")
    String difficultyStr;

    private S2Coordinator coordinator;
    private SC2BotAgent botAgent;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private CompletableFuture<Void> gameLoop;

    @Override
    @Retry(maxRetries = 3, delay = 2000)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10000)
    @Fallback(fallbackMethod = "connectFallback")
    public void connect() {
        log.info("[SC2] Connecting to StarCraft II...");
        botAgent = new SC2BotAgent(intentQueue);
        gameObserver.setBotAgent(botAgent);

        Difficulty difficulty = Difficulty.valueOf(difficultyStr);

        // S2Coordinator.setup() → loadSettings() → setParticipants() → launchStarcraft()
        // launchStarcraft() returns S2Coordinator directly (no separate .create() call)
        coordinator = S2Coordinator.setup()
                .loadSettings(new String[]{})
                .setParticipants(
                        S2Coordinator.createParticipant(Race.PROTOSS, botAgent),
                        S2Coordinator.createComputer(Race.RANDOM, difficulty)
                )
                .launchStarcraft();

        connected.set(true);
        log.info("[SC2] Connected — coordinator ready");
    }

    public void connectFallback() {
        log.error("[SC2] Failed to connect after retries — bot will run without SC2");
    }

    @Override
    public void joinGame() {
        if (coordinator == null) {
            log.error("[SC2] Cannot join game — coordinator not initialised");
            return;
        }
        log.infof("[SC2] Starting game on map: %s", mapName);
        coordinator.startGame(BattlenetMap.of(mapName));

        // Run ocraft's game loop in a background thread.
        // coordinator.update() returns false when the game ends.
        gameLoop = CompletableFuture.runAsync(() -> {
            log.info("[SC2] Game loop started");
            while (coordinator.update()) {
                // SC2BotAgent.onStep() is called by ocraft each frame
            }
            connected.set(false);
            log.info("[SC2] Game loop ended");
        }).exceptionally(e -> {
            log.errorf("[SC2] Game loop error: %s", e.getMessage());
            connected.set(false);
            return null;
        });
    }

    @Override
    public void leaveGame() {
        connected.set(false);
        if (coordinator != null) {
            coordinator.quit();
        }
        if (gameLoop != null) {
            gameLoop.cancel(true);
        }
        log.info("[SC2] Left game");
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }
}
