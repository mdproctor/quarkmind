package io.quarkmind.sc2.mock;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkmind.agent.AgentOrchestrator;
import org.jboss.logging.Logger;

/**
 * Auto-starts the mock or emulated game session on application startup.
 * Active in all profiles except {@code %sc2}, {@code %replay}, {@code %test}, and {@code %prod}.
 * Serves both {@code %mock} (via MockEngine) and {@code %emulated} (via EmulatedEngine) profiles —
 * it injects {@link AgentOrchestrator}, not a specific engine, so the active profile's engine
 * bean is resolved by CDI automatically.
 */
@UnlessBuildProfile(anyOf = {"sc2", "replay", "test", "prod"})
@ApplicationScoped
public class MockStartupBean {
    private static final Logger log = Logger.getLogger(MockStartupBean.class);

    @Inject AgentOrchestrator orchestrator;

    void onStart(@Observes StartupEvent ev) {
        log.info("[MOCK] Auto-starting mock game session on application startup...");
        try {
            orchestrator.startGame();
        } catch (Exception e) {
            log.errorf("[MOCK] Auto-start failed: %s — use POST /sc2/start to retry", e.getMessage());
        }
    }
}
