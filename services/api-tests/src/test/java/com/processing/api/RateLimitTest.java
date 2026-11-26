package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledIfSystemProperty(named = "limited.url", matches = ".+")
class RateLimitTest {
    @Test
    void throttledRequestReportsRetryDelay() {
        var gateway = Api.service("limited", 8080);
        for (int i = 0; i < 5; i++) {
            var response = given()
                .spec(gateway)
                .body(Map.of())

            .when()
                .post("/api/transactions");

            if (response.statusCode() == 429) {
                response.then()
                    .header("Retry-After", equalTo("1"))
                    .body("error", equalTo("RATE_LIMIT_EXCEEDED"), "retryAfterMs", equalTo(1000));
                return;
            }

            response.then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"));
        }
        fail("The one-token Gateway accepted all five consecutive requests");
    }
}
