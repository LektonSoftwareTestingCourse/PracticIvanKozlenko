package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InMemoryCircuitBreakerTest {
    private static final Instant START = Instant.parse("2026-10-23T10:00:00Z");
    @Mock private Clock clock;
    private InMemoryCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(START);
        breaker = new InMemoryCircuitBreaker(Duration.ofSeconds(10), 2, clock);
    }

    @Test
    void shouldAllowRequestBelowFailureThreshold() {
        breaker.recordFailure("switch");

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isTrue();
    }

    @Test
    void shouldOpenAtFailureThreshold() {
        openCircuit();

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isFalse();
    }

    @Test
    void shouldKeepOtherServicesAvailableWhenOneCircuitOpens() {
        openCircuit();

        var allowed = breaker.allowRequest("cards");

        assertThat(allowed).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"9999,false", "10000,true", "10001,true"})
    void shouldAllowProbeAtOpenDurationBoundary(long elapsedMillis, boolean expected) {
        openCircuit();
        when(clock.instant()).thenReturn(START.plusMillis(elapsedMillis));

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    void shouldAllowOnlyOneConcurrentProbe() {
        startProbe();

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isFalse();
    }

    @Test
    void shouldCloseCircuitAndResetFailuresAfterSuccessfulProbe() {
        startProbe();
        breaker.recordSuccess("switch");
        breaker.recordFailure("switch");

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isTrue();
    }

    @Test
    void shouldReopenCircuitAfterFailedProbe() {
        startProbe();
        breaker.recordFailure("switch");

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isFalse();
    }

    @Test
    void shouldReleaseCancelledProbeSlot() {
        startProbe();
        breaker.releaseRequest("switch");

        var allowed = breaker.allowRequest("switch");

        assertThat(allowed).isTrue();
    }

    private void openCircuit() {
        breaker.recordFailure("switch");
        breaker.recordFailure("switch");
    }

    private void startProbe() {
        openCircuit();
        when(clock.instant()).thenReturn(START.plusSeconds(10));
        breaker.allowRequest("switch");
    }
}
