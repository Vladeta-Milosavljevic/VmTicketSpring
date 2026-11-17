package com.vmticket.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
                @NotBlank @Size(max = 120) String name,
                @Size(max = 10000) String description,
                @Size(max = 140) String slug) {
}
