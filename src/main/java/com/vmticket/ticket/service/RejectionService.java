package com.vmticket.ticket.service;

import com.vmticket.common.error.TicketNotFoundException;
import com.vmticket.common.paging.PageResponse;
import com.vmticket.ticket.dto.RejectionResponse;
import com.vmticket.ticket.entity.Rejection;
import com.vmticket.ticket.entity.Ticket;
import com.vmticket.ticket.repository.RejectionRepository;
import com.vmticket.ticket.repository.TicketRepository;
import com.vmticket.user.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class RejectionService {

    private final RejectionRepository rejectionRepository;
    private final TicketRepository ticketRepository;

    public RejectionService(RejectionRepository rejectionRepository, TicketRepository ticketRepository) {
        this.rejectionRepository = rejectionRepository;
        this.ticketRepository = ticketRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<RejectionResponse> list(UUID ticketId, Pageable pageable, String username, Set<Role> roles) {
        Ticket ticket = ticketRepository.findActiveById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found: " + ticketId));
        TicketAccess.requireCanRead(ticket, username, roles);
        Pageable oldestFirst = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<RejectionResponse> page = rejectionRepository.findByTicket_Id(ticketId, oldestFirst)
                .map(RejectionResponse::from);
        return PageResponse.from(page);
    }
}
