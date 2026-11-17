package com.vmticket.ticket.controller;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmticket.common.paging.PageResponse;
import com.vmticket.security.AuthSupport;
import com.vmticket.ticket.dto.RejectionResponse;
import com.vmticket.ticket.service.RejectionService;

@RestController
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/tickets/{ticketId}/rejections")
public class RejectionController {

    private final RejectionService rejectionService;

    public RejectionController(RejectionService rejectionService) {
        this.rejectionService = rejectionService;
    }

    @GetMapping
    public PageResponse<RejectionResponse> list(
            @PathVariable UUID ticketId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return rejectionService.list(
                ticketId, pageable, AuthSupport.currentUsername(), AuthSupport.currentRoles());
    }
}
