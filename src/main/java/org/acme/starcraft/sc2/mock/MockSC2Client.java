package org.acme.starcraft.sc2.mock;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.starcraft.sc2.SC2Client;
import org.jboss.logging.Logger;

@UnlessBuildProfile("sc2")
@ApplicationScoped
public class MockSC2Client implements SC2Client {
    private static final Logger log = Logger.getLogger(MockSC2Client.class);
    private boolean connected = false;

    @Override
    public void connect() {
        connected = true;
        log.info("[MOCK] SC2Client connected");
    }

    @Override
    public void joinGame() {
        log.info("[MOCK] Joined game");
    }

    @Override
    public void leaveGame() {
        connected = false;
        log.info("[MOCK] Left game");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }
}
