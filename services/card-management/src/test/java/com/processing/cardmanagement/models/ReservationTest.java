package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ReservationTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PAN = "4000001234560001";
    private static final String RRN = "012345678901";
    private static final BigDecimal AMOUNT = new BigDecimal("2500");

    @Test
    void shouldLinkRollbackToOriginalReservation() {
        var reservation = reservation(ReservationStatus.RESERVED);

        var result = reservation.startRollback(AMOUNT);

        assertThat(result.reservationId()).isEqualTo(ID);
        assertThat(result.pan()).isEqualTo(PAN);
        assertThat(result.rrn()).isEqualTo(RRN);
        assertThat(result.rollbackAmount()).isEqualByComparingTo(AMOUNT);
    }

    @Test
    void shouldRejectSecondRollback() {
        var reservation = reservation(ReservationStatus.ROLLED_BACK);

        assertThatThrownBy(() -> reservation.startRollback(AMOUNT))
                .isInstanceOf(RollbackAlreadySatisfiedException.class);
    }

    @Test
    void shouldMarkReservationRolledBackWithoutChangingOriginal() {
        var reservation = reservation(ReservationStatus.RESERVED);
        var rollback = new ReservationRollback(UUID.randomUUID(), ID, PAN, AMOUNT, RRN, Instant.EPOCH);

        var result = reservation.rolledBack(rollback);

        assertThat(result.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(result.id()).isEqualTo(ID);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void shouldRejectRollbackForDifferentReservation() {
        var reservation = reservation(ReservationStatus.RESERVED);
        var rollback = new ReservationRollback(UUID.randomUUID(), UUID.randomUUID(), PAN, AMOUNT, RRN, Instant.EPOCH);

        assertThatThrownBy(() -> reservation.rolledBack(rollback)).isInstanceOf(IllegalArgumentException.class);
    }

    private Reservation reservation(ReservationStatus status) {
        return new Reservation(ID, PAN, AMOUNT, RRN, status, Instant.EPOCH, Instant.EPOCH);
    }
}
