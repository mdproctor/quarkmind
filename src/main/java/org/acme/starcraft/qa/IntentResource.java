package org.acme.starcraft.qa;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.acme.starcraft.sc2.IntentQueue;
import org.acme.starcraft.sc2.mock.SimulatedGame;
import java.util.List;
import java.util.Map;

@Path("/sc2")
@Produces(MediaType.APPLICATION_JSON)
public class IntentResource {

    @Inject IntentQueue intentQueue;
    @Inject SimulatedGame simulatedGame;

    @GET
    @Path("/intents/pending")
    public List<Object> pending() {
        return List.copyOf(intentQueue.pending());
    }

    @GET
    @Path("/intents/dispatched")
    public List<Object> dispatched() {
        return List.copyOf(intentQueue.recentlyDispatched());
    }

    @GET
    @Path("/frame")
    public Map<String, Object> frame() {
        var state = simulatedGame.snapshot();
        return Map.of(
            "gameFrame", state.gameFrame(),
            "connected", true,
            "pendingIntents", intentQueue.pending().size()
        );
    }
}
