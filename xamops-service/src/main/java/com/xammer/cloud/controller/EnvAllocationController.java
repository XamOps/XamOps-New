package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.envallocation.EnvAllocationAudit;
import com.xammer.cloud.domain.envallocation.EnvAllocationEnvironment;
import com.xammer.cloud.domain.envallocation.EnvAllocationRun;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.cur.CostBasis;
import com.xammer.cloud.service.cur.CurAthenaQueryService;
import com.xammer.cloud.service.cur.ReportCannotAnswerException;
import com.xammer.cloud.service.envallocation.AllocationModel.RuleSpec;
import com.xammer.cloud.service.envallocation.EnvAllocationAuditService;
import com.xammer.cloud.service.envallocation.EnvAllocationEnvironmentService;
import com.xammer.cloud.service.envallocation.EnvAllocationException;
import com.xammer.cloud.service.envallocation.EnvAllocationExportService;
import com.xammer.cloud.service.envallocation.EnvAllocationRuleService;
import com.xammer.cloud.service.envallocation.EnvAllocationRunService;
import com.xammer.cloud.service.envallocation.EnvAllocationValueService;
import com.xammer.cloud.service.envallocation.EnvAllocationValueService.MatchValues;
import com.xammer.cloud.service.envallocation.EnvAllocationViews.Breakdown;
import com.xammer.cloud.service.envallocation.EnvAllocationViews.RunView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Environment cost allocation: the client's environments, their rules, and runs of those rules
 * over months of the AWS bill.
 *
 * <p>Environments and rules belong to the caller's client. Anything that reads the bill also names
 * an AWS account, and is checked against the caller's entitlement to it the same way the Cost and
 * Usage Report insights are — unknown and not-yours both answer 404.
 */
@RestController
@RequestMapping("/api/xamops/env-allocation")
public class EnvAllocationController {

    private static final Logger logger = LoggerFactory.getLogger(EnvAllocationController.class);

    private final EnvAllocationEnvironmentService environmentService;
    private final EnvAllocationRuleService ruleService;
    private final EnvAllocationRunService runService;
    private final EnvAllocationExportService exportService;
    private final EnvAllocationAuditService auditService;
    private final EnvAllocationValueService valueService;
    private final CloudAccountAccessService accessService;

    public EnvAllocationController(EnvAllocationEnvironmentService environmentService,
                                   EnvAllocationRuleService ruleService,
                                   EnvAllocationRunService runService,
                                   EnvAllocationExportService exportService,
                                   EnvAllocationAuditService auditService,
                                   EnvAllocationValueService valueService,
                                   CloudAccountAccessService accessService) {
        this.environmentService = environmentService;
        this.ruleService = ruleService;
        this.runService = runService;
        this.exportService = exportService;
        this.auditService = auditService;
        this.valueService = valueService;
        this.accessService = accessService;
    }

    // ── Environments ────────────────────────────────────────────────────────────

    public record EnvironmentView(Long id, String name, String color, List<String> matchValues,
                                  int sortOrder) {
        static EnvironmentView of(EnvAllocationEnvironment e) {
            return new EnvironmentView(e.getId(), e.getName(), e.getColor(),
                    e.getMatchValues() == null || e.getMatchValues().isBlank()
                            ? List.of()
                            : List.of(e.getMatchValues().split(",")),
                    e.getSortOrder());
        }
    }

    public record EnvironmentRequest(String name, String color, List<String> matchValues, Integer sortOrder) {
    }

    @GetMapping("/environments")
    public List<EnvironmentView> environments(@AuthenticationPrincipal ClientUserDetails user) {
        return environmentService.list(clientId(user), actor(user)).stream().map(EnvironmentView::of).toList();
    }

    @PostMapping("/environments")
    public EnvironmentView createEnvironment(@AuthenticationPrincipal ClientUserDetails user,
                                             @RequestBody EnvironmentRequest body) {
        return EnvironmentView.of(environmentService.create(clientId(user), actor(user),
                body.name(), body.color(), body.matchValues()));
    }

    @PutMapping("/environments/{id}")
    public EnvironmentView updateEnvironment(@AuthenticationPrincipal ClientUserDetails user,
                                             @PathVariable Long id,
                                             @RequestBody EnvironmentRequest body) {
        return EnvironmentView.of(environmentService.update(clientId(user), actor(user), id,
                body.name(), body.color(), body.matchValues(), body.sortOrder()));
    }

    @DeleteMapping("/environments/{id}")
    public ResponseEntity<Void> deleteEnvironment(@AuthenticationPrincipal ClientUserDetails user,
                                                  @PathVariable Long id) {
        environmentService.delete(clientId(user), actor(user), id);
        return ResponseEntity.noContent().build();
    }

    // ── Rules and versions ──────────────────────────────────────────────────────

    public record RulesRequest(List<RuleSpec> rules) {
    }

    public record PublishRequest(String note) {
    }

    @GetMapping("/rules")
    public EnvAllocationRuleService.DraftView draft(@AuthenticationPrincipal ClientUserDetails user) {
        return ruleService.draft(clientId(user), actor(user));
    }

    @PutMapping("/rules")
    public EnvAllocationRuleService.DraftView saveDraft(@AuthenticationPrincipal ClientUserDetails user,
                                                       @RequestBody RulesRequest body) {
        return ruleService.saveDraft(clientId(user), actor(user), body.rules());
    }

    @PostMapping("/rules/publish")
    public EnvAllocationRuleService.VersionSummary publish(@AuthenticationPrincipal ClientUserDetails user,
                                                          @RequestBody(required = false) PublishRequest body) {
        return ruleService.publish(clientId(user), actor(user), body == null ? null : body.note());
    }

    @GetMapping("/versions")
    public List<EnvAllocationRuleService.VersionSummary> versions(@AuthenticationPrincipal ClientUserDetails user) {
        return ruleService.versions(clientId(user));
    }

    @GetMapping("/versions/{number}")
    public EnvAllocationRuleService.VersionSnapshot version(@AuthenticationPrincipal ClientUserDetails user,
                                                           @PathVariable int number) {
        return ruleService.version(clientId(user), number);
    }

    // ── The month, split ───────────────────────────────────────────────────────

    /** The card's main read: the stored run for a closed month, a live split for the open one. */
    @GetMapping("/breakdown/{accountId}")
    public Breakdown breakdown(@AuthenticationPrincipal ClientUserDetails user,
                               @PathVariable String accountId,
                               @RequestParam(required = false) String period,
                               @RequestParam(required = false) String basis) {
        CloudAccount account = account(user, accountId);
        return runService.breakdown(clientId(user), actor(user), account, period(period), basis(basis));
    }

    public record PreviewRequest(Boolean useDraft, List<RuleSpec> rules) {
    }

    /**
     * Applies rules to a month without storing anything. With {@code rules} in the body, those
     * unsaved rules are previewed; without, the saved draft, or with {@code useDraft=false} the
     * latest published version.
     */
    @PostMapping("/preview/{accountId}")
    public Breakdown preview(@AuthenticationPrincipal ClientUserDetails user,
                             @PathVariable String accountId,
                             @RequestParam(required = false) String period,
                             @RequestParam(required = false) String basis,
                             @RequestBody(required = false) PreviewRequest body) {
        CloudAccount account = account(user, accountId);
        boolean useDraft = body == null || body.useDraft() == null || body.useDraft();
        return runService.previewRules(clientId(user), actor(user), account, period(period), basis(basis),
                useDraft, body == null ? null : body.rules());
    }

    /**
     * The values a rule can match on for one field, from this account's bill for the month, so the
     * rule form can offer them. {@code field=TAG_KEY} lists tag keys; {@code field=TAG} lists the
     * values of {@code tagKey}. {@code q} narrows a long list to values containing it.
     */
    @GetMapping("/values/{accountId}")
    public MatchValues values(@AuthenticationPrincipal ClientUserDetails user,
                              @PathVariable String accountId,
                              @RequestParam String field,
                              @RequestParam(required = false) String tagKey,
                              @RequestParam(required = false) String period,
                              @RequestParam(required = false) String q) {
        CloudAccount account = account(user, accountId);
        return valueService.values(clientId(user), account, period(period),
                EnvAllocationValueService.parseField(field), tagKey, q);
    }

    // ── Runs ────────────────────────────────────────────────────────────────────

    public record RunRequest(String accountId, String from, String to, String basis) {
    }

    @PostMapping("/runs")
    public List<RunView> queue(@AuthenticationPrincipal ClientUserDetails user,
                               @RequestBody RunRequest body) {
        CloudAccount account = account(user, body.accountId());
        YearMonth from = requiredMonth(body.from(), "from");
        YearMonth to = body.to() == null || body.to().isBlank() ? from : requiredMonth(body.to(), "to");
        return runService.queue(clientId(user), actor(user), account, from, to, basis(body.basis()));
    }

    @GetMapping("/runs/account/{accountId}")
    public List<RunView> history(@AuthenticationPrincipal ClientUserDetails user,
                                 @PathVariable String accountId) {
        CloudAccount account = account(user, accountId);
        return runService.history(clientId(user), account);
    }

    @GetMapping("/runs/{runId}")
    public Breakdown runBreakdown(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long runId) {
        EnvAllocationRun run = ownedRun(user, runId);
        return runService.runBreakdown(clientId(user), run);
    }

    @PostMapping("/runs/{runId}/lock")
    public RunView lock(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long runId) {
        ownedRun(user, runId);
        return runService.lock(clientId(user), actor(user), runId);
    }

    @PostMapping("/runs/{runId}/unlock")
    public RunView unlock(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long runId) {
        ownedRun(user, runId);
        return runService.unlock(clientId(user), actor(user), runId);
    }

    @GetMapping(value = "/runs/{runId}/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(@AuthenticationPrincipal ClientUserDetails user,
                                            @PathVariable Long runId) {
        EnvAllocationRun run = ownedRun(user, runId);
        String csv = exportService.csv(clientId(user), actor(user), run);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(body, 0, withBom, bom.length, body.length);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + exportService.fileStem(run) + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(withBom);
    }

    @GetMapping(value = "/runs/{runId}/export.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@AuthenticationPrincipal ClientUserDetails user,
                                            @PathVariable Long runId) {
        EnvAllocationRun run = ownedRun(user, runId);
        Breakdown breakdown = runService.runBreakdown(clientId(user), run);
        byte[] pdf = exportService.pdf(clientId(user), actor(user), run, breakdown);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + exportService.fileStem(run) + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ── Audit ───────────────────────────────────────────────────────────────────

    public record AuditView(Long id, String actor, String action, String entityType, String entityRef,
                            String detail, LocalDateTime createdAt) {
        static AuditView of(EnvAllocationAudit a) {
            return new AuditView(a.getId(), a.getActor(), a.getAction(), a.getEntityType(),
                    a.getEntityRef(), a.getDetail(), a.getCreatedAt());
        }
    }

    @GetMapping("/audit")
    public List<AuditView> audit(@AuthenticationPrincipal ClientUserDetails user) {
        return auditService.recent(clientId(user)).stream().map(AuditView::of).toList();
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    private static Long clientId(ClientUserDetails user) {
        if (user == null || user.getClientId() == null) {
            throw new AccessDeniedException("Authentication required.");
        }
        return user.getClientId();
    }

    private static String actor(ClientUserDetails user) {
        return user == null ? null : user.getUsername();
    }

    private CloudAccount account(ClientUserDetails user, String accountId) {
        clientId(user);
        return accessService.requireAwsAccess(user, accountId);
    }

    /** A run of this client's, on an account this caller may read. */
    private EnvAllocationRun ownedRun(ClientUserDetails user, Long runId) {
        EnvAllocationRun run = runService.run(clientId(user), runId);
        accessService.requireAwsAccess(user, run.getAwsAccountId());
        return run;
    }

    private static YearMonth period(String raw) {
        if (raw == null) {
            return YearMonth.now();
        }
        return requiredMonth(raw, "period");
    }

    private static YearMonth requiredMonth(String raw, String name) {
        try {
            return YearMonth.parse(raw.trim());
        } catch (RuntimeException e) {
            throw EnvAllocationException.badRequest("Expected " + name + " as YYYY-MM, got '" + raw + "'.");
        }
    }

    private static CostBasis basis(String raw) {
        try {
            return CostBasis.from(raw);
        } catch (IllegalArgumentException e) {
            throw EnvAllocationException.badRequest(e.getMessage());
        }
    }

    @ExceptionHandler(EnvAllocationException.class)
    public ResponseEntity<Map<String, String>> refused(EnvAllocationException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    /** 404, not 403, so the endpoint cannot be used to learn which account ids exist. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return ResponseEntity.status(404).body(Map.of("error", "Not found."));
    }

    @ExceptionHandler(ReportCannotAnswerException.class)
    public ResponseEntity<Map<String, String>> cannotAnswer(ReportCannotAnswerException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CurAthenaQueryService.CurQueryException.class)
    public ResponseEntity<Map<String, String>> queryFailed(CurAthenaQueryService.CurQueryException e) {
        logger.warn("[EnvAllocation] Report query failed: {}", e.getMessage());
        return ResponseEntity.status(502).body(Map.of(
                "error", "Could not read the Cost and Usage Report: " + e.getMessage()));
    }

    @ExceptionHandler(software.amazon.awssdk.core.exception.SdkException.class)
    public ResponseEntity<Map<String, String>> awsFailed(software.amazon.awssdk.core.exception.SdkException e) {
        logger.warn("[EnvAllocation] AWS call failed: {}", e.getMessage());
        return ResponseEntity.status(502).body(Map.of(
                "error", "Could not read the Cost and Usage Report: " + e.getMessage()));
    }

    /** Raised by the line reader when a month is too large at the grain the rules need. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unanswerable(IllegalStateException e) {
        logger.warn("[EnvAllocation] {}", e.getMessage());
        return ResponseEntity.unprocessableEntity().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
