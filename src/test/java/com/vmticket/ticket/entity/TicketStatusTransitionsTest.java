package com.vmticket.ticket.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketStatusTransitionsTest {

    @ParameterizedTest
    @CsvSource({
            "OPEN, IN_PROGRESS",
            "OPEN, CANCELLED",
            "IN_PROGRESS, PENDING_REVIEW",
            "IN_PROGRESS, OPEN",
            "IN_PROGRESS, CANCELLED",
            "PENDING_REVIEW, COMPLETED",
            "PENDING_REVIEW, REJECTED",
            "REJECTED, IN_PROGRESS",
            "REJECTED, CANCELLED",
            "OPEN, OPEN"
    })
    void allowsValidTransitions(TicketStatus from, TicketStatus to) {
        assertTrue(TicketStatusTransitions.canTransition(from, to));
    }

    @ParameterizedTest
    @CsvSource({
            "OPEN, COMPLETED",
            "OPEN, PENDING_REVIEW",
            "OPEN, REJECTED",
            "IN_PROGRESS, REJECTED",
            "PENDING_REVIEW, IN_PROGRESS",
            "PENDING_REVIEW, CANCELLED",
            "REJECTED, PENDING_REVIEW",
            "REJECTED, COMPLETED",
            "COMPLETED, OPEN",
            "CANCELLED, OPEN"
    })
    void rejectsInvalidTransitions(TicketStatus from, TicketStatus to) {
        assertFalse(TicketStatusTransitions.canTransition(from, to));
    }

    @Test
    void rejectsNull() {
        assertFalse(TicketStatusTransitions.canTransition(null, TicketStatus.OPEN));
        assertFalse(TicketStatusTransitions.canTransition(TicketStatus.OPEN, null));
    }
}
