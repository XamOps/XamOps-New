package com.xammer.cloud.controller.advice;

import com.xammer.cloud.controller.DspmAdminController;
import com.xammer.cloud.controller.DspmController;
import com.xammer.cloud.controller.DspmDatastoreController;
import com.xammer.cloud.controller.DspmEndpointController;
import com.xammer.cloud.controller.DspmSaasController;
import com.xammer.cloud.security.XamLensAccessDeniedException;
import com.xammer.cloud.service.matters.DspmBadRequestException;
import com.xammer.cloud.service.matters.MattersApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates {@link MattersApiException}, {@link DspmBadRequestException} and
 * {@link XamLensAccessDeniedException} into an HTTP response.
 *
 * <p>Scoped to the DSPM controllers with {@code assignableTypes} rather than registered globally. The
 * application already has a {@code GlobalExceptionHandler}, and adding a vendor-specific exception to it
 * would couple a shared file to this integration; scoping keeps the blast radius to these controllers.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} because {@code GlobalExceptionHandler} has no order and handles
 * {@code Exception}: Spring uses the first applicable advice that has a matching method, so without the
 * order these exceptions would become 500s whenever that bean happened to register first. Exception
 * types this class has no method for still fall through to {@code GlobalExceptionHandler}.
 *
 * <h2>The response contract</h2>
 * {@code code} is the {@link MattersApiException.Reason} name, {@code INVALID_REQUEST} or
 * {@code TAB_ACCESS_DENIED}, and is what the frontend branches on. {@code message} is prose and may be
 * reworded at any time — a UI that switched on message text would break the first time someone improved
 * it. The status comes from the reason itself, so it cannot drift between endpoints.
 */
@RestControllerAdvice(assignableTypes = {DspmController.class, DspmAdminController.class,
        DspmDatastoreController.class, DspmSaasController.class, DspmEndpointController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class MattersExceptionHandler {

    /**
     * 400 for a Datastores request that failed validation, before any vendor call. The message names
     * the parameter and the rule it broke, never the rejected value.
     */
    @ExceptionHandler(DspmBadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(DspmBadRequestException e) {
        log.debug("[DSPM] INVALID_REQUEST — {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_REQUEST");
        body.put("message", e.getMessage());
        body.put("parameter", e.parameter());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 403 for a caller without the {@code secops-xamlens} tab ({@code XamLensTabGuard}). */
    @ExceptionHandler(XamLensAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleTabAccessDenied(XamLensAccessDeniedException e) {
        log.debug("[DSPM] TAB_ACCESS_DENIED");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "TAB_ACCESS_DENIED");
        body.put("message", XamLensAccessDeniedException.MESSAGE);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

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
