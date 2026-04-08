package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.domain.GameState;

@UnlessBuildProfile("prod")
@Path("/sc2")
@Produces(MediaType.APPLICATION_JSON)
public class CaseFileResource {

    @Inject SC2Engine engine;

    @GET
    @Path("/casefile")
    public GameState getGameState() {
        return engine.observe();
    }
}
