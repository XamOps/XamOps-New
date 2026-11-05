package com.xammer.cloud.xampulse.exception;

import com.xammer.cloud.xampulse.dto.XpError;
import com.xammer.cloud.xampulse.dto.XpErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/** Maps XamPulse failures to the contract's error envelope. Scoped to the XamPulse controllers only. */
@RestControllerAdvice(basePackages = "com.xammer.cloud.xampulse.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class XamPulseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(XamPulseExceptionHandler.class);

    @ExceptionHandler(XamPulseException.class)
    public ResponseEntity<XpErrorEnvelope> handle(XamPulseException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(new XpErrorEnvelope(new XpError(ex.getCode(), ex.getMessage(), requestId(request), ex.getDetails())));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<XpErrorEnvelope> handleBadRequest(Exception ex, HttpServletRequest request) {
        // Do not echo parser internals; the message from IllegalArgumentException is authored by us.
        String message = ex instanceof IllegalArgumentException ? String.valueOf(ex.getMessage()) : "Invalid request.";
        return ResponseEntity.badRequest()
                .body(new XpErrorEnvelope(new XpError("XP_INVALID_REQUEST", message, requestId(request), Map.of())));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<XpErrorEnvelope> handleDenied(
            org.springframework.security.access.AccessDeniedException ex, HttpServletRequest request) {
        // Account tenancy denials are converted to XamPulseException.notFound() before reaching here;
        // what remains is a role denial.
        return ResponseEntity.status(403)
                .body(new XpErrorEnvelope(new XpError("XP_FORBIDDEN", "You do not have permission to do that.",
                        requestId(request), Map.of())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<XpErrorEnvelope> handleOther(Exception ex, HttpServletRequest request) {
        String id = requestId(request);
        log.error("XamPulse unexpected error requestId={}", id, ex);
        return ResponseEntity.status(500)
                .body(new XpErrorEnvelope(new XpError("XP_STORE_UNAVAILABLE", "Unexpected error.", id, Map.of())));
    }

    private static String requestId(HttpServletRequest request) {
        String h = request.getHeader("X-Request-Id");
        if (h != null && h.matches("[A-Za-z0-9._-]{1,64}")) {
            return h;
        }
        return UUID.randomUUID().toString();
    }
}
