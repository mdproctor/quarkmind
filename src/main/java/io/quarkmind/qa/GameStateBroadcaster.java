package io.quarkmind.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.sc2.emulated.VisibilityHolder;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@UnlessBuildProfile("prod")
@ApplicationScoped
public class GameStateBroadcaster {

    private static final Logger log = Logger.getLogger(GameStateBroadcaster.class);

    @Inject SC2Engine engine;
    @Inject ObjectMapper objectMapper;
    @Inject VisibilityHolder visibilityHolder;

    private final Set<WebSocketConnection> sessions = new CopyOnWriteArraySet<>();
    private volatile boolean suppressed = false;

    public void setSuppressed(boolean s) { this.suppressed = s; }

    @PostConstruct
    void init() {
        engine.addFrameListener(this::onFrame);
    }

    /** Called by {@link GameStateSocket} on WebSocket open. */
    void addSession(WebSocketConnection connection) {
        sessions.add(connection);
        log.infof("[VISUALIZER] Client connected — %d active", sessions.size());
    }

    /** Called by {@link GameStateSocket} on WebSocket close. */
    void removeSession(WebSocketConnection connection) {
        sessions.remove(connection);
        log.infof("[VISUALIZER] Client disconnected — %d active", sessions.size());
    }

    /** Package-private for testing — pure serialisation, no I/O. */
    String toJson(GameState state) throws Exception {
        var vg  = visibilityHolder.get();
        var vis = vg != null ? vg.encode() : null;
        return objectMapper.writeValueAsString(new GameStateBroadcast(state, vis));
    }

    private void onFrame(GameState state) {
        if (suppressed || sessions.isEmpty()) return;
        try {
            String json = toJson(state);
            sessions.forEach(s -> s.sendText(json)
                .subscribe().with(
                    ignored -> {},
                    err -> log.warnf("[VISUALIZER] Send failed: %s", err.getMessage())));
        } catch (Exception e) {
            log.warnf(e, "[VISUALIZER] Serialisation failed: %s", e.getMessage());
        }
    }
}
