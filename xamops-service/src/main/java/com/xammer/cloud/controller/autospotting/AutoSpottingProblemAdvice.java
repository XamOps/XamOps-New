package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.dto.autospotting.v2.ProblemDto;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseUnavailableException;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdcTaskDecorator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.ArrayList;
import java.util.List;

/**
 * The AutoSpotting error envelope (design-core.md §11 "Error envelope", plan-phase-09.md task 9.2).
 * An AutoSpotting-scoped {@code @RestControllerAdvice} ordered ahead of the application-wide
 * {@code GlobalExceptionHandler} (which echoes {@code ex.getMessage()}), so every AutoSpotting
 * controller returns RFC 9457 {@code application/problem+json} with a stable {@code code} the UI and
 * tests match on, and {@code detail} that is safe text only — never a raw provider message
 * (FR-SEC-02, AC-SEC-04).
 *
 * <p>Scope is by base package: it advises only
 * {@code com.xammer.cloud.controller.autospotting..} controllers, so no other feature's error shape
 * changes (FR-CMP-05). {@code @Order(HIGHEST_PRECEDENCE)} puts it ahead of
 * {@code GlobalExceptionHandler} for those controllers.
 *
 * <p>Mappings:
 * <ul>
 *   <li>{@link AutoSpottingApiException} — the stable code and status it carries (operator and v2
 *       codes: {@code AS_FORBIDDEN_ROLE}, {@code SWITCH_PROPERTY_OFF}, {@code SWITCH_VERSION_MISMATCH},
 *       {@code PLAN_KIND_NOT_ALLOWED}, {@code IDEMPOTENCY_KEY_REUSED}, {@code NOT_MANAGED}, ...);</li>
 *   <li>{@link AutoSpottingBlockedException} — the gate and Phase A/plan refusals ({@code AS_*},
 *       {@code PLAN_REQUIRED}, {@code AS_GROUP_BUSY}, {@code AUTOSPOTTING_CONVERSIONS_PAUSED}, ...);</li>
 *   <li>{@link LeaseUnavailableException} — 503 {@code AS_LEASE_UNAVAILABLE}, {@code retryable=true}
 *       (AC-SPN-02);</li>
 *   <li>{@link LeaseLostException} — 503 {@code AS_LEASE_UNAVAILABLE}, {@code retryable=true}: the
 *       lease moved under a request, which the client may retry;</li>
 *   <li>{@link AccessDeniedException} — 403 {@code AS_FORBIDDEN_ROLE} (the same opaque message the
 *       guard uses, so existence is never leaked);</li>
 *   <li>Bean Validation failures ({@link MethodArgumentNotValidException},
 *       {@link HandlerMethodValidationException}, {@link ConstraintViolationException}) — 400
 *       {@code CHECK_FAILED} with {@code fieldErrors} (AC-SEC-02).</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.xammer.cloud.controller.autospotting")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AutoSpottingProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingProblemAdvice.class);

    @ExceptionHandler(AutoSpottingApiException.class)
    public ResponseEntity<ProblemDto> handleApi(AutoSpottingApiException ex) {
        ProblemDto body = ProblemDto.of(ex.getCode(), ex.getStatus().value(), ex.getMessage())
                .title(ex.getStatus().getReasonPhrase())
                .requestId(requestId())
                .operationId(ex.getOperationId())
                .engagedSwitch(ex.getEngagedSwitch())
                .retryable(ex.isRetryable())
                .build();
        return problem(ex.getStatus(), body);
    }

    @ExceptionHandler(AutoSpottingBlockedException.class)
    public ResponseEntity<ProblemDto> handleBlocked(AutoSpottingBlockedException ex) {
        ProblemDto body = ProblemDto.of(ex.getCode(), ex.getStatus().value(), ex.getMessage())
                .title(ex.getStatus().getReasonPhrase())
                .requestId(requestId())
                .build();
        return problem(ex.getStatus(), body);
    }

    @ExceptionHandler(LeaseUnavailableException.class)
    public ResponseEntity<ProblemDto> handleLeaseUnavailable(LeaseUnavailableException ex) {
        ProblemDto body = ProblemDto.of(LeaseUnavailableException.ERROR_CODE,
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "The lease store is unavailable. Try again shortly.")
                .title(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase())
                .requestId(requestId())
                .retryable(true)
                .build();
        log.warn("AutoSpotting lease store unavailable: {}", ex.toString());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, body);
    }

    @ExceptionHandler(LeaseLostException.class)
    public ResponseEntity<ProblemDto> handleLeaseLost(LeaseLostException ex) {
        ProblemDto body = ProblemDto.of(LeaseUnavailableException.ERROR_CODE,
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "The operation lease moved. Try again.")
                .title(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase())
                .requestId(requestId())
                .retryable(true)
                .build();
        return problem(HttpStatus.SERVICE_UNAVAILABLE, body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDto> handleAccessDenied(AccessDeniedException ex) {
        // The same opaque 403 the guard raises: do not distinguish "not found" from "not yours".
        ProblemDto body = ProblemDto.of("AS_FORBIDDEN_ROLE", HttpStatus.FORBIDDEN.value(),
                        "You are not allowed to perform this action.")
                .title(HttpStatus.FORBIDDEN.getReasonPhrase())
                .requestId(requestId())
                .build();
        return problem(HttpStatus.FORBIDDEN, body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDto> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<ProblemDto.FieldError> fieldErrors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new ProblemDto.FieldError(fe.getField(), safe(fe.getDefaultMessage())));
        }
        return validationProblem(fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDto> handleParamValidation(HandlerMethodValidationException ex) {
        List<ProblemDto.FieldError> fieldErrors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result ->
                result.getResolvableErrors().forEach(error ->
                        fieldErrors.add(new ProblemDto.FieldError(
                                result.getMethodParameter().getParameterName(),
                                safe(error.getDefaultMessage())))));
        return validationProblem(fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDto> handleConstraintViolation(ConstraintViolationException ex) {
        List<ProblemDto.FieldError> fieldErrors = new ArrayList<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            String path = v.getPropertyPath() == null ? null : v.getPropertyPath().toString();
            fieldErrors.add(new ProblemDto.FieldError(lastNode(path), safe(v.getMessage())));
        }
        return validationProblem(fieldErrors);
    }

    private ResponseEntity<ProblemDto> validationProblem(List<ProblemDto.FieldError> fieldErrors) {
        ProblemDto body = ProblemDto.of("CHECK_FAILED", HttpStatus.BAD_REQUEST.value(),
                        "One or more fields are invalid.")
                .title(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .requestId(requestId())
                .fieldErrors(fieldErrors)
                .build();
        return problem(HttpStatus.BAD_REQUEST, body);
    }

    private static ResponseEntity<ProblemDto> problem(HttpStatus status, ProblemDto body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.valueOf("application/problem+json"))
                .body(body);
    }

    private static String requestId() {
        String value = MDC.get(AutoSpottingMdcTaskDecorator.REQUEST_ID);
        return (value == null || value.isEmpty()) ? null : value;
    }

    /** A validation message is author-controlled (our own constraints), so it is safe to surface. */
    private static String safe(String message) {
        return message == null ? "invalid" : message;
    }

    private static String lastNode(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
