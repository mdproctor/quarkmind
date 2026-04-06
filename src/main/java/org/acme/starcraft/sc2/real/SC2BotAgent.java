package org.acme.starcraft.sc2.real;

import com.github.ocraft.s2client.bot.S2Agent;
import org.acme.starcraft.domain.GameState;
import org.acme.starcraft.sc2.IntentQueue;
import org.jboss.logging.Logger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ocraft S2Agent bridge. NOT a CDI bean — lifecycle owned by S2Coordinator.
 *
 * Two responsibilities:
 *  1. Store the latest SC2 observation as GameState for RealGameObserver to poll.
 *  2. Drain IntentQueue and send commands to SC2 each frame (MUST happen within onStep()).
 *     Phase 1: logs intents as no-ops. Phase 3+: translates to ocraft action calls.
 */
public class SC2BotAgent extends S2Agent {
    private static final Logger log = Logger.getLogger(SC2BotAgent.class);

    private final IntentQueue intentQueue;
    private final AtomicReference<GameState> latestGameState = new AtomicReference<>(null);

    public SC2BotAgent(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override
    public void onGameStart() {
        log.info("[SC2] Game started");
    }

    @Override
    public void onStep() {
        // 1. Translate and store the current observation
        try {
            GameState state = ObservationTranslator.translate(observation());
            latestGameState.set(state);
        } catch (Exception e) {
            log.warnf("[SC2] Observation translation failed: %s", e.getMessage());
        }

        // 2. Drain IntentQueue — commands MUST be sent within onStep().
        //    Phase 1: dummy plugins produce no intents — queue is always empty.
        //    Phase 3+: translate Intent types to ocraft actions here.
        intentQueue.drainAll().forEach(intent ->
            log.debugf("[SC2] Intent (Phase 1 no-op): %s", intent));
    }

    @Override
    public void onGameEnd() {
        log.info("[SC2] Game ended");
    }

    /** Called by RealGameObserver — returns null until first onStep() fires. */
    public GameState getLatestGameState() {
        return latestGameState.get();
    }
}
