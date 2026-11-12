package com.processing.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {
    private final AtomicLong nanos = new AtomicLong();

    @Test
    void shouldRejectRequestWhenBucketIsExhausted() {
        var limiter = limiter();
        limiter.allowRequest("client-a");

        var allowed = limiter.allowRequest("client-a");

        assertThat(allowed).isFalse();
    }

    @Test
    void shouldKeepIndependentBucketsForDifferentClients() {
        var limiter = limiter();
        limiter.allowRequest("client-a");

        var allowed = limiter.allowRequest("client-b");

        assertThat(allowed).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"999999999,false", "1000000000,true", "1000000001,true"})
    void shouldRefillOnlyAfterOneWholeTokenIsAvailable(long elapsedNanos, boolean expected) {
        var limiter = limiter();
        limiter.allowRequest("client-a");
        nanos.set(elapsedNanos);

        var allowed = limiter.allowRequest("client-a");

        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    void shouldCapRefillAtBucketCapacity() {
        var limiter = limiter();
        limiter.allowRequest("client-a");
        nanos.set(Duration.ofSeconds(10).toNanos());
        limiter.allowRequest("client-a");

        var allowed = limiter.allowRequest("client-a");

        assertThat(allowed).isFalse();
    }

    private InMemoryRateLimiter limiter() {
        return new InMemoryRateLimiter(1, 1, Duration.ofMinutes(10), 100, nanos::get);
    }
}
