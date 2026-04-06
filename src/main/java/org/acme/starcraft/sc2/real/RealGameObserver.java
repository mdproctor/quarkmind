package org.acme.starcraft.sc2.real;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.domain.GameState;
import org.acme.starcraft.sc2.GameObserver;
import org.jboss.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads the latest GameState from SC2BotAgent (which stores it each ocraft frame).
 * SC2BotAgent is wired via setBotAgent() by RealSC2Client after agent creation.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class RealGameObserver implements GameObserver {
    private static final Logger log = Logger.getLogger(RealGameObserver.class);

    private SC2BotAgent botAgent;
    private final List<Consumer<GameState>> frameListeners = new ArrayList<>();

    /** Called by RealSC2Client after creating the SC2BotAgent. */
    public void setBotAgent(SC2BotAgent botAgent) {
        this.botAgent = botAgent;
    }

    /** Exposes the agent reference for SC2DebugScenarioRunner. */
    public SC2BotAgent getBotAgent() {
        return botAgent;
    }

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
        frameListeners.forEach(l -> l.accept(state));
        return state;
    }

    @Override
    public void addFrameListener(Consumer<GameState> listener) {
        frameListeners.add(listener);
    }

    private static GameState emptyState() {
        return new GameState(0, 0, 0, 0, List.of(), List.of(), List.of(), 0L);
    }
}
