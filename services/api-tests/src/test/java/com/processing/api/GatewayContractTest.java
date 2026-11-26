package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.math.BigDecimal;
import static org.awaitility.Awaitility.await;
import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class GatewayContractTest {
    @Test
    void healthyGatewayMatchesResponseSchema() {
        given()
            .spec(GATEWAY)

        .when()
            .get("/health")

        .then()
            .statusCode(200)
            .header("Content-Type", containsString("application/json"))
            .body(io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath("schemas/health.json"))
            .body("status", equalTo("ok"), "service", equalTo("gateway"),
                "services.authorization", equalTo("ok"), "services.switch", equalTo("ok"),
                "services.cardManagement", equalTo("ok"), "services.logger", equalTo("ok"));
    }

    @Test
    void dashboardExposesCountsAndTimeSeries() {
        given()
            .spec(GATEWAY)

        .when()
            .get("/api/dashboard/stats")

        .then()
            .statusCode(200)
            .body("totalTransactions", greaterThanOrEqualTo(0), "approvedCount", greaterThanOrEqualTo(0),
                "declinedCount", greaterThanOrEqualTo(0));

        given()
            .spec(GATEWAY)
            .queryParam("limit", 1)

        .when()
            .get("/api/dashboard/recent")

        .then()
            .statusCode(200)
            .body("size()", lessThanOrEqualTo(1));

        given()
            .spec(GATEWAY)
            .queryParam("granularity", "day")

        .when()
            .get("/api/dashboard/charts")

        .then()
            .statusCode(200)
            .body("$", instanceOf(List.class));
    }

    @ParameterizedTest
    @CsvSource({"terminal,normal", "merchant,grocery"})
    void simulatorSubmitsOnePayment(String simulator, String scenario) {
        createCard();

        given()
            .spec(GATEWAY)
            .body(Map.of("count", 1, "scenario", scenario, "tps", 1))

        .when()
            .post("/api/simulator/" + simulator + "/run")

        .then()
            .statusCode(200)
            .body("totalSubmitted", equalTo(1), "transactions", hasSize(1),
                "transactions[0].status", oneOf("APPROVED", "DECLINED"));
    }

    @ParameterizedTest
    @CsvSource({"terminal,normal", "merchant,grocery"})
    void simulatorRejectsZeroCount(String simulator, String scenario) {
        given()
            .spec(GATEWAY)
            .body(Map.of("count", 0, "scenario", scenario, "tps", 1))

        .when()
            .post("/api/simulator/" + simulator + "/run")

        .then()
            .statusCode(400)
            .body("message", containsString("count"), "serviceName",
                equalTo("terminal".equals(simulator) ? "terminal-simulator" : "Merchant acquirer simulator"));
    }

    @Test
    void terminalCanStartAndStop() {
        createCard();
        try {
            given()
                .spec(GATEWAY)
                .body(Map.of("tps", 1, "transactionType", "NORMAL"))

            .when()
                .post("/api/simulator/terminal/start-continuous")

            .then()
                .statusCode(200)
                .body(emptyString());
        } finally {
            given()
                .spec(GATEWAY)

            .when()
                .post("/api/simulator/terminal/stop")

            .then()
                .statusCode(200)
                .body(emptyString());
        }
    }

    @Test
    void merchantRejectsFeeLookupForUnknownPayment() {
        var request = payment(createCard());
        var fee = Map.of("stan", request.get("stan"), "pan", request.get("pan"), "amount", request.get("amount"),
                "terminalId", request.get("terminalId"), "transmissionDateTime", request.get("transmissionDateTime"));

        given()
            .spec(GATEWAY)
            .body(fee)

        .when()
            .post("/api/simulator/merchant/fee")

        .then()
            .statusCode(404)
            .body("error", equalTo("Resource not found"),
                "serviceName", equalTo("Merchant acquirer simulator"));
    }

    @Test
    void merchantFeeMatchesTheLoggedPayment() {
        createCard();
        String recent = "/api/dashboard/recent";

        List<String> before = given()
            .spec(GATEWAY)
            .queryParam("limit", 500)

        .when()
            .get(recent)

        .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("id");

        String stan = given()
            .spec(GATEWAY)
            .body(Map.of("count", 1, "scenario", "grocery", "tps", 1))

        .when()
            .post("/api/simulator/merchant/run")

        .then()
            .statusCode(200)
            .body("transactions", hasSize(1))
            .extract()
            .path("transactions[0].stan");
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            List<Map<String, Object>> rows = given()
                .spec(GATEWAY)
                .queryParam("limit", 500)

            .when()
                .get(recent)

            .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
            var matches = rows.stream().filter(row -> stan.equals(row.get("stan")) && !before.contains(row.get("id"))).toList();
            assertThat(matches, hasSize(1));
            var row = matches.get(0);
            var lookup = Map.of("transmissionDateTime", row.get("transmissionDateTime"), "pan", row.get("pan"),
                    "stan", stan, "terminalId", row.get("terminalId"), "amount", row.get("amount"));

            var fee = given()
                .spec(GATEWAY)
                .body(lookup)

            .when()
                .post("/api/simulator/merchant/fee")

            .then()
                .statusCode(200)
                .body("acquirerFee", notNullValue())
                .extract()
                .jsonPath()
                .getString("acquirerFee");
            assertThat(row.get("acquiringFee"), notNullValue());
            assertThat(new BigDecimal(fee), comparesEqualTo(new BigDecimal(row.get("acquiringFee").toString())));
        });
    }

    @Test
    void merchantDirectoryExistsDownstreamButIsMissingAtGateway() {
        String path = "/api/simulator/merchants";

        given()
            .spec(Api.service("merchant", 8084))

        .when()
            .get(path)

        .then()
            .statusCode(200)
            .body("$", instanceOf(List.class));

        given()
            .spec(GATEWAY)

        .when()
            .get(path)

        .then()
            .statusCode(404)
            .body("path", equalTo(path));
    }

    @Test
    void binRoutingReturnsIssuerForKnownPrefix() {
        var bin = Api.service("bin", 8096);

        given()
            .spec(bin)

        .when()
            .get("/api/bin/400000")

        .then()
            .statusCode(200)
            .body("bin", equalTo("400000"), "issuerId", not(emptyString()));

        given()
            .spec(bin)

        .when()
            .get("/api/bin/999999")

        .then()
            .statusCode(404)
            .body(emptyString());

        given()
            .spec(bin)
            .queryParam("fail", true)

        .when()
            .get("/api/bin/400000")

        .then()
            .statusCode(500)
            .body(emptyString());
    }
}
