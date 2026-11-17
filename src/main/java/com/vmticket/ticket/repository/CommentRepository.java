package com.vmticket.ticket.repository;

import com.vmticket.ticket.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    @Query(
            value = """
                    SELECT c FROM Comment c
                    JOIN FETCH c.author
                    JOIN FETCH c.ticket
                    WHERE c.ticket.id = :ticketId
                    """,
            countQuery = "SELECT COUNT(c) FROM Comment c WHERE c.ticket.id = :ticketId")
    Page<Comment> findByTicket_Id(@Param("ticketId") UUID ticketId, Pageable pageable);

    Optional<Comment> findByIdAndTicket_Id(UUID id, UUID ticketId);
}
