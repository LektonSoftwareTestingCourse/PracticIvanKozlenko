package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class CardTest {
    private static final String PAN = "4000001234560001";
    private static final String RRN = "012345678901";

    @ParameterizedTest
    @ValueSource(strings = {"9999", "10000"})
    void shouldCreateReservationWhenAmountDoesNotExceedBalance(String amount) {
        var card = card(CardStatus.ACTIVE);

        var reservation = card.startReservation(new BigDecimal(amount), RRN);

        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.rrn()).isEqualTo(RRN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(amount);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(card.availableBalance()).isEqualByComparingTo("10000");
    }

    @Test
    void shouldRejectReservationAboveBalance() {
        var card = card(CardStatus.ACTIVE);

        assertThatThrownBy(() -> card.startReservation(new BigDecimal("10001"), RRN))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectReservationForEveryInactiveStatus(CardStatus status) {
        var card = card(status);

        assertThatThrownBy(() -> card.startReservation(new BigDecimal("100"), RRN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(status.name());
    }

    @Test
    void shouldSubtractReservedAmountWithoutChangingOriginalCard() {
        var card = card(CardStatus.ACTIVE);
        var reservation = new Reservation(PAN, new BigDecimal("2500"), RRN);

        var result = card.withReservation(reservation);

        assertThat(result.availableBalance()).isEqualByComparingTo("7500");
        assertThat(result.id()).isEqualTo(card.id());
        assertThat(card.availableBalance()).isEqualByComparingTo("10000");
    }

    @Test
    void shouldRejectReservationBelongingToAnotherCard() {
        var card = card(CardStatus.ACTIVE);
        var reservation = new Reservation("4000011234560001", new BigDecimal("100"), RRN);

        assertThatThrownBy(() -> card.withReservation(reservation)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRestoreBalanceByRollbackAmount() {
        var card = card(CardStatus.ACTIVE);
        var rollback = new ReservationRollback(UUID.randomUUID(), UUID.randomUUID(), PAN,
                new BigDecimal("2500"), RRN, Instant.EPOCH);

        var result = card.withRollback(rollback);

        assertThat(result.availableBalance()).isEqualByComparingTo("12500");
        assertThat(card.availableBalance()).isEqualByComparingTo("10000");
    }

    @ParameterizedTest
    @CsvSource({"-1,100", "100,-1", "101,100"})
    void shouldRejectInvalidLimits(String daily, String monthly) {
        var card = card(CardStatus.ACTIVE);

        assertThatThrownBy(() -> card.withData(CardStatus.ACTIVE, new BigDecimal(daily),
                new BigDecimal(monthly), card.availableBalance())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "99,100", "100,100"})
    void shouldAcceptNonnegativeLimitsWhenDailyDoesNotExceedMonthly(String daily, String monthly) {
        var card = card(CardStatus.ACTIVE);

        var result = card.withData(CardStatus.BLOCKED, new BigDecimal(daily), new BigDecimal(monthly),
                card.availableBalance());

        assertThat(result.dailyLimit()).isEqualByComparingTo(daily);
        assertThat(result.monthlyLimit()).isEqualByComparingTo(monthly);
        assertThat(result.status()).isEqualTo(CardStatus.BLOCKED);
    }

    @Test
    void shouldSoftDeleteCardWithoutChangingItsIdentity() {
        var card = card(CardStatus.ACTIVE);

        var result = card.deleted();

        assertThat(result.status()).isEqualTo(CardStatus.DELETED);
        assertThat(result.id()).isEqualTo(card.id());
        assertThat(result.pan()).isEqualTo(PAN);
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
    }

    private Card card(CardStatus status) {
        return new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN KOZLENKO",
                YearMonth.of(2027, 12), status, "643", new BigDecimal("100000"), new BigDecimal("500000"),
                new BigDecimal("10000"), "ISS001", Instant.EPOCH);
    }
}
