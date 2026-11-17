package com.vmticket.ticket.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class TicketStatusTransitions {

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = new EnumMap<>(TicketStatus.class);

    static {
        ALLOWED.put(TicketStatus.OPEN, EnumSet.of(
                TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED));
        ALLOWED.put(TicketStatus.IN_PROGRESS, EnumSet.of(
                TicketStatus.PENDING_REVIEW, TicketStatus.OPEN, TicketStatus.CANCELLED));
        ALLOWED.put(TicketStatus.PENDING_REVIEW, EnumSet.of(
                TicketStatus.COMPLETED, TicketStatus.REJECTED));
        ALLOWED.put(TicketStatus.COMPLETED, EnumSet.noneOf(TicketStatus.class));
        ALLOWED.put(TicketStatus.REJECTED, EnumSet.of(
                TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED));
        ALLOWED.put(TicketStatus.CANCELLED, EnumSet.noneOf(TicketStatus.class));
    }

    private TicketStatusTransitions() {
    }

    public static boolean canTransition(TicketStatus from, TicketStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(TicketStatus.class)).contains(to);
    }
}
