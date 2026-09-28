package com.xammer.cloud.controller.advice;

import com.xammer.cloud.controller.DspmAdminController;
import com.xammer.cloud.controller.DspmController;
import com.xammer.cloud.service.matters.MattersApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates {@link MattersApiException} into an HTTP response.
 *
 * <p>Scoped to the DSPM controllers with {@code assignableTypes} rather than registered globally. The
 * application already has a {@code GlobalExceptionHandler}, and adding a vendor-specific exception to it
 * would couple a shared file to this integration; scoping keeps the blast radius to these two
 * controllers.
 *
 * <h2>The response contract</h2>
 * {@code code} is the {@link MattersApiException.Reason} name and is what the frontend branches on.
 * {@code message} is prose and may be reworded at any time — a UI that switched on message text would
 * break the first time someone improved it. The status comes from the reason itself, so it cannot drift
 * between endpoints.
 */
@RestControllerAdvice(assignableTypes = {DspmController.class, DspmAdminController.class})
@Slf4j
public class MattersExceptionHandler {

    @ExceptionHandler(MattersApiException.class)
    public ResponseEntity<Map<String, Object>> handle(MattersApiException e) {
        MattersApiException.Reason reason = e.getReason();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());

        ResponseEntity.BodyBuilder response = ResponseEntity.status(reason.status());

        // Passed through so a polling client can back off by the interval the vendor asked for, rather
        // than guessing. Only meaningful for RATE_LIMITED; zero elsewhere.
        if (e.getRetryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
            body.put("retryAfterSeconds", e.getRetryAfterSeconds());
        }

        // Logged at the level the cause deserves. DECRYPT_FAILED and MALFORMED_KEY are our own
        // deployment faults and want a stack trace; the rest are expected operational states — a
        // customer whose project is not provisioned yet should not produce error noise on every page
        // load.
        switch (reason) {
            case DECRYPT_FAILED, MALFORMED_KEY ->
                    log.error("[DSPM] {} — {}", reason, e.getMessage(), e);
            case UPSTREAM_UNAVAILABLE, BAD_UPSTREAM_RESPONSE ->
                    log.warn("[DSPM] {} — {}", reason, e.getMessage());
            default ->
                    log.debug("[DSPM] {} — {}", reason, e.getMessage());
        }

        return response.body(body);
    }
}
