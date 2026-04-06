package org.acme.starcraft.sc2.mock;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.starcraft.sc2.CommandDispatcher;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.intent.Intent;
import org.jboss.logging.Logger;
import java.util.List;

@UnlessBuildProfile("sc2")
@ApplicationScoped
public class MockCommandDispatcher implements CommandDispatcher {
    private static final Logger log = Logger.getLogger(MockCommandDispatcher.class);

    private final SimulatedGame game;
    private final IntentQueue intentQueue;

    @Inject
    public MockCommandDispatcher(SimulatedGame game, IntentQueue intentQueue) {
        this.game = game;
        this.intentQueue = intentQueue;
    }

    @Override
    public void dispatch() {
        List<Intent> intents = intentQueue.drainAll();
        intents.forEach(intent -> {
            log.debugf("[MOCK] Dispatching intent: %s", intent);
            game.applyIntent(intent);
        });
    }
}
