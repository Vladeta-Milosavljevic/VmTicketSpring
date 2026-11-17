package com.vmticket.ticket.dto;

import com.vmticket.user.entity.User;
import com.vmticket.ticket.entity.Ticket;
import com.vmticket.ticket.entity.TicketStatus;
import com.vmticket.ticket.entity.TicketUrgency;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        String title,
        String description,
        TicketStatus status,
        TicketUrgency urgency,
        String agent,
        String manager,
        String requester,
        UUID categoryId,
        Instant deadline,
        Instant completedByAgentAt,
        Instant completedByManagerAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getStatus(),
                ticket.getUrgency(),
                username(ticket.getAgent()),
                username(ticket.getManager()),
                username(ticket.getRequester()),
                ticket.getCategory().getId(),
                ticket.getDeadline(),
                ticket.getCompletedByAgentAt(),
                ticket.getCompletedByManagerAt(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getVersion()
        );
    }

    private static String username(User user) {
        return user == null ? null : user.getUsername();
    }
}
