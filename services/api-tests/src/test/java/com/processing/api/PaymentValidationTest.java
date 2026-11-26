package com.processing.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static com.processing.api.Api.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class PaymentValidationTest {
    static Stream<Arguments> violations() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String field : ("mti stan pan processingCode currencyCode transmissionDateTime terminalId merchantId "
                + "mcc acquirerId amount").split(" ")) {
            cases.add(Arguments.of(field, null, "is required"));
        }
        cases.add(Arguments.of("mti", "0110", "must be '0100'"));
        for (String pan : new String[]{"0".repeat(15), "0".repeat(17), "A".repeat(16)}) {
            cases.add(Arguments.of("pan", pan, "exactly 16 digits"));
        }
        for (String mcc : new String[]{"123", "12345", "ABCD"}) {
            cases.add(Arguments.of("mcc", mcc, "exactly 4 digits"));
        }
        cases.add(Arguments.of("amount", 0, "must be > 0"));
        cases.add(Arguments.of("amount", -1, "must be > 0"));
        cases.add(Arguments.of("currencyCode", "64", "exactly 3 characters"));
        cases.add(Arguments.of("currencyCode", "6430", "exactly 3 characters"));
        return cases.build();
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("violations")
    void gatewayNamesFieldAndRule(String field, Object value, String rule) {
        var request = payment("400000" + digits(10));
        request.put(field, value);

        given()
            .spec(GATEWAY)
            .body(request)

        .when()
            .post("/api/transactions")

        .then()
            .statusCode(400)
            .body("serviceName", equalTo("gateway"), "error", equalTo("VALIDATION_ERROR"),
                "message", allOf(containsString("'" + field + "'"), containsString(rule)));
    }
}
