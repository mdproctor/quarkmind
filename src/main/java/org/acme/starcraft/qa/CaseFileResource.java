package org.acme.starcraft.qa;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.acme.starcraft.sc2.mock.SimulatedGame;
import org.acme.starcraft.domain.GameState;

@Path("/sc2")
@Produces(MediaType.APPLICATION_JSON)
public class CaseFileResource {

    @Inject SimulatedGame simulatedGame;

    @GET
    @Path("/casefile")
    public GameState getGameState() {
        return simulatedGame.snapshot();
    }
}
