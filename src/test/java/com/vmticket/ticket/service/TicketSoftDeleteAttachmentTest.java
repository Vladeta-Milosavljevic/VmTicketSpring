package com.vmticket.ticket.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmticket.config.AppStorageProperties;
import com.vmticket.ticket.dto.CreateTicketRequest;
import com.vmticket.ticket.dto.TicketResponse;
import com.vmticket.ticket.entity.Attachment;
import com.vmticket.ticket.entity.TicketUrgency;
import com.vmticket.ticket.repository.AttachmentRepository;
import com.vmticket.user.entity.Role;

@SpringBootTest
@ActiveProfiles("test")
class TicketSoftDeleteAttachmentTest {

    private static final UUID HARDWARE_CATEGORY = UUID.fromString("a1000000-0000-4000-8000-000000000001");

    @Autowired
    private TicketService ticketService;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private AppStorageProperties storageProperties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void rolledBackSoftDeleteLeavesAttachmentRowsAndFiles() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "notes.txt", "text/plain", "keep-me".getBytes());
        TicketResponse created = ticketService.create(
                new CreateTicketRequest(
                        "Soft-delete rollback",
                        "body",
                        TicketUrgency.LOW,
                        "agent.one",
                        "manager.one",
                        HARDWARE_CATEGORY, null),
                "manager.one",
                Set.of(Role.MANAGER),
                List.of(file));

        List<Attachment> stored = attachmentRepository.findByTicket_IdAndDeletedAtIsNull(created.id());
        assertEquals(1, stored.size());
        Path storedFile = Path.of(storageProperties.getStorage().getLocation())
                .toAbsolutePath()
                .normalize()
                .resolve(stored.get(0).getStorageKey());
        assertTrue(Files.exists(storedFile));

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            ticketService.softDelete(created.id());
            status.setRollbackOnly();
        });

        List<Attachment> afterRollback = attachmentRepository.findByTicket_IdAndDeletedAtIsNull(created.id());
        assertEquals(1, afterRollback.size());
        assertNull(afterRollback.get(0).getDeletedAt());
        assertTrue(Files.exists(storedFile));
    }
}
