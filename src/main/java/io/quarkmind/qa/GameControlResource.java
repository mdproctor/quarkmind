package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.quarkmind.agent.AgentOrchestrator;
import org.jboss.logging.Logger;

@UnlessBuildProfile("prod")
@Path("/sc2")
@Produces(MediaType.APPLICATION_JSON)
public class GameControlResource {
    private static final Logger log = Logger.getLogger(GameControlResource.class);

    @Inject AgentOrchestrator orchestrator;

    @POST
    @Path("/start")
    @Consumes(MediaType.WILDCARD)
    public Response startGame() {
        try {
            orchestrator.startGame();
            return Response.ok(java.util.Map.of("status", "started")).build();
        } catch (Exception e) {
            log.errorf("Failed to start game: %s", e.getMessage());
            return Response.serverError()
                .entity(java.util.Map.of("error", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/stop")
    @Consumes(MediaType.WILDCARD)
    public Response stopGame() {
        orchestrator.stopGame();
        return Response.ok(java.util.Map.of("status", "stopped")).build();
    }
}
