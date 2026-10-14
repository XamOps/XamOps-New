package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.workload.HorizontalAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.HpaReason;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.HpaApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.HpaApplyResponseDto;
import com.xammer.cloud.service.automation.workload.api.dto.RefusalDto;
import com.xammer.cloud.service.automation.workload.execute.HpaActionCommand;
import com.xammer.cloud.service.automation.workload.execute.HpaApplyExecutor;
import com.xammer.cloud.service.automation.workload.execute.HpaApplyOutcome;
import com.xammer.cloud.service.automation.workload.execute.HpaPatchClient;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The HPA apply-by-click path — the horizontal counterpart to {@link VerticalApplyService}, running
 * the <strong>same gates in code, in the same published order</strong>.
 *
 * <h2>The gates run in code, in the published order</h2>
 *
 * {@code DEMO} / {@code NO_USER_PERMISSION} (incl. Login-As) → {@code READ_ONLY_CONNECTION} →
 * {@code CONTROLLER_NOT_READY} (the HPA readiness) → {@code CLUSTER_ACCESS_NOT_CONFIGURED} /
 * {@code CLUSTER_UNREACHABLE} → the body, then the HPA safety rules and the shared cooldown. In code
 * rather than with {@code @PreAuthorize} for the same reason the vertical path is: a Spring-generated
 * {@code 403} carries no body, and the UI disables a control and explains <em>why</em> from the
 * refusal's {@code code}. {@link AutomationWritePermission} is the shared predicate, so the same user
 * cannot be allowed to change HPA automation on one screen and refused on the other.
 *
 * <h2>The submitted bounds are re-validated against the current HPA, never clamped</h2>
 *
 * The body is checked against the <strong>current</strong> HPA state before any patch: a GitOps-owned
 * HPA, a {@code minReplicas} below a PDB's {@code minAvailable}, a {@code maxReplicas} above the
 * operator's hard ceiling, a lower below observed need, and the fail-safe {@code *_NOT_READABLE}
 * states are each <strong>refused with the rule named</strong> ({@link HpaReason}), never silently
 * clamped. A moved CPU target with no readable {@code Resource: cpu} metric is refused. Refusals carry
 * {@code K8S_INVALID_POLICY} for a safety rule and {@code K8S_<gate>} for a gate.
 *
 * <h2>The cooldown is the shared model</h2>
 *
 * An apply inside the policy's cooldown is refused here as it is for the vertical path — and, unlike
 * the vertical memory-OOM exemption, <strong>a replica raise under unmet demand is not cooldown
 * exempt</strong>: the cooldown gates the next ceiling move precisely so one click cannot chase a
 * climbing metric upward every minute.
 *
 * <p>{@code dryRun} is mandatory and {@code autoApplyEligible} does not exist here: there is no HPA
 * auto tier in this task, so every apply is user-initiated.
 */
@Service
public class HpaApplyService {

    private static final Logger logger = LoggerFactory.getLogger(HpaApplyService.class);

    /** How long a submitted command stays applicable — the same short window the vertical path uses. */
    static final Duration COMMAND_VALIDITY = Duration.ofMinutes(5);

    private static final String CPU = "cpu";
    private static final String RESOURCE = "Resource";

    private final HorizontalAutoscalingProperties properties;
    private final AutomationWritePermission writePermission;
    private final List<ClusterAccessProvider> accessProviders;
    private final ClusterWorkloadInventory inventory;
    private final UpstreamVpaReader vpaReader;
    private final HpaPatchClient patchClient;
    private final HpaApplyExecutor executor;
    private final WorkloadPolicyService policyService;
    private final VerticalMetricsProperties metricsProperties;
    private final Clock clock;

    @Autowired
    public HpaApplyService(HorizontalAutoscalingProperties properties,
                           AutomationWritePermission writePermission,
                           List<ClusterAccessProvider> accessProviders,
                           ClusterWorkloadInventory inventory,
                           UpstreamVpaReader vpaReader,
                           HpaPatchClient patchClient,
                           HpaApplyExecutor executor,
                           WorkloadPolicyService policyService,
                           VerticalMetricsProperties metricsProperties) {
        this(properties, writePermission, accessProviders, inventory, vpaReader, patchClient, executor,
                policyService, metricsProperties, Clock.systemUTC());
    }

    HpaApplyService(HorizontalAutoscalingProperties properties,
                    AutomationWritePermission writePermission,
                    List<ClusterAccessProvider> accessProviders,
                    ClusterWorkloadInventory inventory,
                    UpstreamVpaReader vpaReader,
                    HpaPatchClient patchClient,
                    HpaApplyExecutor executor,
                    WorkloadPolicyService policyService,
                    VerticalMetricsProperties metricsProperties,
                    Clock clock) {
        this.properties = properties;
        this.writePermission = writePermission;
        this.inventory = inventory;
        this.vpaReader = vpaReader;
        this.patchClient = patchClient;
        this.executor = executor;
        this.policyService = policyService;
        this.metricsProperties = metricsProperties;
        this.clock = clock;
        List<ClusterAccessProvider> ordered = new ArrayList<>(
                accessProviders == null ? List.of() : accessProviders);
        AnnotationAwareOrderComparator.sort(ordered);
        this.accessProviders = List.copyOf(ordered);
    }

    /**
     * @param account the AWS account the caller is <strong>already entitled to</strong>; entitlement
     *                is the controller's job, this method only reads the connection's access type
     */
    public HpaApplyResponseDto apply(ClientUserDetails principal, CloudAccount account,
                                     ClusterTarget target, WorkloadRef workloadRef,
                                     HpaApplyRequestDto request) {
        requirePermission(principal, account);
        requireWriteConnection(account);
        requireControllerReady();

        Submitted submitted = requireBody(request);

        ClusterAccessProvider provider = accessProviders.stream()
                .filter(candidate -> candidate.supports(target))
                .findFirst()
                .orElseThrow(() -> new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN,
                        ReasonCode.CLUSTER_ACCESS_NOT_CONFIGURED.refusalCode(), null,
                        "XamOps has no credential path into this cluster, so nothing can be applied."
                                + " Create an EKS access entry for the role registered for this"
                                + " account."));

        try (ClusterAccess access = open(provider, target)) {
            ClusterWorkloadInventory.Workload workload = inventory.read(access.client()).workloads()
                    .stream()
                    .filter(candidate -> matches(candidate, workloadRef))
                    .findFirst()
                    .orElseThrow(() -> invalid("name", "This cluster has no " + workloadRef.kind()
                            + " " + workloadRef.describe() + " that XamOps can tune, so there is"
                            + " nothing to apply."));

            // Fail-safe: a mutating or unreadable native VPA owns the requests, but the HPA still
            // tunes bounds -- except an unreadable VPA, which yields the whole workload.
            requireVpaReadable(access, workload.ref());

            if (workload.hpa() == null || !workload.hpa().targets(workload.ref())) {
                throw invalid("name", workloadRef.describe() + " has no HPA that XamOps can tune."
                        + " Use the create endpoint to add one.");
            }

            // Read the HPA itself for the resourceVersion (the optimistic precondition) and the
            // live bounds/target the body is re-validated against -- never a stale snapshot.
            HorizontalPodAutoscaler hpa = patchClient.currentHpa(access.client(),
                    workload.hpa().namespace(), workload.hpa().name());
            if (hpa == null) {
                throw refusal(HpaReason.HPA_NOT_READABLE, "name", workloadRef.describe()
                        + "'s HPA could not be read back, so XamOps will not patch it.");
            }

            WorkloadPolicy policy = policyService.resolve(target.clusterConfigId(), workload.ref(),
                    metricsProperties.defaultsPolicy());

            revalidate(submitted, workload, hpa);
            requireCooldownElapsed(policy, workload.ref());

            boolean dryRun = Boolean.TRUE.equals(request.dryRun());
            HpaActionCommand command = new HpaActionCommand(UUID.randomUUID().toString(), target,
                    workload.ref(), hpa.getMetadata().getNamespace(), hpa.getMetadata().getName(),
                    submitted.min(), submitted.max(), submitted.target(),
                    hpa.getMetadata() == null ? null : hpa.getMetadata().getResourceVersion(),
                    dryRun, clock.instant().plus(COMMAND_VALIDITY));

            HpaApplyOutcome outcome = executor.submit(command, access, hpa);

            // A real patch that changed the HPA starts the cooldown, so the server enforces the same
            // hold the engine publishes and the UI disables Apply for. A dry run writes nothing.
            if (!dryRun && outcome.applied()) {
                policyService.recordApplied(target.clientId(), target.clusterConfigId(),
                        workload.ref(), policy, clock.instant());
            }

            logger.info("HPA apply {} for {} on {}: submit {} terminal {}", command.correlationId(),
                    workload.ref().describe(), target.describe(), outcome.submitState(),
                    outcome.terminal());

            return toResponse(outcome);
        }
    }

    private static HpaApplyResponseDto toResponse(HpaApplyOutcome outcome) {
        switch (outcome.terminal()) {
            case APPLIED:
                // A landed patch (or a dry-run rehearsal that would have landed).
                return HpaApplyResponseDto.accepted(outcome.correlationId(),
                        outcome.submitState().name(), outcome.dryRun(), outcome.minReplicas(),
                        outcome.maxReplicas(), outcome.targetUtilizationPct());
            case QUEUED:
                // A genuinely pending submission (the agent path): the applied result arrives later,
                // so 202 ACCEPTED is correct here and only here.
                return HpaApplyResponseDto.of(outcome.correlationId(), outcome.submitState().name(),
                        outcome.dryRun());
            case FAILED:
                // The patch was rejected by the API server or its outcome could not be confirmed.
                // This is NOT a pending accept: surface it as a failure carrying the reason, never
                // as 202 ACCEPTED with a dropped detail (the Defect-3 bug).
                throw new VerticalApplyService.ApplyRefusedException(HttpStatus.BAD_GATEWAY,
                        "K8S_APPLY_FAILED", null,
                        outcome.detail() == null ? "The HPA patch failed." : outcome.detail());
            case EXPIRED:
                throw new VerticalApplyService.ApplyRefusedException(HttpStatus.CONFLICT,
                        "K8S_COMMAND_EXPIRED", null,
                        outcome.detail() == null ? "The apply command expired before it was applied."
                                : outcome.detail());
            case REFUSED:
            default:
                // A post-submit refusal (the HPA vanished, or a lost optimistic race): nothing was
                // written, and the reason must reach the caller rather than be dropped.
                throw new VerticalApplyService.ApplyRefusedException(HttpStatus.CONFLICT,
                        "K8S_APPLY_REFUSED", null,
                        outcome.detail() == null ? "The HPA apply was refused." : outcome.detail());
        }
    }

    // ---------------------------------------------------------------- gates

    private void requirePermission(ClientUserDetails principal, CloudAccount account) {
        if (principal == null) {
            throw new VerticalApplyService.ApplyRefusedException(HttpStatus.UNAUTHORIZED,
                    RefusalDto.UNAUTHENTICATED, null, "Sign in again to apply automation changes.");
        }
        AutomationWritePermission.Decision decision = writePermission.decide(principal, account);
        if (!decision.permitted()) {
            logger.info("HPA apply refused for user {}: {} ({})", principal.getId(),
                    decision.refusalCode(), decision.detail());
            throw new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN,
                    decision.refusalCode(), null, decision.message(), decision.detail());
        }
    }

    private static void requireWriteConnection(CloudAccount account) {
        if (account == null || !"read-write".equalsIgnoreCase(account.getAccessType())) {
            throw new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN,
                    ReasonCode.READ_ONLY_CONNECTION.refusalCode(), null,
                    "This AWS account is connected read-only, so XamOps will not change anything in"
                            + " it. Reconnect it with read-write access to apply recommendations.");
        }
    }

    private void requireControllerReady() {
        if (!properties.isApplyPermitted()) {
            throw new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN,
                    ReasonCode.CONTROLLER_NOT_READY.refusalCode(), null,
                    "HPA automation has not been enabled and validated in this environment, so"
                            + " nothing can be applied.");
        }
    }

    private void requireVpaReadable(ClusterAccess access, WorkloadRef ref) {
        UpstreamVpaReader.View view = vpaReader.read(access.client());
        switch (view.modeFor(ref)) {
            case UNREADABLE -> throw refusal(HpaReason.VPA_NOT_READABLE, "name", ref.describe()
                    + "'s VPA ownership could not be read, so XamOps yields the whole workload"
                    + " rather than tune its HPA against an unknown owner.");
            default -> {
                // MUTATING is fine: a native VPA owns the pod REQUESTS, which the HPA path never
                // writes. The two controllers write different resources, so a bound tune coexists.
            }
        }
    }

    // ---------------------------------------------------------------- the body

    private static Submitted requireBody(HpaApplyRequestDto request) {
        if (request == null) {
            throw invalidRequest("minReplicas",
                    "An HPA apply needs a body naming at least one bound or the target to change.");
        }
        if (request.dryRun() == null) {
            throw invalidRequest("dryRun", "dryRun is required on every apply, and must be true or"
                    + " false. It is echoed in the response and on the audit record, so it is never"
                    + " inferred.");
        }
        if (request.isEmpty()) {
            throw invalidRequest("minReplicas", "An HPA apply with no bound and no target would"
                    + " change nothing. A null field means 'leave that field alone'.");
        }
        requirePositive("minReplicas", request.minReplicas());
        requirePositive("maxReplicas", request.maxReplicas());
        if (request.targetUtilizationPct() != null
                && (request.targetUtilizationPct() <= 0 || request.targetUtilizationPct() > 100)) {
            throw invalidRequest("targetUtilizationPct", "targetUtilizationPct must be in 1..100,"
                    + " got " + request.targetUtilizationPct() + ".");
        }
        if (request.minReplicas() != null && request.maxReplicas() != null
                && request.minReplicas() > request.maxReplicas()) {
            throw invalidRequest("minReplicas", "minReplicas " + request.minReplicas()
                    + " exceeds maxReplicas " + request.maxReplicas()
                    + ": an HPA cannot have a floor above its ceiling.");
        }
        return new Submitted(request.minReplicas(), request.maxReplicas(),
                request.targetUtilizationPct());
    }

    private static void requirePositive(String field, Integer value) {
        if (value != null && value <= 0) {
            throw invalidRequest(field, field + " must be a positive whole number, got " + value + ".");
        }
    }

    // ---------------------------------------------------------------- re-validation (never clamped)

    /**
     * Checks the submitted bounds/target against the HPA as it exists now, refusing with the rule
     * named rather than clamping. The resulting command must still satisfy {@code min <= max} once
     * the submitted fields are merged over the current ones.
     */
    private static void revalidate(Submitted submitted, ClusterWorkloadInventory.Workload workload,
                                   HorizontalPodAutoscaler hpa) {
        WorkloadRef ref = workload.ref();
        var hpaView = workload.hpa();

        if (hpaView.isGitopsManaged()) {
            throw refusal(HpaReason.GITOPS_MANAGED, "name", ref.describe() + "'s HPA is owned by"
                    + " Argo CD, Flux or Helm, so XamOps shows the change but never patches it.");
        }

        // A lower that would drop below observed need is refused rather than guessed at: when the
        // look-back history is unreadable the engine never lowers, and neither does an apply.
        if (submitted.max() != null && hpaView.maxReplicas() != null
                && submitted.max() < hpaView.maxReplicas()) {
            if (!hpaView.historyReadable() || hpaView.observedPeakDesiredReplicas() == null) {
                throw refusal(HpaReason.BELOW_OBSERVED_NEED, "maxReplicas", "The HPA's replica"
                        + " history for " + ref.describe() + " is unreadable, so XamOps will not"
                        + " lower its ceiling: it would be shrinking below a need it cannot see.");
            }
            if (submitted.max() < hpaView.observedPeakDesiredReplicas()) {
                throw refusal(HpaReason.BELOW_OBSERVED_NEED, "maxReplicas", "A ceiling of "
                        + submitted.max() + " is below the observed peak of "
                        + hpaView.observedPeakDesiredReplicas() + " replicas for " + ref.describe()
                        + ", so it is refused rather than shrinking below measured need.");
            }
        }

        // The operator-set hard ceiling D1 may never exceed: a raise above it is refused, not capped.
        if (submitted.max() != null && hpaView.userMaxReplicasCeiling() != null
                && submitted.max() > hpaView.userMaxReplicasCeiling()) {
            throw refusal(HpaReason.USER_MAX_RESPECTED, "maxReplicas", "A ceiling of "
                    + submitted.max() + " exceeds the operator-set hard ceiling of "
                    + hpaView.userMaxReplicasCeiling() + " for " + ref.describe()
                    + ", so it is refused rather than capped silently.");
        }

        // A minReplicas below a PDB's minAvailable would leave the budget unsatisfiable.
        Integer pdbFloor = workload.pdbMinAvailable();
        Integer effectiveMin = submitted.min() != null ? submitted.min() : hpaView.minReplicas();
        if (submitted.min() != null && pdbFloor != null && submitted.min() < pdbFloor) {
            throw refusal(HpaReason.PDB_FLOOR_BINDS, "minReplicas", "A floor of " + submitted.min()
                    + " is below the PodDisruptionBudget's minAvailable of " + pdbFloor + " for "
                    + ref.describe() + ", which would make the budget unsatisfiable.");
        }

        // The merged bounds must still be a valid HPA: a submitted min above the (possibly current)
        // max, or vice versa, is refused before enqueue.
        Integer effectiveMax = submitted.max() != null ? submitted.max() : hpaView.maxReplicas();
        if (effectiveMin != null && effectiveMax != null && effectiveMin > effectiveMax) {
            throw invalid("minReplicas", "The resulting bounds for " + ref.describe()
                    + " would be min " + effectiveMin + " / max " + effectiveMax
                    + ", and an HPA cannot have a floor above its ceiling.");
        }

        // A CPU target move needs a Resource: cpu metric to move -- the HPA path only tunes CPU
        // utilisation. A workload whose HPA scales on something else is refused, not reshaped.
        if (submitted.target() != null && !cpuResourceMetricPresent(hpa)) {
            throw refusal(HpaReason.CLAMP_BOUND, "targetUtilizationPct", ref.describe()
                    + "'s HPA has no Resource: cpu metric, so its CPU target cannot be co-adjusted.");
        }
    }

    private static boolean cpuResourceMetricPresent(HorizontalPodAutoscaler hpa) {
        if (hpa.getSpec() == null || hpa.getSpec().getMetrics() == null) {
            return false;
        }
        for (MetricSpec metric : hpa.getSpec().getMetrics()) {
            if (RESOURCE.equals(metric.getType()) && metric.getResource() != null
                    && CPU.equals(metric.getResource().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * An apply inside the policy's cooldown is refused, so the server enforces the same hold the
     * engine publishes as {@code COOLDOWN}. A replica raise under unmet demand is <strong>not</strong>
     * exempt — the cooldown gates the next ceiling move.
     */
    private void requireCooldownElapsed(WorkloadPolicy policy, WorkloadRef ref) {
        Instant lastApplied = policy.lastAppliedAt();
        if (lastApplied == null || policy.cooldownMinutes() <= 0) {
            return;
        }
        Instant now = clock.instant();
        if (lastApplied.isBefore(now.minus(Duration.ofMinutes(policy.cooldownMinutes())))) {
            return;
        }
        Instant endsAt = lastApplied.plus(Duration.ofMinutes(policy.cooldownMinutes()));
        throw refusal(HpaReason.COOLDOWN, "name", "A change was applied to " + ref.describe()
                + " at " + lastApplied + ", and the policy's " + policy.cooldownMinutes()
                + "-minute cooldown runs until " + endsAt + ". This apply is refused and nothing was"
                + " sent to the cluster.");
    }

    // ---------------------------------------------------------------- helpers

    private ClusterAccess open(ClusterAccessProvider provider, ClusterTarget target) {
        try {
            return provider.open(target);
        } catch (ClusterAccessException unreachable) {
            throw new VerticalApplyService.ApplyRefusedException(HttpStatus.FORBIDDEN,
                    "K8S_" + unreachable.reasonCode(), null, unreachable.getMessage());
        }
    }

    private static boolean matches(ClusterWorkloadInventory.Workload candidate, WorkloadRef ref) {
        return candidate.ref().namespace().equals(ref.namespace())
                && candidate.ref().kind().equalsIgnoreCase(ref.kind())
                && candidate.ref().name().equals(ref.name());
    }

    private static VerticalApplyService.ApplyRefusedException invalid(String field, String message) {
        return new VerticalApplyService.ApplyRefusedException(HttpStatus.BAD_REQUEST,
                RefusalDto.INVALID_POLICY, field, message);
    }

    private static VerticalApplyService.ApplyRefusedException invalidRequest(String field,
                                                                             String message) {
        return new VerticalApplyService.ApplyRefusedException(HttpStatus.BAD_REQUEST,
                RefusalDto.INVALID_REQUEST, field, message);
    }

    /**
     * An HPA safety-rule refusal: {@code 400 K8S_INVALID_POLICY} with the {@link HpaReason} named in
     * the detail, so the UI can render the specific rule rather than a generic "something went
     * wrong".
     */
    private static VerticalApplyService.ApplyRefusedException refusal(HpaReason reason, String field,
                                                                      String message) {
        return new VerticalApplyService.ApplyRefusedException(HttpStatus.BAD_REQUEST,
                RefusalDto.INVALID_POLICY, field, reason.name() + ": " + message, reason.name());
    }

    /** The three bounds a body carried, after shape validation. Null means "leave that field alone". */
    private record Submitted(Integer min, Integer max, Integer target) {
    }
}
