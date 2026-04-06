package org.acme.starcraft.sc2.real;

import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.protocol.game.BattlenetMap;
import com.github.ocraft.s2client.protocol.game.Difficulty;
import com.github.ocraft.s2client.protocol.game.Race;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.domain.GameState;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.SC2Engine;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Real SC2 engine — connects to a live StarCraft II process via ocraft-s2client.
 * Active only in the {@code %sc2} profile.
 *
 * <p>ocraft is callback-driven: {@link SC2BotAgent#onStep()} fires each game frame
 * and is the only place where SC2 commands may be issued. Consequently:
 * <ul>
 *   <li>{@link #tick()} is a no-op — ocraft owns the game clock.</li>
 *   <li>{@link #dispatch()} is a no-op — {@code SC2BotAgent.onStep()} drains {@link IntentQueue}.</li>
 *   <li>{@link #observe()} polls the {@link GameState} stored by the most recent {@code onStep()}.</li>
 * </ul>
 *
 * <p>{@link #getBotAgent()} exposes the agent reference for {@link SC2DebugScenarioRunner}.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class RealSC2Engine implements SC2Engine {

    private static final Logger log = Logger.getLogger(RealSC2Engine.class);

    @Inject IntentQueue intentQueue;

    @ConfigProperty(name = "starcraft.sc2.map", defaultValue = "Simple128")
    String mapName;

    @ConfigProperty(name = "starcraft.sc2.difficulty", defaultValue = "VERY_EASY")
    String difficultyStr;

    private S2Coordinator coordinator;
    private SC2BotAgent botAgent;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private CompletableFuture<Void> gameLoop;

    // --- Lifecycle ---

    @Override
    @Retry(maxRetries = 3, delay = 2000)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10000)
    @Fallback(fallbackMethod = "connectFallback")
    public void connect() {
        log.info("[SC2] Connecting to StarCraft II...");
        botAgent = new SC2BotAgent(intentQueue);

        Difficulty difficulty = Difficulty.valueOf(difficultyStr);
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
                // SC2BotAgent.onStep() is called by ocraft each game frame
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
        if (coordinator != null) coordinator.quit();
        if (gameLoop != null) gameLoop.cancel(true);
        log.info("[SC2] Left game");
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    // --- Per-tick: no-ops for real SC2 ---

    /** No-op — ocraft drives the game clock via {@code coordinator.update()}. */
    @Override
    public void tick() {}

    /** Polls the GameState stored by the most recent {@code SC2BotAgent.onStep()} call. */
    @Override
    public GameState observe() {
        if (botAgent == null) {
            log.warn("[SC2] observe() called before SC2BotAgent was set — returning empty state");
            return emptyState();
        }
        GameState state = botAgent.getLatestGameState();
        if (state == null) {
            log.debug("[SC2] No observation yet — first frame pending");
            return emptyState();
        }
        return state;
    }

    /** No-op — {@code SC2BotAgent.onStep()} drains {@link IntentQueue} within the SC2 frame callback. */
    @Override
    public void dispatch() {}

    // --- Extension point for SC2DebugScenarioRunner ---

    /** Returns the underlying bot agent. Used by {@link SC2DebugScenarioRunner} to enqueue debug commands. */
    public SC2BotAgent getBotAgent() {
        return botAgent;
    }

    // --- Helpers ---

    private static GameState emptyState() {
        return new GameState(0, 0, 0, 0, List.of(), List.of(), List.of(), 0L);
    }
}
