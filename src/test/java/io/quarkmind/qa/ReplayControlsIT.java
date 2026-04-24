package io.quarkmind.qa;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestProfile(ReplayControlsIT.ReplayProfile.class)
class ReplayControlsIT {

    public static class ReplayProfile implements QuarkusTestProfile {
        @Override public String getConfigProfile() { return "replay"; }
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.scheduler.enabled", "false");
        }
    }

    @Test
    void statusEndpointReturnsReplayState() {
        given().when().get("/qa/replay/status")
            .then().statusCode(200)
            .body("paused",     notNullValue())
            .body("loop",       greaterThanOrEqualTo(0))
            .body("totalLoops", greaterThan(0));
    }

    @Test
    void pauseAndResumeWork() {
        given().when().post("/qa/replay/pause").then().statusCode(204);
        given().when().get("/qa/replay/status").then().body("paused", is(true));
        given().when().post("/qa/replay/resume").then().statusCode(204);
        given().when().get("/qa/replay/status").then().body("paused", is(false));
    }

    @Test
    void seekDoesNotError() {
        given().queryParam("loop", 200).when().post("/qa/replay/seek")
            .then().statusCode(204);
    }

    @Test
    void speedEndpointStoresMultiplier() {
        given().queryParam("multiplier", 2).when().post("/qa/replay/speed")
            .then().statusCode(204);
        given().when().get("/qa/replay/status")
            .then().body("speed", is(2));
        // Reset to 1
        given().queryParam("multiplier", 1).when().post("/qa/replay/speed").then().statusCode(204);
    }

    @Test
    void invalidSpeedReturnsBadRequest() {
        given().queryParam("multiplier", 99).when().post("/qa/replay/speed")
            .then().statusCode(400);
    }
}
