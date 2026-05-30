package com.xammer.cloud.exception;

import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import javax.servlet.http.HttpServletRequest; // ✅ Added Import
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles ClientAbortException (broken pipe).
     * Since the client has disconnected, we generally do not want to return a
     * response
     * or log a stack trace, as it creates noise and secondary errors.
     */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbortException() {
        // Do nothing. The client has disconnected.
    }

    /**
     * Handles general, unexpected exceptions throughout the application.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> globalExceptionHandler(Exception ex, HttpServletRequest request) { // ✅ Changed WebRequest
                                                                                                // to HttpServletRequest
        // Fallback check in case ClientAbortException is wrapped or not caught by the
        // specific handler
        if (ex instanceof ClientAbortException || ex.getCause() instanceof ClientAbortException) {
            return null;
        }

        // FIX: Skip non-JSON endpoints — static resources and WebSocket/SockJS transports.
        // SockJS paths already have Content-Type set to application/javascript, so writing
        // a JSON body causes HttpMessageNotWritableException → Broken pipe cascade.
        String path = request.getRequestURI().toLowerCase();
        String acceptHeader = request.getHeader("Accept");

        boolean isStaticResource = path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".map") ||
                path.endsWith(".ico") || path.endsWith(".png") || path.endsWith(".jpg") ||
                path.endsWith(".woff") || path.endsWith(".woff2");
        boolean isWebSocket = path.contains("/xamops-ws/") || path.contains("/ws/") ||
                path.contains("xhr_streaming") || path.contains("/xhr") ||
                path.contains("eventsource") || path.contains("htmlfile") ||
                path.contains("jsonp") || path.contains("websocket");
        boolean isJavascriptAccept = acceptHeader != null && acceptHeader.contains("javascript");

        if (isStaticResource || isWebSocket || isJavascriptAccept) {
            // Return status only — do not write a body over an incompatible Content-Type
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        log.error("Unhandled exception — {} {} tenant={}: {}",
                request.getMethod(), request.getRequestURI(), MDC.get("tenant"), ex.getMessage(), ex);

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", new Date());
        // SENTINEL: Return generic error message to prevent leaking internal implementation details via exception message
        body.put("message", "An unexpected error occurred while processing your request. Please contact support if the issue persists.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Handles authorization failures (insufficient permissions).
     * Returns 403 Forbidden instead of letting it fall through to the generic 500 handler.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", new Date());
        body.put("message", "Access is denied");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Handles authentication failures for bad credentials.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<?> badCredentialsExceptionHandler(BadCredentialsException ex, WebRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", new Date());
        body.put("message", "Invalid username or password");
        return new ResponseEntity<>(body, HttpStatus.UNAUTHORIZED);
    }

    /**
     * This new handler specifically catches asynchronous request timeouts.
     * It returns a proper ResponseEntity, which prevents the ClassCastException.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Map<String, String>> handleAsyncRequestTimeoutException(
            AsyncRequestTimeoutException ex, HttpServletRequest request) {
        log.warn("⏱ AsyncRequestTimeoutException — URI={} tenant={} thread={}",
                request.getRequestURI(),
                MDC.get("tenant"),
                Thread.currentThread().getName());

        Map<String, String> response = new HashMap<>();
        response.put("error", "Request Timed Out");
        response.put("message", "The request took too long to process and was terminated.");

        // Return a SERVICE_UNAVAILABLE status to indicate a temporary server-side issue
        return new ResponseEntity<>(response, HttpStatus.SERVICE_UNAVAILABLE);
    }
}