package com.vmticket.ticket.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.vmticket.common.error.AttachmentNotFoundException;
import com.vmticket.common.error.BadRequestException;
import com.vmticket.config.AppStorageProperties;
import com.vmticket.ticket.dto.CreateTicketRequest;
import com.vmticket.ticket.entity.Attachment;
import com.vmticket.ticket.entity.Category;
import com.vmticket.ticket.entity.Comment;
import com.vmticket.ticket.entity.Ticket;
import com.vmticket.ticket.entity.TicketUrgency;
import com.vmticket.ticket.repository.AttachmentRepository;
import com.vmticket.ticket.repository.CategoryRepository;
import com.vmticket.ticket.repository.RejectionRepository;
import com.vmticket.ticket.repository.TicketRepository;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.user.service.UserAccountService;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

        @TempDir
        Path tempDir;

        @Mock
        private AttachmentRepository attachmentRepository;

        @Mock
        private TicketRepository ticketRepository;

        @Mock
        private UserAccountService userAccountService;

        @Mock
        private CategoryRepository categoryRepository;

        @Mock
        private RejectionRepository rejectionRepository;

        private AttachmentService attachmentService;
        private TicketService ticketService;
        private Category category;

        @BeforeEach
        void setUp() {
                AppStorageProperties properties = new AppStorageProperties();
                properties.getStorage().setLocation(tempDir.toString());
                properties.getStorage().setMaxFileBytes(10L * 1024 * 1024);
                attachmentService = new AttachmentService(attachmentRepository, ticketRepository, properties);
                category = new Category("Network", "network", null);
                ticketService = new TicketService(
                                ticketRepository, userAccountService, attachmentService, categoryRepository,
                                rejectionRepository);
                lenient().when(ticketRepository.save(any(Ticket.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                lenient().when(attachmentRepository.save(any(Attachment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                lenient().when(attachmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
                lenient().when(userAccountService.requireByUsername(any()))
                                .thenAnswer(invocation -> new User(invocation.getArgument(0)));
                lenient().when(userAccountService.requireActiveWithRole(any(), any()))
                                .thenAnswer(invocation -> new User(invocation.getArgument(0)));
                lenient().when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        }

        @Test
        void ticketCreateStoresFileWithNullComment() {
                MockMultipartFile file = new MockMultipartFile("files", "notes.txt", "text/plain", "hello".getBytes());

                ticketService.create(newTicketRequest(), "alice", Set.of(Role.MANAGER), List.of(file));

                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<Attachment>> captor = ArgumentCaptor.forClass(List.class);
                verify(attachmentRepository).saveAll(captor.capture());
                Attachment saved = captor.getValue().get(0);
                assertNull(saved.getComment());
                assertEquals("alice", saved.getUploadedBy().getUsername());
                assertEquals("notes.txt", saved.getOriginalFileName());
                assertTrue(Files.exists(tempDir.resolve(saved.getStorageKey())));
        }

        @Test
        void tooManyOrEmptyFilesAreBadRequestAndTicketIsNotSaved() {
                List<MultipartFile> tooMany = List.of(
                                file("a.txt"), file("b.txt"), file("c.txt"), file("d.txt"), file("e.txt"),
                                file("f.txt"));
                assertThrows(BadRequestException.class,
                                () -> ticketService.create(newTicketRequest(), "alice", Set.of(Role.MANAGER), tooMany));

                MockMultipartFile empty = new MockMultipartFile("files", "empty.txt", "text/plain", new byte[0]);
                assertThrows(BadRequestException.class,
                                () -> ticketService.create(newTicketRequest(), "alice", Set.of(Role.MANAGER),
                                                List.of(empty)));

                verify(ticketRepository, never()).save(any());
        }

        @Test
        void downloadOfCommentFileThroughTicketLookupIsNotFound() {
                Ticket ticket = assignedTicket();
                when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));
                Comment comment = new Comment(ticket, new User("alice"), "note");
                Attachment commentFile = new Attachment(
                                ticket, comment, new User("alice"), "notes.txt", "text/plain", 4,
                                UUID.randomUUID().toString());
                when(attachmentRepository.findByIdAndTicket_IdAndCommentIsNullAndDeletedAtIsNull(
                                commentFile.getId(), ticket.getId())).thenReturn(Optional.empty());

                assertThrows(AttachmentNotFoundException.class,
                                () -> attachmentService.downloadTicketFile(
                                                ticket.getId(), commentFile.getId(), "agent1", Set.of(Role.AGENT)));
        }

        @Test
        void uploaderManagerAndAdminCanRemoveTicketFile() throws IOException {
                assertRemoved("uploader", "uploader", Set.of(Role.AGENT));
                assertRemoved("uploader", "mgr1", Set.of(Role.MANAGER));
                assertRemoved("uploader", "root", Set.of(Role.ADMIN));
        }

        @Test
        void demotedManagerCannotRemoveSomeoneElsesFileAndDemotedUploaderCanRemoveOwn() throws IOException {
                Ticket ticket = assignedTicket();
                Attachment someoneElses = storeTicketFile(ticket, "uploader");
                when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));

                assertThrows(AccessDeniedException.class, () -> attachmentService.removeTicketFile(
                                ticket.getId(), someoneElses.getId(), "mgr1", Set.of(Role.AGENT)));
                assertFalse(someoneElses.isDeleted());
                verify(attachmentRepository, never()).save(any());

                Attachment ownFile = storeTicketFile(ticket, "mgr1");
                TransactionSynchronizationManager.initSynchronization();
                try {
                        attachmentService.removeTicketFile(
                                        ticket.getId(), ownFile.getId(), "mgr1", Set.of(Role.AGENT));
                        assertTrue(ownFile.isDeleted());
                        runAfterCommit();
                        assertFalse(Files.exists(tempDir.resolve(ownFile.getStorageKey())));
                } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                }
        }

        @Test
        void anotherAgentCannotRemoveTicketFile() throws IOException {
                Ticket ticket = assignedTicket();
                Attachment attachment = storeTicketFile(ticket, "uploader");
                when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));

                assertThrows(AccessDeniedException.class, () -> attachmentService.removeTicketFile(
                                ticket.getId(), attachment.getId(), "agent1", Set.of(Role.AGENT)));

                assertFalse(attachment.isDeleted());
                assertTrue(Files.exists(tempDir.resolve(attachment.getStorageKey())));
                verify(attachmentRepository, never()).save(any());
        }

        @Test
        void disallowedFileIsBadRequestAndAllowedFileKeepsItsBytes() throws IOException {
                MockMultipartFile disallowed = new MockMultipartFile(
                                "files", "notes.exe", "application/octet-stream", "nope".getBytes());
                assertThrows(BadRequestException.class, () -> attachmentService.prepare(List.of(disallowed)));

                MockMultipartFile mismatched = new MockMultipartFile(
                                "files", "notes.txt", "image/png", "hello".getBytes());
                assertThrows(BadRequestException.class, () -> attachmentService.prepare(List.of(mismatched)));

                MockMultipartFile spoofedPng = new MockMultipartFile(
                                "files", "photo.png", "image/png", "not-a-png".getBytes());
                BadRequestException signature = assertThrows(BadRequestException.class,
                                () -> attachmentService.prepare(List.of(spoofedPng)));
                assertEquals("file type is not allowed", signature.getMessage());
                assertEquals(0, regularFileCount(tempDir));

                MockMultipartFile allowedPng = new MockMultipartFile(
                                "files", "photo.png", "image/png", pngBytes());
                List<AttachmentService.PreparedFile> pngPrepared = attachmentService.prepare(List.of(allowedPng));
                assertEquals("image/png", pngPrepared.get(0).contentType());
                assertTrue(Files.exists(pngPrepared.get(0).tempFile()));
                attachmentService.discard(pngPrepared);

                MockMultipartFile allowed = new MockMultipartFile(
                                "files", "notes.txt", "text/plain; charset=UTF-8", "hello".getBytes());
                List<AttachmentService.PreparedFile> prepared = attachmentService.prepare(List.of(allowed));
                Path temp = prepared.get(0).tempFile();
                assertEquals("text/plain", prepared.get(0).contentType());
                assertEquals("hello", Files.readString(temp));
                attachmentService.discard(prepared);
                assertFalse(Files.exists(temp));
        }

        @Test
        void writtenFilesAreRemovedOnRollbackAndKeptOnCommit() throws IOException {
                MockMultipartFile png = new MockMultipartFile("files", "photo.png", "image/png", pngBytes());
                List<AttachmentService.PreparedFile> prepared = attachmentService.prepare(List.of(png));

                TransactionSynchronizationManager.initSynchronization();
                Path stored;
                try {
                        List<Path> written = attachmentService.write(prepared);
                        stored = written.get(0);
                        assertTrue(Files.exists(stored));
                        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
                                        .forEach(sync -> sync.afterCompletion(
                                                        TransactionSynchronization.STATUS_ROLLED_BACK));
                        assertFalse(Files.exists(stored));
                } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                }

                List<AttachmentService.PreparedFile> committed = attachmentService.prepare(List.of(
                                new MockMultipartFile("files", "photo.png", "image/png", pngBytes())));
                TransactionSynchronizationManager.initSynchronization();
                try {
                        List<Path> written = attachmentService.write(committed);
                        stored = written.get(0);
                        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
                                        .forEach(sync -> sync.afterCompletion(
                                                        TransactionSynchronization.STATUS_COMMITTED));
                        assertTrue(Files.exists(stored));
                } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                }
        }

        @Test
        void ticketSoftDeleteMarksAttachmentsAndDeletesFilesAfterCommit() throws IOException {
                Ticket ticket = assignedTicket();
                when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));
                Attachment ticketFile = writeStoredFile(ticket, null, "uploader");
                Comment comment = new Comment(ticket, new User("alice"), "note");
                Attachment commentFile = writeStoredFile(ticket, comment, "uploader");
                when(attachmentRepository.findByTicket_IdAndDeletedAtIsNull(ticket.getId()))
                                .thenReturn(List.of(ticketFile, commentFile));

                TransactionSynchronizationManager.initSynchronization();
                try {
                        ticketService.softDelete(ticket.getId());
                        assertNotNull(ticketFile.getDeletedAt());
                        assertNotNull(commentFile.getDeletedAt());
                        assertTrue(ticket.isDeleted());
                        assertTrue(Files.exists(tempDir.resolve(ticketFile.getStorageKey())));
                        assertTrue(Files.exists(tempDir.resolve(commentFile.getStorageKey())));
                } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                }
                assertTrue(Files.exists(tempDir.resolve(ticketFile.getStorageKey())));
                assertTrue(Files.exists(tempDir.resolve(commentFile.getStorageKey())));

                TransactionSynchronizationManager.initSynchronization();
                try {
                        ticketService.softDelete(ticket.getId());
                        runAfterCommit();
                        assertFalse(Files.exists(tempDir.resolve(ticketFile.getStorageKey())));
                        assertFalse(Files.exists(tempDir.resolve(commentFile.getStorageKey())));
                } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                }
        }

        @Test
        void fileRemainsUntilAfterCommitAndStaysWhenCommitDoesNotRun() throws IOException {
                Ticket ticket = assignedTicket();
                Attachment attachment = storeTicketFile(ticket, "uploader");
                when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));

                TransactionSynchronizationManager.initSynchronization();
                try {
                        attachmentService.removeTicketFile(
                                        ticket.getId(), attachment.getId(), "uploader", Set.of(Role.AGENT));
                        assertTrue(attachment.isDeleted());
                        assertTrue(Files.exists(tempDir.resolve(attachment.getStorageKey())));
                } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                }
                assertTrue(Files.exists(tempDir.resolve(attachment.getStorageKey())));

                TransactionSynchronizationManager.initSynchronization();
                try {
                        attachmentService.removeTicketFile(
                                        ticket.getId(), attachment.getId(), "uploader", Set.of(Role.AGENT));
                        runAfterCommit();
                        assertFalse(Files.exists(tempDir.resolve(attachment.getStorageKey())));
                } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                }
        }

        private void assertRemoved(String uploader, String caller, Set<Role> roles) throws IOException {
                Ticket ticket = assignedTicket();
                Attachment attachment = storeTicketFile(ticket, uploader);
                when(ticketRepository.findActiveById(ticket.getId())).thenReturn(Optional.of(ticket));

                TransactionSynchronizationManager.initSynchronization();
                try {
                        attachmentService.removeTicketFile(ticket.getId(), attachment.getId(), caller, roles);

                        assertTrue(attachment.isDeleted());
                        assertTrue(Files.exists(tempDir.resolve(attachment.getStorageKey())));
                        runAfterCommit();
                        assertFalse(Files.exists(tempDir.resolve(attachment.getStorageKey())));
                } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                }
        }

        private static void runAfterCommit() {
                List<TransactionSynchronization> synchronizations = new ArrayList<>(
                                TransactionSynchronizationManager.getSynchronizations());
                synchronizations.forEach(TransactionSynchronization::afterCommit);
        }

        private Attachment storeTicketFile(Ticket ticket, String uploader) throws IOException {
                Attachment attachment = writeStoredFile(ticket, null, uploader);
                when(attachmentRepository.findByIdAndTicket_IdAndCommentIsNullAndDeletedAtIsNull(
                                attachment.getId(), ticket.getId())).thenReturn(Optional.of(attachment));
                return attachment;
        }

        private Attachment writeStoredFile(Ticket ticket, Comment comment, String uploader) throws IOException {
                String storageKey = UUID.randomUUID().toString();
                Files.writeString(tempDir.resolve(storageKey), "data");
                return new Attachment(
                                ticket, comment, new User(uploader), "notes.txt", "text/plain", 4, storageKey);
        }

        private Ticket assignedTicket() {
                return new Ticket(
                                "Title",
                                "Desc",
                                TicketUrgency.MEDIUM,
                                new User("agent1"),
                                new User("mgr1"),
                                new User("alice"),
                                category);
        }

        private CreateTicketRequest newTicketRequest() {
                return new CreateTicketRequest(
                                "Title", "Desc", TicketUrgency.MEDIUM, "agent1", "mgr1", category.getId(), null);
        }

        private static MockMultipartFile file(String name) {
                return new MockMultipartFile("files", name, "text/plain", "x".getBytes());
        }

        private static byte[] pngBytes() {
                return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        }

        private static long regularFileCount(Path root) throws IOException {
                try (var walk = Files.walk(root)) {
                        return walk.filter(Files::isRegularFile).count();
                }
        }
}
