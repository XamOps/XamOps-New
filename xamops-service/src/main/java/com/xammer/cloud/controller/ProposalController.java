package com.xammer.cloud.controller;

import com.xammer.cloud.dto.autoscaling.ProposalDtos.ApprovalModeRequest;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ApprovalModeResponse;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ApproveRequest;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.DecisionResponse;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ProposalDetailDto;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ProposalListDto;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.RejectRequest;
import com.xammer.cloud.dto.k8s.K8sRefusalBody;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.security.K8sAutomationRequestContext;
import com.xammer.cloud.service.automation.proposal.ProposalException;
import com.xammer.cloud.service.automation.proposal.ProposalService;
import com.xammer.cloud.service.automation.proposal.ProposalService.DecisionResult;
import com.xammer.cloud.service.automation.workload.ProposalExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The user-facing Manual-mode API (design 3.8): the Pending actions queue, approve/reject, and the
 * per-cluster approval mode. Base path {@code /api/eks/autoscaling}, so
 * {@code K8sAutomationGuardInterceptor} runs on every request exactly as for the rest of node
 * automation: GET needs read access, every mutation passes the node-automation write gate.
 *
 * <p>The guard's {@link K8sAutomationRequestContext} is the <strong>only</strong> source of the cluster
 * scope: every proposal lookup is {@code (id, ctx.config().getId())}, so another cluster's proposal id is
 * a 404, never a 403. Refusals use the uniform body
 * {@code {success:false, code, message, field, detail}}; the proposal codes are bare
 * ({@code VERSION_CONFLICT}) except the three that mirror a {@code K8sAutomationReason}
 * ({@code K8S_CONTROLLER_NOT_READY}, {@code K8S_MODE_DISABLED}, {@code K8S_AGENT_TOO_OLD}).
 */
@RestController
@RequestMapping("/api/eks/autoscaling")
public class ProposalController {

    private static final Logger logger = LoggerFactory.getLogger(ProposalController.class);

    private final ProposalService proposalService;
    private final ProposalExecutionService executionService;

    public ProposalController(ProposalService proposalService, ProposalExecutionService executionService) {
        this.proposalService = proposalService;
        this.executionService = executionService;
    }

    @GetMapping("/{clusterName}/proposals")
    public ResponseEntity<ProposalListDto> list(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ResponseEntity.ok(proposalService.list(ctx.config(), status, limit, cursor));
    }

    @GetMapping("/{clusterName}/proposals/{id}")
    public ResponseEntity<ProposalDetailDto> detail(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @PathVariable("id") String id) {
        return ResponseEntity.ok(proposalService.detail(ctx.config(), id));
    }

    @PostMapping("/{clusterName}/proposals/{id}/approve")
    public ResponseEntity<DecisionResponse> approve(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @PathVariable("id") String id,
            @RequestBody ApproveRequest body) {
        DecisionResult result = proposalService.approve(ctx, id, body);
        if (result.dispatchWorkload()) {
            // After the approving transaction committed: a pool rejection puts the row back and answers 503.
            executionService.submit(ctx.config().getId(), result.id(), ctx.user().getUsername());
        }
        DecisionResponse response = new DecisionResponse(result.id(), result.status(), result.alreadyDecided());
        return ResponseEntity.status(result.alreadyDecided() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{clusterName}/proposals/{id}/reject")
    public ResponseEntity<DecisionResponse> reject(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @PathVariable("id") String id,
            @RequestBody RejectRequest body) {
        DecisionResult result = proposalService.reject(ctx, id, body);
        return ResponseEntity.ok(new DecisionResponse(result.id(), result.status(), result.alreadyDecided()));
    }

    @PutMapping("/{clusterName}/approval-mode")
    public ResponseEntity<ApprovalModeResponse> setApprovalMode(
            @RequestAttribute(K8sAutomationRequestContext.ATTRIBUTE) K8sAutomationRequestContext ctx,
            @RequestBody ApprovalModeRequest body) {
        return ResponseEntity.ok(proposalService.setApprovalMode(ctx, body == null ? null : body.mode()));
    }

    // ------------------------------------------------------------------ refusals

    @ExceptionHandler(ProposalException.class)
    public ResponseEntity<K8sRefusalBody> handleProposal(ProposalException e, HttpServletRequest request) {
        logger.info("Proposal request refused: method={} path={} status={} code={}", request.getMethod(),
                request.getRequestURI(), e.getStatus().value(), e.getCode());
        return ResponseEntity.status(e.getStatus())
                .body(new K8sRefusalBody(false, e.getCode(), e.getMessage(), e.getField(), null));
    }

    @ExceptionHandler(K8sAutomationRefusedException.class)
    public ResponseEntity<K8sRefusalBody> handleRefused(K8sAutomationRefusedException e, HttpServletRequest request) {
        K8sAutomationResponses.logRefusal(logger, request, e);
        return K8sAutomationResponses.refusal(e);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<K8sRefusalBody> handleUnreadable(HttpMessageNotReadableException e,
                                                           HttpServletRequest request) {
        String field = K8sAutomationResponses.jsonField(e);
        logger.warn("Proposal body rejected: method={} path={} field={}", request.getMethod(),
                request.getRequestURI(), field);
        return K8sAutomationResponses.refusal(K8sAutomationReason.INVALID_REQUEST,
                K8sAutomationReason.INVALID_REQUEST.defaultMessage(), field);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<K8sRefusalBody> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return K8sAutomationResponses.refusal(K8sAutomationReason.INVALID_REQUEST,
                K8sAutomationReason.INVALID_REQUEST.defaultMessage(), e.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<K8sRefusalBody> handleMissingParameter(MissingServletRequestParameterException e) {
        return K8sAutomationResponses.refusal(K8sAutomationReason.INVALID_REQUEST,
                K8sAutomationReason.INVALID_REQUEST.defaultMessage(), e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<K8sRefusalBody> handleUnexpected(Exception e, HttpServletRequest request) {
        logger.error("Proposal request failed: method={} path={}", request.getMethod(), request.getRequestURI(), e);
        return K8sAutomationResponses.internal(request);
    }
}
