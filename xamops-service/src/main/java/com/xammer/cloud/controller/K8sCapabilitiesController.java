package com.xammer.cloud.controller;

import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto;
import com.xammer.cloud.dto.k8s.K8sRefusalBody;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationGuardInterceptor;
import com.xammer.cloud.service.K8sCapabilitiesService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * {@code GET /api/xamops/k8s/capabilities?accountId=<awsAccountId>} (design §11.4): what the caller
 * may change on each registered cluster of one AWS account, and why not.
 *
 * <p>
 * <b>Synchronous on purpose.</b> It returns a {@link ResponseEntity}, never a
 * {@code CompletableFuture} or an {@code @Async} result, unlike its neighbour
 * {@code CloudK8sController}: {@code ImpersonationContext} is a thread-local set on the request
 * thread, so work handed to another thread could read a missing impersonation id and report
 * writes as allowed.
 */
@RestController
@RequestMapping("/api/xamops/k8s")
public class K8sCapabilitiesController {

    private static final Logger logger = LoggerFactory.getLogger(K8sCapabilitiesController.class);

    private final K8sCapabilitiesService capabilitiesService;

    public K8sCapabilitiesController(K8sCapabilitiesService capabilitiesService) {
        this.capabilitiesService = capabilitiesService;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities(@AuthenticationPrincipal ClientUserDetails user,
                                          @RequestParam String accountId) {
        if (user == null) {
            return K8sAutomationResponses.refusal(K8sAutomationRefusedException.of(K8sAutomationReason.UNAUTHENTICATED));
        }
        if (!K8sAutomationGuardInterceptor.ACCOUNT_ID.matcher(accountId).matches()) {
            return K8sAutomationResponses.refusal(K8sAutomationReason.INVALID_REQUEST,
                    "accountId must be a 1-12 digit AWS account number.", "accountId");
        }
        K8sCapabilitiesDto body = capabilitiesService.capabilities(user, accountId);
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(K8sAutomationRefusedException.class)
    public ResponseEntity<K8sRefusalBody> handleRefused(K8sAutomationRefusedException e, HttpServletRequest request) {
        K8sAutomationResponses.logRefusal(logger, request, e);
        return K8sAutomationResponses.refusal(e);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<K8sRefusalBody> handleMissingParameter(MissingServletRequestParameterException e,
                                                                 HttpServletRequest request) {
        logger.warn("K8s capabilities parameter missing: path={} field={}", request.getRequestURI(), e.getParameterName());
        return K8sAutomationResponses.refusal(K8sAutomationReason.INVALID_REQUEST,
                K8sAutomationReason.INVALID_REQUEST.defaultMessage(), e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<K8sRefusalBody> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                             HttpServletRequest request) {
        logger.warn("K8s capabilities parameter rejected: path={} field={}", request.getRequestURI(), e.getName());
        return K8sAutomationResponses.refusal(K8sAutomationReason.INVALID_REQUEST,
                K8sAutomationReason.INVALID_REQUEST.defaultMessage(), e.getName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<K8sRefusalBody> handleUnexpected(Exception e, HttpServletRequest request) {
        logger.error("K8s capabilities request failed: path={}", request.getRequestURI(), e);
        return K8sAutomationResponses.refusal(K8sAutomationReason.INTERNAL,
                K8sAutomationReason.INTERNAL.defaultMessage(), null);
    }
}
