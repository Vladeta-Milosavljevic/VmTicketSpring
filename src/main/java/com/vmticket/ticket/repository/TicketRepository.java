package com.vmticket.ticket.repository;

import com.vmticket.ticket.entity.Ticket;
import com.vmticket.ticket.entity.TicketStatus;
import com.vmticket.ticket.entity.TicketUrgency;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    @Query("""
            SELECT t FROM Ticket t
            JOIN FETCH t.requester
            JOIN FETCH t.category
            LEFT JOIN FETCH t.agent
            LEFT JOIN FETCH t.manager
            WHERE t.deletedAt IS NULL
              AND t.id = :id
            """)
    Optional<Ticket> findActiveById(@Param("id") UUID id);

    @Query(value = """
            SELECT t FROM Ticket t
            JOIN FETCH t.requester
            JOIN FETCH t.category
            LEFT JOIN FETCH t.agent agentUser
            LEFT JOIN FETCH t.manager managerUser
            WHERE t.deletedAt IS NULL
              AND (:status IS NULL OR t.status = :status)
              AND (:urgency IS NULL OR t.urgency = :urgency)
              AND (:agent IS NULL OR LOWER(agentUser.username) = LOWER(:agent))
              AND (:manager IS NULL OR LOWER(managerUser.username) = LOWER(:manager))
              AND (:q IS NULL
                OR LOWER(t.title) LIKE LOWER(:q) ESCAPE '\\'
                OR LOWER(t.description) LIKE LOWER(:q) ESCAPE '\\'
                OR LOWER(t.category.name) LIKE LOWER(:q) ESCAPE '\\')
              AND (:isAdmin = TRUE
                OR LOWER(t.requester.username) = :caller
                OR (:isAgent = TRUE AND LOWER(agentUser.username) = :caller)
                OR (:isManager = TRUE AND LOWER(managerUser.username) = :caller))
            """, countQuery = """
            SELECT COUNT(t) FROM Ticket t
            JOIN t.requester
            JOIN t.category
            LEFT JOIN t.agent agentUser
            LEFT JOIN t.manager managerUser
            WHERE t.deletedAt IS NULL
              AND (:status IS NULL OR t.status = :status)
              AND (:urgency IS NULL OR t.urgency = :urgency)
              AND (:agent IS NULL OR LOWER(agentUser.username) = LOWER(:agent))
              AND (:manager IS NULL OR LOWER(managerUser.username) = LOWER(:manager))
              AND (:q IS NULL
                OR LOWER(t.title) LIKE LOWER(:q) ESCAPE '\\'
                OR LOWER(t.description) LIKE LOWER(:q) ESCAPE '\\'
                OR LOWER(t.category.name) LIKE LOWER(:q) ESCAPE '\\')
              AND (:isAdmin = TRUE
                OR LOWER(t.requester.username) = :caller
                OR (:isAgent = TRUE AND LOWER(agentUser.username) = :caller)
                OR (:isManager = TRUE AND LOWER(managerUser.username) = :caller))
            """)
    Page<Ticket> search(
            @Param("status") TicketStatus status,
            @Param("urgency") TicketUrgency urgency,
            @Param("agent") String agent,
            @Param("manager") String manager,
            @Param("q") String q,
            @Param("caller") String caller,
            @Param("isAdmin") boolean isAdmin,
            @Param("isAgent") boolean isAgent,
            @Param("isManager") boolean isManager,
            Pageable pageable);
}
