package com.vmticket.ticket.dto;

import com.vmticket.user.entity.User;
import com.vmticket.ticket.entity.Rejection;

import java.time.Instant;
import java.util.UUID;

public record RejectionResponse(
        UUID id,
        UUID ticketId,
        String agent,
        String manager,
        String reason,
        Instant createdAt
) {
    public static RejectionResponse from(Rejection rejection) {
        return new RejectionResponse(
                rejection.getId(),
                rejection.getTicket().getId(),
                username(rejection.getAgent()),
                rejection.getManager().getUsername(),
                rejection.getReason(),
                rejection.getCreatedAt()
        );
    }

    private static String username(User user) {
        return user == null ? null : user.getUsername();
    }
}
