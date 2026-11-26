package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class LoggerContractTest {
    @Test
    void conflictingReplayIdentifiesLogger() {
        var entry = logEntry();

        given()
            .spec(LOGGER)
            .body(entry)

        .when()
            .post(LOG)

        .then()
            .statusCode(201)
            .body("id", equalTo(entry.get("id")), "status", equalTo("stored"));
        entry.put("amount", 101);

        given()
            .spec(LOGGER)
            .body(entry)

        .when()
            .post(LOG)

        .then()
            .statusCode(409)
            .body("error", equalTo("Transaction conflict"),
                "serviceName", equalTo("transaction-logger"), "message", containsString(entry.get("id").toString()));
    }
    @Test
    void sameIdDeduplicatesButDifferentBusinessDataConflicts() {
        var entry = logEntry();

        given()
            .spec(LOGGER)
            .body(entry)

        .when()
            .post(LOG)

        .then()
            .statusCode(201)
            .body("id", equalTo(entry.get("id")));

        entry.put("createdAt", "2020-01-01T00:00:00Z");

        given()
            .spec(LOGGER)
            .body(entry)

        .when()
            .post(LOG)

        .then()
            .statusCode(200)
            .body("id", equalTo(entry.get("id")), "stan", equalTo(entry.get("stan")));

        entry.put("stan", "000000".equals(entry.get("stan")) ? "000001" : "000000");

        given()
            .spec(LOGGER)
            .body(entry)

        .when()
            .post(LOG)

        .then()
            .statusCode(409)
            .body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Transaction conflict"), "message", containsString(entry.get("id").toString()));

        given()
            .spec(GATEWAY)
            .queryParam("pan", entry.get("pan"))

        .when()
            .get(SEARCH)

        .then()
            .statusCode(200)
            .body("total", equalTo(1),
                "transactions.findAll { it.id == '" + entry.get("id") + "' }", hasSize(1));
    }

    static Stream<Arguments> invalidFields() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String required : ("id mti stan pan processingCode amount currencyCode terminalId merchantId "
                + "mcc acquirerId status transmissionDateTime createdAt").split(" ")) {
            cases.add(Arguments.of(required, null));
        }
        Map.of("mti", 4, "rrn", 12, "terminalId", 8, "terminalType", 10, "merchantId", 15,
                "acquirerId", 10, "issuerId", 10, "declineReason", 100, "authCode", 6)
                .forEach((field, max) -> cases.add(Arguments.of(field, "A".repeat(max + 1))));
        Map.of("stan", 6, "pan", 16, "processingCode", 6, "currencyCode", 3, "mcc", 4)
                .forEach((field, length) -> {
                    cases.add(Arguments.of(field, "0".repeat(length - 1)));
                    cases.add(Arguments.of(field, "0".repeat(length + 1)));
                    cases.add(Arguments.of(field, "X".repeat(length)));
                });
        cases.add(Arguments.of("amount", 0));
        cases.add(Arguments.of("amount", -1));
        cases.add(Arguments.of("acquiringFee", -1));
        cases.add(Arguments.of("processingTimeMs", -1));
        return cases.build();
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidFields")
    void reportsInvalidField(String field, Object value) {
        var entry = logEntry();
        entry.put(field, value);

        given()
            .spec(LOGGER)
            .body(entry)

        .when()
            .post(LOG)

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Validation error"), "message", containsString(field + ":"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "id", "transmissionDateTime", "createdAt"})
    void rejectsMalformedTypedValues(String field) {
        var entry = logEntry();
        entry.put(field, "invalid");

        given()
            .spec(LOGGER)
            .body(entry)

        .when()
            .post(LOG)

        .then()
            .statusCode(400)
            .body("error", equalTo("Invalid request body"),
                "serviceName", equalTo("transaction-logger"));
    }
}
