package com.vmticket.ticket.service;

import com.vmticket.common.error.AttachmentNotFoundException;
import com.vmticket.common.error.BadRequestException;
import com.vmticket.common.error.TicketNotFoundException;
import com.vmticket.common.paging.PageResponse;
import com.vmticket.config.AppStorageProperties;
import com.vmticket.user.Usernames;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;
import com.vmticket.ticket.dto.AttachmentMetadataResponse;
import com.vmticket.ticket.entity.Attachment;
import com.vmticket.ticket.entity.Comment;
import com.vmticket.ticket.entity.Ticket;
import com.vmticket.ticket.repository.AttachmentRepository;
import com.vmticket.ticket.repository.TicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class AttachmentService {

    static final int MAX_FILES = 5;
    static final int MAX_FILE_NAME_LENGTH = 255;
    static final int MAX_CONTENT_TYPE_LENGTH = 127;
    private static final int TEXT_SIGNATURE_BYTES = 8192;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF_SIGNATURE = {'G', 'I', 'F', '8'};
    private static final byte[] RIFF_SIGNATURE = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_SIGNATURE = {'W', 'E', 'B', 'P'};
    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F'};
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "pdf", "application/pdf",
            "txt", "text/plain",
            "csv", "text/csv");

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository attachmentRepository;
    private final TicketRepository ticketRepository;
    private final AppStorageProperties storageProperties;

    public AttachmentService(
            AttachmentRepository attachmentRepository,
            TicketRepository ticketRepository,
            AppStorageProperties storageProperties) {
        this.attachmentRepository = attachmentRepository;
        this.ticketRepository = ticketRepository;
        this.storageProperties = storageProperties;
    }

    /**
     * Reads and checks uploads before any ticket or comment row is saved.
     */
    public List<PreparedFile> prepare(List<MultipartFile> files) {
        List<MultipartFile> uploads = files == null ? List.of() : files;
        if (uploads.size() > MAX_FILES) {
            throw new BadRequestException("at most 5 files are allowed");
        }
        List<PreparedFile> pending = new ArrayList<>();
        try {
            for (MultipartFile file : uploads) {
                pending.add(readFile(file));
            }
            return pending;
        } catch (RuntimeException ex) {
            discard(pending);
            throw ex;
        }
    }

    /**
     * Writes prepared bytes under generated storage keys. The client path is never stored.
     */
    public List<Path> write(List<PreparedFile> prepared) {
        if (prepared == null || prepared.isEmpty()) {
            return List.of();
        }
        List<Path> written = new ArrayList<>();
        try {
            Path root = storageRoot();
            Files.createDirectories(root);
            for (PreparedFile pending : prepared) {
                String storageKey = UUID.randomUUID().toString();
                Path target = resolveUnderRoot(root, storageKey);
                Files.move(pending.tempFile(), target, StandardCopyOption.REPLACE_EXISTING);
                pending.markMoved();
                written.add(target);
                pending.setStorageKey(storageKey);
            }
            registerDeleteOnRollback(written);
            return written;
        } catch (RuntimeException ex) {
            deleteWritten(written);
            discard(prepared);
            throw ex;
        } catch (IOException ex) {
            deleteWritten(written);
            discard(prepared);
            throw new UncheckedIOException("Could not store file", ex);
        }
    }

    public List<Attachment> save(Ticket ticket, Comment comment, User uploader, List<PreparedFile> prepared) {
        if (prepared == null || prepared.isEmpty()) {
            return List.of();
        }
        List<Attachment> attachments = new ArrayList<>();
        for (PreparedFile pending : prepared) {
            attachments.add(new Attachment(
                    ticket,
                    comment,
                    uploader,
                    pending.fileName(),
                    pending.contentType(),
                    pending.size(),
                    pending.storageKey()));
        }
        attachmentRepository.saveAll(attachments);
        return attachments;
    }

    public void discard(Collection<PreparedFile> prepared) {
        if (prepared == null) {
            return;
        }
        for (PreparedFile pending : prepared) {
            pending.deleteTemp();
        }
    }

    public List<String> softDeleteActiveForTicket(UUID ticketId) {
        List<Attachment> attachments = attachmentRepository.findByTicket_IdAndDeletedAtIsNull(ticketId);
        for (Attachment attachment : attachments) {
            attachment.softDelete();
        }
        if (!attachments.isEmpty()) {
            attachmentRepository.saveAll(attachments);
        }
        return attachments.stream().map(Attachment::getStorageKey).toList();
    }

    /**
     * Deletes stored bytes only after the surrounding transaction commits.
     * A failed delete is logged and does not revive the row.
     */
    public void deleteAfterCommit(Collection<String> storageKeys) {
        if (storageKeys == null || storageKeys.isEmpty()) {
            return;
        }
        List<String> keys = List.copyOf(storageKeys);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    deleteStoredFile(key);
                }
            }
        });
    }

    public void deleteWritten(Collection<Path> written) {
        if (written == null) {
            return;
        }
        for (Path path : written) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // The original failure is what the caller sees. Leftover files can be removed later.
            }
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AttachmentMetadataResponse> listTicketFiles(
            UUID ticketId, Pageable pageable, String username, Set<Role> roles) {
        requireReadable(ticketId, username, roles);
        Pageable oldestFirst = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<AttachmentMetadataResponse> page = attachmentRepository
                .findByTicket_IdAndCommentIsNullAndDeletedAtIsNull(ticketId, oldestFirst)
                .map(AttachmentMetadataResponse::from);
        return PageResponse.from(page);
    }

    @Transactional(readOnly = true)
    public AttachmentContent downloadTicketFile(UUID ticketId, UUID attachmentId, String username, Set<Role> roles) {
        requireReadable(ticketId, username, roles);
        Attachment attachment = attachmentRepository
                .findByIdAndTicket_IdAndCommentIsNullAndDeletedAtIsNull(attachmentId, ticketId)
                .orElseThrow(() -> new AttachmentNotFoundException("Attachment not found: " + attachmentId));
        return read(attachment);
    }

    public void removeTicketFile(UUID ticketId, UUID attachmentId, String username, Set<Role> roles) {
        Ticket ticket = requireActive(ticketId);
        Attachment attachment = attachmentRepository
                .findByIdAndTicket_IdAndCommentIsNullAndDeletedAtIsNull(attachmentId, ticketId)
                .orElseThrow(() -> new AttachmentNotFoundException("Attachment not found: " + attachmentId));
        requireCanRemove(ticket, attachment, username, roles);
        softDelete(attachment);
    }

    public AttachmentContent read(Attachment attachment) {
        Path path = resolveUnderRoot(storageRoot(), attachment.getStorageKey());
        if (!Files.exists(path)) {
            throw new AttachmentNotFoundException("Attachment not found: " + attachment.getId());
        }
        return new AttachmentContent(path, attachment.getContentType(), attachment.getOriginalFileName());
    }

    public void softDelete(Attachment attachment) {
        attachment.softDelete();
        attachmentRepository.save(attachment);
        deleteAfterCommit(List.of(attachment.getStorageKey()));
    }

    private PreparedFile readFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file must not be empty");
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());
        String contentType = requireAllowedType(fileName, file.getContentType());
        Path temp = createTempFile();
        long size = 0;
        long maxBytes = storageProperties.getStorage().getMaxFileBytes();
        try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) {
                    throw new BadRequestException("file exceeds maximum size");
                }
                output.write(buffer, 0, read);
            }
        } catch (RuntimeException ex) {
            deleteQuietly(temp);
            throw ex;
        } catch (IOException ex) {
            deleteQuietly(temp);
            throw new UncheckedIOException("Could not read file", ex);
        }
        if (size == 0) {
            deleteQuietly(temp);
            throw new BadRequestException("file must not be empty");
        }
        try {
            requireMatchingSignature(temp, extension(fileName));
        } catch (RuntimeException ex) {
            deleteQuietly(temp);
            throw ex;
        }
        return new PreparedFile(fileName, contentType, temp, size);
    }

    private static String requireAllowedType(String fileName, String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_CONTENT_TYPE_LENGTH) {
            throw new BadRequestException("file type is not allowed");
        }
        String mediaType = raw.trim().toLowerCase(Locale.ROOT);
        int semicolon = mediaType.indexOf(';');
        if (semicolon >= 0) {
            mediaType = mediaType.substring(0, semicolon).trim();
        }
        String extension = extension(fileName);
        String allowed = ALLOWED_TYPES.get(extension);
        if (allowed == null || !allowed.equals(mediaType)) {
            throw new BadRequestException("file type is not allowed");
        }
        return mediaType;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Path createTempFile() {
        try {
            Path tmpDir = storageRoot().resolve(".tmp");
            Files.createDirectories(tmpDir);
            return Files.createTempFile(tmpDir, "upload-", ".part");
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read file", ex);
        }
    }

    /**
     * Keeps only the last path segment. The stored key is a generated id, never this name.
     */
    static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            throw new BadRequestException("file name must not be blank");
        }
        String normalized = original.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String name = (slash >= 0 ? normalized.substring(slash + 1) : normalized).trim();
        if (name.isBlank() || ".".equals(name) || "..".equals(name) || hasControlCharacter(name)) {
            throw new BadRequestException("file name must not be blank");
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            throw new BadRequestException("file name must be at most 255 characters");
        }
        return name;
    }

    private static boolean hasControlCharacter(String name) {
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private Path storageRoot() {
        String location = storageProperties.getStorage().getLocation();
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("app.storage.location is not configured");
        }
        return Path.of(location).toAbsolutePath().normalize();
    }

    private Path resolveUnderRoot(Path root, String storageKey) {
        requireUuidKey(storageKey);
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new AttachmentNotFoundException("Attachment not found");
        }
        return target;
    }

    private static void requireUuidKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new AttachmentNotFoundException("Attachment not found");
        }
        try {
            UUID.fromString(storageKey);
        } catch (IllegalArgumentException ex) {
            throw new AttachmentNotFoundException("Attachment not found");
        }
    }

    private void registerDeleteOnRollback(List<Path> written) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        List<Path> paths = List.copyOf(written);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteWritten(paths);
                }
            }
        });
    }

    private static void requireMatchingSignature(Path temp, String extension) {
        try (InputStream input = Files.newInputStream(temp)) {
            switch (extension) {
                case "png" -> requirePrefix(input, PNG_SIGNATURE);
                case "jpg", "jpeg" -> requirePrefix(input, JPEG_SIGNATURE);
                case "gif" -> requirePrefix(input, GIF_SIGNATURE);
                case "webp" -> requireWebp(input);
                case "pdf" -> requirePrefix(input, PDF_SIGNATURE);
                case "txt", "csv" -> requireNoNul(input);
                default -> throw new BadRequestException("file type is not allowed");
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read file", ex);
        }
    }

    private static void requirePrefix(InputStream input, byte[] prefix) throws IOException {
        byte[] actual = input.readNBytes(prefix.length);
        if (actual.length < prefix.length || !Arrays.equals(actual, prefix)) {
            throw new BadRequestException("file type is not allowed");
        }
    }

    private static void requireWebp(InputStream input) throws IOException {
        byte[] header = input.readNBytes(12);
        if (header.length < 12
                || !regionEquals(header, 0, RIFF_SIGNATURE)
                || !regionEquals(header, 8, WEBP_SIGNATURE)) {
            throw new BadRequestException("file type is not allowed");
        }
    }

    private static void requireNoNul(InputStream input) throws IOException {
        byte[] buffer = input.readNBytes(TEXT_SIGNATURE_BYTES);
        for (byte value : buffer) {
            if (value == 0) {
                throw new BadRequestException("file type is not allowed");
            }
        }
    }

    private static boolean regionEquals(byte[] bytes, int offset, byte[] expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (bytes[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private void deleteStoredFile(String storageKey) {
        try {
            Files.deleteIfExists(resolveUnderRoot(storageRoot(), storageKey));
        } catch (RuntimeException | IOException ex) {
            log.error("Could not delete stored file", ex);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The original failure is what the caller sees.
        }
    }

    private Ticket requireActive(UUID id) {
        return ticketRepository.findActiveById(id)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found: " + id));
    }

    private Ticket requireReadable(UUID id, String username, Set<Role> roles) {
        Ticket ticket = requireActive(id);
        TicketAccess.requireCanRead(ticket, username, roles);
        return ticket;
    }

    private static void requireCanRemove(Ticket ticket, Attachment attachment, String username, Set<Role> roles) {
        if (roles != null && roles.contains(Role.ADMIN)) {
            return;
        }
        String caller = Usernames.normalize(username);
        if (sameUser(attachment.getUploadedBy(), caller)) {
            return;
        }
        if (sameUser(ticket.getManager(), caller) && roles != null && roles.contains(Role.MANAGER)) {
            return;
        }
        throw new AccessDeniedException("Access denied");
    }

    private static boolean sameUser(User user, String username) {
        return user != null && username != null && username.equals(Usernames.normalize(user.getUsername()));
    }

    public static final class PreparedFile {
        private final String fileName;
        private final String contentType;
        private final long size;
        private Path tempFile;
        private String storageKey;

        private PreparedFile(String fileName, String contentType, Path tempFile, long size) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.tempFile = tempFile;
            this.size = size;
        }

        public String fileName() {
            return fileName;
        }

        public String contentType() {
            return contentType;
        }

        public long size() {
            return size;
        }

        public Path tempFile() {
            return tempFile;
        }

        public String storageKey() {
            return storageKey;
        }

        private void setStorageKey(String storageKey) {
            this.storageKey = storageKey;
        }

        private void markMoved() {
            this.tempFile = null;
        }

        private void deleteTemp() {
            if (tempFile == null) {
                return;
            }
            deleteQuietly(tempFile);
            tempFile = null;
        }
    }
}
