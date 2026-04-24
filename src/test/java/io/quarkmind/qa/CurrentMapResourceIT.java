package io.quarkmind.qa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;

@QuarkusTest
class CurrentMapResourceIT {

    @Test
    void currentMapReturns404InMockProfile() {
        given()
            .when().get("/qa/current-map")
            .then()
            .statusCode(404);
    }
}
