package io.quarkmind.qa;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.SimulatedGame;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class UnitResourceTest {

    @Inject SimulatedGame game;

    @Test
    void returnsUnitDetailsForKnownTag() {
        game.spawnFriendlyUnitForTesting(UnitType.STALKER, new Point2d(10, 10));
        String tag = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.STALKER)
            .findFirst().orElseThrow().tag();

        given().pathParam("tag", tag)
            .when().get("/qa/unit/{tag}")
            .then().statusCode(200)
            .body("type",      equalTo("STALKER"))
            .body("health",    greaterThan(0))
            .body("maxHealth", greaterThan(0));
    }

    @Test
    void returns404ForUnknownTag() {
        given().pathParam("tag", "nonexistent-tag-xyz")
            .when().get("/qa/unit/{tag}")
            .then().statusCode(404);
    }
}
