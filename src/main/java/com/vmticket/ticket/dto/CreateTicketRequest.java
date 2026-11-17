package com.vmticket.ticket.dto;

import java.time.Instant;
import java.util.UUID;

import com.vmticket.ticket.entity.TicketUrgency;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTicketRequest(
                @NotBlank @Size(max = 200) String title,
                @NotBlank @Size(max = 10000) String description,
                TicketUrgency urgency,
                @Size(max = 120) String agent,
                @NotBlank @Size(max = 120) String manager,
                @NotNull UUID categoryId,
                Instant deadline) {
}
