package az.testup.exception;

import az.testup.enums.AuditAction;
import az.testup.service.AuditLogService;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AuditLogService auditLogService;

    // ──────────────────────────── Domain exceptions ────────────────────────────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        // FORBIDDEN (403) — "authenticated but lacks permission". The
        // previous UNAUTHORIZED (401) made the frontend axios interceptor
        // run an unnecessary refresh-token cycle on every permission denial
        // (the token was fine; the user just lacked rights), and the failed
        // retry left the genuine error message hidden behind a refresh path.
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    // Without this handler the catch-all handleGeneral(Exception) wins over the
    // class's @ResponseStatus(FORBIDDEN), so limit errors surfaced as a generic
    // 500 and the plan-limit message never reached the user.
    @ExceptionHandler(SubscriptionLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleSubscriptionLimit(SubscriptionLimitExceededException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.error("Service unavailable: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    // Services use IllegalArgument/IllegalState for caller-fixable conditions;
    // their messages are written to be user-safe. Anything with no message
    // still falls back to a readable default rather than null.
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleIllegal(RuntimeException ex) {
        String message = ex.getMessage();
        return buildResponse(HttpStatus.BAD_REQUEST,
                message != null && !message.isBlank() ? message : "Sorğu yerinə yetirilə bilmədi");
    }

    // ──────────────────────────── Spring Security ──────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "Bu əməliyyat üçün icazəniz yoxdur");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "Giriş tələb olunur");
    }

    // ──────────────────────────── Validation ───────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getDefaultMessage())
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST,
                message.isBlank() ? "Daxil edilən məlumatlar yanlışdır" : message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST,
                message.isBlank() ? "Məlumat doğrulama xətası" : message);
    }

    // ──────────────────────────── HTTP / request ───────────────────────────────

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Sorğu məlumatları oxuna bilmir");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, "Bu HTTP metodu dəstəklənmir");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Tələb olunan parametr çatışmır: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Yanlış parametr tipi: " + ex.getName());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, "Səhifə tapılmadı");
    }

    // ──────────────────────────── Database ─────────────────────────────────────

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String msg = ex.getMostSpecificCause().getMessage();
        if (msg != null && (msg.contains("unique") || msg.contains("duplicate") || msg.contains("Unique"))) {
            return buildResponse(HttpStatus.CONFLICT, "Bu məlumat artıq mövcuddur");
        }
        log.error("Data integrity violation", ex);
        auditLogService.logCurrent(AuditAction.SYSTEM_ERROR, "DB",
                ex.getClass().getSimpleName(), trimMessage(msg));
        return buildResponse(HttpStatus.CONFLICT, "Verilənlər bazası xətası");
    }

    // ──────────────────────────── Fallback ─────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        // The message stays generic on purpose (no stack traces / SQL / paths to
        // the user), but carries a short correlation ID so support can find the
        // matching log line and audit entry.
        String errorId = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled exception [{}]: {}", errorId, ex.getMessage(), ex);
        String detail = trimMessage(ex.getMessage());
        auditLogService.logCurrent(AuditAction.SYSTEM_ERROR, "EXCEPTION",
                ex.getClass().getSimpleName(), "[" + errorId + "]" + (detail == null ? "" : " " + detail));
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "Gözlənilməz xəta baş verdi. Problem davam edərsə dəstəyə müraciət edin (kod: " + errorId + ")");
    }

    private String trimMessage(String msg) {
        if (msg == null) return null;
        return msg.length() > 500 ? msg.substring(0, 500) + "…" : msg;
    }

    // ───────────────────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "message", message,
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
