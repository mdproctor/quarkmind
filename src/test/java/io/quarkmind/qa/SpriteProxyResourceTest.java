package io.quarkmind.qa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class SpriteProxyResourceTest {

    @Test
    void unknownSpriteReturns404() {
        given()
            .when().get("/qa/sprites/unknown.jpg")
            .then()
            .statusCode(404);
    }

    @Test
    void knownSpriteReturns200WithJpegContentType() {
        // Requires internet access to Liquipedia
        given()
            .when().get("/qa/sprites/SC2Probe.jpg")
            .then()
            .statusCode(200)
            .contentType("image/jpeg");
    }
}
