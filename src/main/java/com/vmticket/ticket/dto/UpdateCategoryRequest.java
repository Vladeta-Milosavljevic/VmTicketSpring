package com.vmticket.ticket.dto;

import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(
        @Size(max = 120) String name,
        @Size(max = 10000) String description,
        @Size(max = 140) String slug,
        Boolean isArchived
) {
    public boolean hasAnyUpdate() {
        return name != null
                || description != null
                || slug != null
                || isArchived != null;
    }
}
