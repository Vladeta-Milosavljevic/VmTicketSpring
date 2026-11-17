package com.vmticket.ticket.repository;

import com.vmticket.ticket.entity.Rejection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface RejectionRepository extends JpaRepository<Rejection, UUID> {

    @Query(
            value = """
                    SELECT r FROM Rejection r
                    JOIN FETCH r.manager
                    LEFT JOIN FETCH r.agent
                    WHERE r.ticket.id = :ticketId
                    """,
            countQuery = "SELECT COUNT(r) FROM Rejection r WHERE r.ticket.id = :ticketId")
    Page<Rejection> findByTicket_Id(@Param("ticketId") UUID ticketId, Pageable pageable);
}
