package org.acme.starcraft.qa;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.starcraft.sc2.ScenarioRunner;
import java.util.Map;

@Path("/sc2/debug")
@Produces(MediaType.APPLICATION_JSON)
public class ScenarioResource {

    @Inject ScenarioRunner scenarioRunner;

    @POST
    @Path("/scenario/{name}")
    public Response runScenario(@PathParam("name") String name) {
        try {
            scenarioRunner.run(name);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", e.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
        }
    }
}
