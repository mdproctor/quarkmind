package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.sc2.IntentQueue;
import java.util.List;
import java.util.Map;

@UnlessBuildProfile("prod")
@Path("/sc2")
@Produces(MediaType.APPLICATION_JSON)
public class IntentResource {

    @Inject IntentQueue intentQueue;
    @Inject SC2Engine engine;

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
        var state = engine.observe();
        return Map.of(
            "gameFrame", state.gameFrame(),
            "connected", true,
            "pendingIntents", intentQueue.pending().size()
        );
    }
}
