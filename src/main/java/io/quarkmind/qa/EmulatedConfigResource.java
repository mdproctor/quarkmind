package io.quarkmind.qa;

import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@UnlessBuildProfile("prod")
@Path("/qa/emulated/config")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatedConfigResource {

    @Inject EmulatedConfig config;

    @GET
    public EmulatedConfig.Snapshot getConfig() {
        return config.snapshot();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateConfig(Map<String, Object> updates) {
        if (updates.containsKey("waveSpawnFrame"))
            config.setWaveSpawnFrame(((Number) updates.get("waveSpawnFrame")).intValue());
        if (updates.containsKey("waveUnitCount"))
            config.setWaveUnitCount(((Number) updates.get("waveUnitCount")).intValue());
        if (updates.containsKey("waveUnitType"))
            config.setWaveUnitType((String) updates.get("waveUnitType"));
        if (updates.containsKey("unitSpeed"))
            config.setUnitSpeed(((Number) updates.get("unitSpeed")).doubleValue());
        return Response.ok(config.snapshot()).build();
    }
}
