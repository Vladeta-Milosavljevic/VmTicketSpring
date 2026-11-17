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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.vmticket.common.paging.PageResponse;
import com.vmticket.security.AuthSupport;
import com.vmticket.ticket.dto.CommentResponse;
import com.vmticket.ticket.dto.CreateCommentRequest;
import com.vmticket.ticket.service.AttachmentContent;
import com.vmticket.ticket.service.CommentService;

import jakarta.validation.Valid;

@RestController
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/tickets/{ticketId}/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public PageResponse<CommentResponse> list(
            @PathVariable UUID ticketId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return commentService.list(ticketId, pageable, AuthSupport.currentUsername(), AuthSupport.currentRoles());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CommentResponse> createJson(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CreateCommentRequest request) {
        CommentResponse created = commentService.create(
                ticketId,
                request.body(),
                List.of(),
                AuthSupport.currentUsername(),
                AuthSupport.currentRoles());
        return ResponseEntity.created(location(created.id())).body(created);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CommentResponse> createMultipart(
            @PathVariable UUID ticketId,
            @Valid @RequestPart("comment") CreateCommentRequest request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        CommentResponse created = commentService.create(
                ticketId,
                request.body(),
                files,
                AuthSupport.currentUsername(),
                AuthSupport.currentRoles());
        return ResponseEntity.created(location(created.id())).body(created);
    }

    @GetMapping("/{commentId}/attachments/{attachmentId}")
    public ResponseEntity<Resource> download(
            @PathVariable UUID ticketId,
            @PathVariable UUID commentId,
            @PathVariable UUID attachmentId) {
        AttachmentContent file = commentService.download(
                ticketId, commentId, attachmentId, AuthSupport.currentUsername(), AuthSupport.currentRoles());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        MediaType mediaType = mediaType(file.contentType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file.path()));
    }

    @DeleteMapping("/{commentId}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(
            @PathVariable UUID ticketId,
            @PathVariable UUID commentId,
            @PathVariable UUID attachmentId) {
        commentService.removeAttachment(
                ticketId,
                commentId,
                attachmentId,
                AuthSupport.currentUsername(),
                AuthSupport.currentRoles());
    }

    private static MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static URI location(UUID commentId) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(commentId)
                .toUri();
    }
}
