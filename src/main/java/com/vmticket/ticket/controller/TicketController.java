package com.vmticket.ticket.controller;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.vmticket.common.paging.PageResponse;
import com.vmticket.security.AuthSupport;
import com.vmticket.ticket.dto.AttachmentMetadataResponse;
import com.vmticket.ticket.dto.CreateTicketRequest;
import com.vmticket.ticket.dto.TicketResponse;
import com.vmticket.ticket.dto.UpdateTicketRequest;
import com.vmticket.ticket.entity.TicketStatus;
import com.vmticket.ticket.entity.TicketUrgency;
import com.vmticket.ticket.service.AttachmentContent;
import com.vmticket.ticket.service.AttachmentService;
import com.vmticket.ticket.service.TicketService;

import jakarta.validation.Valid;

@RestController
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final AttachmentService attachmentService;

    public TicketController(TicketService ticketService, AttachmentService attachmentService) {
        this.ticketService = ticketService;
        this.attachmentService = attachmentService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request) {
        TicketResponse created = ticketService.create(request, AuthSupport.currentUsername(),
                AuthSupport.currentRoles());
        return ResponseEntity.created(location(created.id())).body(created);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketResponse> createMultipart(
            @RequestPart("ticket") @Valid CreateTicketRequest request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        TicketResponse created = ticketService.create(request, AuthSupport.currentUsername(),
                AuthSupport.currentRoles(), files);
        return ResponseEntity.created(location(created.id())).body(created);
    }

    @GetMapping("/{ticketId}/attachments")
    public PageResponse<AttachmentMetadataResponse> listAttachments(
            @PathVariable UUID ticketId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return attachmentService.listTicketFiles(
                ticketId, pageable, AuthSupport.currentUsername(), AuthSupport.currentRoles());
    }

    @GetMapping("/{ticketId}/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(
            @PathVariable UUID ticketId,
            @PathVariable UUID attachmentId) {
        AttachmentContent file = attachmentService.downloadTicketFile(
                ticketId, attachmentId, AuthSupport.currentUsername(), AuthSupport.currentRoles());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file.path()));
    }

    @DeleteMapping("/{ticketId}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(@PathVariable UUID ticketId, @PathVariable UUID attachmentId) {
        attachmentService.removeTicketFile(
                ticketId,
                attachmentId,
                AuthSupport.currentUsername(),
                AuthSupport.currentRoles());
    }

    @GetMapping("/{id}")
    public TicketResponse getById(@PathVariable UUID id) {
        return ticketService.getById(id, AuthSupport.currentUsername(), AuthSupport.currentRoles());
    }

    @GetMapping
    public PageResponse<TicketResponse> list(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketUrgency urgency,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String manager,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ticketService.search(
                status, urgency, agent, manager, q, pageable,
                AuthSupport.currentUsername(), AuthSupport.currentRoles());
    }

    @PatchMapping("/{id}")
    public TicketResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTicketRequest request) {
        return ticketService.update(id, request, AuthSupport.currentRoles(), AuthSupport.currentUsername());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) {
        ticketService.softDelete(id);
    }

    private static MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static URI location(UUID ticketId) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(ticketId)
                .toUri();
    }
}
