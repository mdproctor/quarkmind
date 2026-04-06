package org.acme.starcraft.sc2;

public interface SC2Client {
    void connect();
    void joinGame();
    void leaveGame();
    boolean isConnected();
}
