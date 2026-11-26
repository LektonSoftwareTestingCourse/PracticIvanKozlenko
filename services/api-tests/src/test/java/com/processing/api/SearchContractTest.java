package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class SearchContractTest {
    @Test
    void allFiltersSelectOnlyMatchingRows() {
        var entry = logEntry();
        entry.put("status", "DECLINED");
        entry.put("declineReason", "CARD_BLOCKED");
        entry.remove("authCode");

        given()
            .spec(LOGGER)
            .body(entry)

        .when()
            .post(LOG)

        .then()
            .statusCode(201)
            .body("id", equalTo(entry.get("id")));
        String day = entry.get("createdAt").toString().substring(0, 10);
        Map<String, Object> query = new LinkedHashMap<>();
        for (var field : List.of("pan", "merchantId", "issuerId", "mcc", "status", "declineReason")) {
            query.put(field, entry.get(field));
        }
        query.put("dateFrom", day);
        query.put("dateTo", day);
        query.put("limit", 1);
        query.put("offset", 0);

        given()
            .spec(GATEWAY)
            .queryParams(query)

        .when()
            .get(SEARCH)

        .then()
            .statusCode(200)
            .body("total", equalTo(1), "transactions.id", contains(entry.get("id")));

        Map<String, Object> alternatives = Map.of("pan", "999999" + digits(10), "merchantId", "OTHER",
                "issuerId", "OTHER", "mcc", "0000", "status", "APPROVED", "declineReason", "EXPIRED");
        var fields = List.copyOf(alternatives.keySet());
        for (int i = 0; i < fields.size(); i++) {
            var mismatch = new LinkedHashMap<>(query);
            mismatch.put(fields.get(i), alternatives.get(fields.get(i)));
            assertEmpty(mismatch);
            for (int j = i + 1; j < fields.size(); j++) {
                var pair = new LinkedHashMap<>(mismatch);
                pair.put(fields.get(j), alternatives.get(fields.get(j)));
                assertEmpty(pair);
            }
        }
        var earlier = new LinkedHashMap<>(query);
        earlier.remove("dateFrom");
        earlier.put("dateTo", LocalDate.parse(day).minusDays(1).toString());
        assertEmpty(earlier);
        var later = new LinkedHashMap<>(query);
        later.remove("dateTo");
        later.put("dateFrom", LocalDate.parse(day).plusDays(1).toString());
        assertEmpty(later);

        query.put("offset", 1);
        query.put("limit", 500);

        given()
            .spec(GATEWAY)
            .queryParams(query)

        .when()
            .get(SEARCH)

        .then()
            .statusCode(200)
            .body("total", equalTo(1), "transactions", empty());
    }

    private void assertEmpty(Map<String, Object> query) {
        given()
            .spec(GATEWAY)
            .queryParams(query)

        .when()
            .get(SEARCH)

        .then()
            .statusCode(200)
            .body("transactions", empty(), "total", equalTo(0));
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,501", "offset,-1", "status,UNKNOWN", "dateFrom,bad-date"})
    void rejectsInvalidFilter(String field, String value) {
        given()
            .spec(GATEWAY)
            .queryParam(field, value)

        .when()
            .get(SEARCH)

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("transaction-logger"), "error", not(emptyString()));
    }

    @Test
    void rejectsInvertedDates() {
        var day = LocalDate.now(ZoneOffset.UTC);

        given()
            .spec(GATEWAY)
            .queryParam("dateFrom", day.toString())
            .queryParam("dateTo", day.minusDays(1).toString())

        .when()
            .get(SEARCH)

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    void exportsOnlySelectedMerchant() {
        var entry = logEntry();

        given()
            .spec(LOGGER)
            .body(entry)

        .when()
            .post(LOG)

        .then()
            .statusCode(201)
            .body("id", equalTo(entry.get("id")));
        var other = logEntry();

        given()
            .spec(LOGGER)
            .body(other)

        .when()
            .post(LOG)

        .then()
            .statusCode(201)
            .body("id", equalTo(other.get("id")));

        given()
            .spec(GATEWAY)
            .queryParam("merchantId", entry.get("merchantId"))

        .when()
            .get("/api/transactions/export")

        .then()
            .statusCode(200)
            .header("Content-Type", startsWith("text/csv"))
            .header("Content-Disposition", containsString("transactions.csv"))
            .body(allOf(containsString(entry.get("merchantId").toString()),
                not(containsString(other.get("merchantId").toString()))));
    }
}
