package com.vmticket.common.error;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TicketNotFoundException.class)
    public ProblemDetail handleNotFound(TicketNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Ticket Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ProblemDetail handleCategoryNotFound(CategoryNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Category Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "User Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(CommentNotFoundException.class)
    public ProblemDetail handleCommentNotFound(CommentNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Comment Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(AttachmentNotFoundException.class)
    public ProblemDetail handleAttachmentNotFound(AttachmentNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Attachment Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(CategoryConflictException.class)
    public ProblemDetail handleCategoryConflict(CategoryConflictException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Category Conflict", ex.getMessage(), request);
    }

    @ExceptionHandler(UserConflictException.class)
    public ProblemDetail handleUserConflict(UserConflictException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "User Conflict", ex.getMessage(), request);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ProblemDetail handleOptimisticLock(RuntimeException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", "Ticket was updated by another request", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        if (isCategoryUniqueViolation(ex)) {
            return problem(HttpStatus.CONFLICT, "Category Conflict", "Category name or slug already exists", request);
        }
        if (isUserUniqueViolation(ex)) {
            return problem(HttpStatus.CONFLICT, "User Conflict", "username already exists", request);
        }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "Unexpected error", request);
    }

    @ExceptionHandler({InvalidStatusTransitionException.class, BadRequestException.class})
    public ProblemDetail handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUpload(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "file exceeds maximum size", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Malformed request", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid request parameter", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Validation Failed", "Request validation failed", request);
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), request);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail handleTooManyRequests(TooManyRequestsException ex, HttpServletRequest request) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", "Too many login attempts", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "Access denied", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "Unexpected error", request);
    }

    private static boolean isCategoryUniqueViolation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                boolean categories = lower.contains("categories");
                boolean unique = lower.contains("unique") || lower.contains("duplicate");
                if (categories && unique) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isUserUniqueViolation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                boolean users = lower.contains("users");
                boolean unique = lower.contains("unique") || lower.contains("duplicate");
                if (users && unique) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        return problemDetail;
    }
}
