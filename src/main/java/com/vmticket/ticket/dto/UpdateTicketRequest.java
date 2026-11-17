package com.vmticket.ticket.dto;

import com.vmticket.ticket.entity.TicketStatus;
import com.vmticket.ticket.entity.TicketUrgency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record UpdateTicketRequest(
        @Size(max = 200) String title,
        @Size(max = 10000) String description,
        TicketStatus status,
        TicketUrgency urgency,
        @Size(max = 120) String agent,
        @Size(max = 120) String manager,
        @Size(max = 10000) String rejectionReason,
        UUID categoryId,
        Instant deadline,
        @NotNull Long version
) {
    public boolean hasAnyUpdate() {
        return title != null
                || description != null
                || status != null
                || urgency != null
                || agent != null
                || manager != null
                || rejectionReason != null
                || categoryId != null
                || deadline != null;
    }
}
