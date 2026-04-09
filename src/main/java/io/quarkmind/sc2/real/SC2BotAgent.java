package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.bot.S2Agent;
import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.IntentQueue;
import org.jboss.logging.Logger;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    /**
     * Pending debug commands queued by SC2DebugScenarioRunner.
     * Debug API calls must be issued from within onStep(); commands posted
     * here are drained and executed (followed by sendDebug()) each frame.
     */
    private final Queue<Runnable> pendingDebugCommands = new ConcurrentLinkedQueue<>();

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

        // 2. Drain IntentQueue and dispatch commands.
        //    Commands MUST be issued within onStep() — ocraft enforces this.
        //    ActionTranslator resolves each Intent to a Tag + Ability + optional Point2d.
        List<ResolvedCommand> commands = ActionTranslator.translate(intentQueue.drainAll());
        commands.forEach(cmd ->
            cmd.target().ifPresentOrElse(
                pos -> actions().unitCommand(cmd.tag(), cmd.ability(), pos, false),
                ()  -> actions().unitCommand(cmd.tag(), cmd.ability(), false)
            )
        );

        // 3. Drain pending debug commands (queued by SC2DebugScenarioRunner).
        //    Debug API calls must be issued from onStep(); sendDebug() flushes them.
        if (!pendingDebugCommands.isEmpty()) {
            Runnable cmd;
            while ((cmd = pendingDebugCommands.poll()) != null) {
                cmd.run();
            }
            debug().sendDebug();
        }
    }

    @Override
    public void onGameEnd() {
        log.info("[SC2] Game ended");
    }

    /** Called by RealGameObserver — returns null until first onStep() fires. */
    public GameState getLatestGameState() {
        return latestGameState.get();
    }

    /**
     * Enqueue a debug command to be executed on the next onStep() frame.
     * SC2's debug API must be called from within the onStep() callback;
     * this queue bridges calls made outside that context.
     */
    public void enqueueDebugCommand(Runnable command) {
        pendingDebugCommands.add(command);
    }
}
