package org.acme.starcraft.qa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class QaEndpointsTest {

    @Test
    void getGameStateReturnsJson() {
        given()
            .when().get("/sc2/casefile")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("minerals", notNullValue())
            .body("supply", notNullValue());
    }

    @Test
    void getFrameReturnsFrameInfo() {
        given()
            .when().get("/sc2/frame")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("gameFrame", notNullValue())
            .body("connected", notNullValue());
    }

    @Test
    void getIntentsPendingReturnsEmptyInitially() {
        given()
            .when().get("/sc2/intents/pending")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("$", hasSize(0));
    }

    @Test
    void runKnownScenarioReturns204() {
        given()
            .when().post("/sc2/debug/scenario/set-resources-500")
            .then()
            .statusCode(204);
    }

    @Test
    void runUnknownScenarioReturns400() {
        given()
            .when().post("/sc2/debug/scenario/nonexistent")
            .then()
            .statusCode(400);
    }
}
