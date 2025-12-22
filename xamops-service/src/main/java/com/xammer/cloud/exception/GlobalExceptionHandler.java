package com.xammer.cloud.exception;

import org.apache.catalina.connector.ClientAbortException; // Import the specific exception
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

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
    public ResponseEntity<?> globalExceptionHandler(Exception ex, WebRequest request) {
        // Fallback check in case ClientAbortException is wrapped or not caught by the
        // specific handler
        if (ex instanceof ClientAbortException || ex.getCause() instanceof ClientAbortException) {
            return null;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", new Date());
        body.put("message", "An unexpected error occurred: " + ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
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
    public ResponseEntity<Map<String, String>> handleAsyncRequestTimeoutException(AsyncRequestTimeoutException ex) {
        Map<String, String> response = new HashMap<>();
        response.put("error", "Request Timed Out");
        response.put("message", "The request took too long to process and was terminated.");

        // Return a SERVICE_UNAVAILABLE status to indicate a temporary server-side issue
        return new ResponseEntity<>(response, HttpStatus.SERVICE_UNAVAILABLE);
    }
}