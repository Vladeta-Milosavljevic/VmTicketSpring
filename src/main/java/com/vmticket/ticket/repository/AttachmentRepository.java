package com.vmticket.ticket.repository;

import com.vmticket.ticket.entity.Attachment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByComment_IdIn(Collection<UUID> commentIds);

    Optional<Attachment> findByIdAndComment_IdAndTicket_Id(UUID id, UUID commentId, UUID ticketId);

    Page<Attachment> findByTicket_IdAndCommentIsNullAndDeletedAtIsNull(UUID ticketId, Pageable pageable);

    Optional<Attachment> findByIdAndTicket_IdAndCommentIsNullAndDeletedAtIsNull(UUID id, UUID ticketId);

    List<Attachment> findByTicket_IdAndDeletedAtIsNull(UUID ticketId);
}
