package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import java.util.List;
import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class BoundaryContractTest {
    @ParameterizedTest
    @CsvSource({"GET", "PATCH", "DELETE"})
    void missingCardIsReportedForEachMethod(String method) {
        given()
            .spec(GATEWAY)
            .body(Map.of("dailyLimit", 100))

        .when()
            .request(method, "/api/cards/999999" + digits(10))

        .then()
            .statusCode(404)
            .body("serviceName", equalTo("card-management"),
                "error", equalTo("CardNotFoundException"), "message", containsString("not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE"})
    void malformedPanIsRejectedForEachMethod(String method) {
        given()
            .spec(GATEWAY)
            .body(Map.of("dailyLimit", 100))

        .when()
            .request(method, "/api/cards/123")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("card-management"),
                "message", containsString("16 digits"));
    }

    @ParameterizedTest
    @CsvSource({"dailyLimit,-1", "monthlyLimit,-1"})
    void patchRejectsNegativeLimits(String field, int value) {
        String pan = createCard();

        given()
            .spec(GATEWAY)
            .body(Map.of(field, value))

        .when()
            .patch("/api/cards/{pan}", pan)

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("card-management"),
                "message", containsString("negative"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void generationRequiresPositiveCount(int count) {
        given()
            .spec(GATEWAY)
            .body(Map.of("count", count, "bins", List.of("400000")))

        .when()
            .post("/api/cards/generate")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("card-management"),
                "message", containsString("greater than or equal to 1"));
    }

    @Test
    void generationRequiresBins() {
        given()
            .spec(GATEWAY)
            .body(Map.of("count", 1, "bins", List.of()))

        .when()
            .post("/api/cards/generate")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("card-management"),
                "message", containsString("must not be empty"));
    }

    @Test
    void bulkUpdateRequiresStatus() {
        given()
            .spec(GATEWAY)
            .body(Map.of("pans", List.of(createCard())))

        .when()
            .post("/api/cards/bulk-update")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("card-management"),
                "message", containsString("must not be null"));
    }

    @ParameterizedTest
    @CsvSource({"reserve,0", "reserve,-1", "rollback,0", "rollback,-1"})
    void moneyOperationsRequirePositiveAmount(String operation, int amount) {
        String pan = createCard();

        given()
            .spec(GATEWAY)
            .body(Map.of("pan", pan, "rrn", digits(12), "amount", amount))

        .when()
            .post("/api/cards/{pan}/" + operation, pan)

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("card-management"),
                "message", containsString("greater than 0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "rollback"})
    void moneyOperationsRequireExistingCard(String operation) {
        String pan = "999999" + digits(10);

        given()
            .spec(GATEWAY)
            .body(Map.of("pan", pan, "rrn", digits(12), "amount", 1))

        .when()
            .post("/api/cards/{pan}/" + operation, pan)

        .then()
            .statusCode(404)
            .body("serviceName", equalTo("card-management"),
                "message", not(emptyString()));
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,-1", "offset,-1", "bin,123", "issuerId,lowercase"})
    void cardsRejectInvalidQuery(String field, String value) {
        given()
            .spec(GATEWAY)
            .queryParam(field, value)

        .when()
            .get("/api/cards")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("card-management"),
                "message", containsString(field));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 501})
    void recentRejectsInvalidLimit(int limit) {
        given()
            .spec(GATEWAY)
            .queryParam("limit", limit)

        .when()
            .get("/api/dashboard/recent")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("transaction-logger"),
                "message", containsString("limit"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500})
    void recentAcceptsLimitBoundaries(int limit) {
        given()
            .spec(GATEWAY)
            .queryParam("limit", limit)

        .when()
            .get("/api/dashboard/recent")

        .then()
            .statusCode(200)
            .body("size()", lessThanOrEqualTo(limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void chartsAcceptBothGranularities(String granularity) {
        given()
            .spec(GATEWAY)
            .queryParam("granularity", granularity)

        .when()
            .get("/api/dashboard/charts")

        .then()
            .statusCode(200)
            .body("$", instanceOf(List.class));
    }

    @Test
    void chartsRejectUnknownGranularity() {
        given()
            .spec(GATEWAY)
            .queryParam("granularity", "week")

        .when()
            .get("/api/dashboard/charts")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("transaction-logger"),
                "message", containsString("granularity"));
    }

    @Test
    void exportRejectsInvertedDates() {
        given()
            .spec(GATEWAY)
            .queryParams("dateFrom", "2026-02-02", "dateTo", "2026-02-01")

        .when()
            .get("/api/transactions/export")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Validation error"));
    }

    @ParameterizedTest
    @CsvSource({"terminal,normal", "merchant,grocery"})
    void simulatorsRejectZeroRate(String simulator, String scenario) {
        given()
            .spec(GATEWAY)
            .body(Map.of("count", 1, "scenario", scenario, "tps", 0))

        .when()
            .post("/api/simulator/" + simulator + "/run")

        .then()
            .statusCode(400)
            .body("message", containsString("tps"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rrn", "pan", "amount"})
    void internalRollbackRequiresEachField(String field) {
        var request = new java.util.LinkedHashMap<String, Object>(
                Map.of("rrn", digits(12), "pan", "400000" + digits(10), "amount", 1));
        request.remove(field);

        given()
            .spec(AUTH)
            .body(request)

        .when()
            .post("/api/internal/rollback")

        .then()
            .statusCode(400)
            .body("error", equalTo("Bad Request"),
                "path", equalTo("/api/internal/rollback"));
    }
    @ParameterizedTest
    @CsvSource({"POST,start", "GET,status"})
    void recordsMissingDocumentedTerminalPaths(String method, String suffix) {
        String path = "/api/simulator/terminal/" + suffix;

        given()
            .spec(Api.service("terminal", 8085))
            .body(Map.of("tps", 1, "transactionType", "NORMAL"))

        .when()
            .request(method, path)

        .then()
            .statusCode(500)
            .body("serviceName", equalTo("terminal-simulator"),
                "error", equalTo("Unexpected internal server error"),
                "message", containsString("No static resource " + path.substring(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bin", "cardholderName", "currencyCode"})
    void cardCreationRequiresEachField(String field) {
        var body = cardBody();
        body.remove(field);

        given()
            .spec(GATEWAY)
            .body(body)

        .when()
            .post("/api/cards")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("card-management"),
                "message", not(emptyString()));
    }

    @Test
    void chartsRejectInvertedRange() {
        given()
            .spec(GATEWAY)
            .queryParams("from", "2026-02-02T00:00:00Z", "to", "2026-02-01T00:00:00Z")

        .when()
            .get("/api/dashboard/charts")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Validation error"));
    }

}
