package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.workload.ActionTrigger;
import com.xammer.cloud.service.automation.workload.ApplyMethod;
import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.NativeVpaMode;
import com.xammer.cloud.service.automation.workload.QosRules;
import com.xammer.cloud.service.automation.workload.Quantities;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadMode;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyResponseDto;
import com.xammer.cloud.service.automation.workload.api.dto.PolicyResponseDto;
import com.xammer.cloud.service.automation.workload.api.dto.RefusalDto;
import com.xammer.cloud.service.automation.workload.execute.ActionCommand;
import com.xammer.cloud.service.automation.workload.execute.ActionSubmission;
import com.xammer.cloud.service.automation.workload.execute.ContainerApplyTarget;
import com.xammer.cloud.service.automation.workload.execute.DirectInPlaceResizeExecutor;
import com.xammer.cloud.service.automation.workload.execute.ResizeProof;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;
import com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Endpoint 3: apply one recommendation to one workload.
 *
 * <h2>The gates run in code, in the published order</h2>
 *
 * {@code DEMO} / {@code NO_USER_PERMISSION} → {@code READ_ONLY_CONNECTION} →
 * {@code CONTROLLER_NOT_READY} → {@code CLUSTER_ACCESS_NOT_CONFIGURED} /
 * {@code CLUSTER_UNREACHABLE} → {@code GITOPS_MANAGED}, then the body. In code rather than with
 * {@code @PreAuthorize} for a specific reason: a Spring-generated {@code 403} carries no body, and
 * the UI disables a control and explains <em>why</em> from the refusal's {@code code}. A check read
 * off the principal is what lets the answer be {@code {"code":"K8S_NO_USER_PERMISSION", …}} instead
 * of an empty 403 the frontend can only render as "something went wrong".
 *
 * <p>The first gate is {@link AutomationWritePermission}, which is the CloudK8s UI revamp design's
 * decision D2 implemented locally — the same predicate, so the same user cannot be allowed to change
 * automation on one screen and refused on the other. {@code DEMO} <em>is</em> emitted server-side:
 * the signal is the user row's email, which is the only field that identifies the shared demo
 * identities.
 *
 * <p>{@code READ_ONLY_CONNECTION} is {@code "read-write".equalsIgnoreCase(account.getAccessType())},
 * which is this repository's established apply gate ({@code RightsizingApplyService},
 * {@code WasteDeleteService}, {@code AwsClientProvider}).
 *
 * <h2>The submitted numbers are re-validated, never clamped</h2>
 *
 * A body is checked against the <strong>current</strong> cluster state and the resolved policy's
 * floors before any patch: a value below a floor, above its own limit, adding a resource a container
 * does not have, or changing its QoS class is <strong>refused with the rule named</strong>. Silently
 * clamping would turn "the engine would not recommend this" into "we applied something else", which
 * is unreviewable. A floor or a QoS rule blocking an apply is the product working correctly.
 *
 * <h2>What makes the cooldown real</h2>
 *
 * A non-dry-run apply that changed any pod, or left one that may still change, upserts
 * {@code vertical_workload_policy.last_applied_at} ({@link ResizeProof#anyPodChanged()}). Until
 * this existed, the engine's cooldown guard had nothing to read and so never fired. An apply inside
 * that cooldown is now refused here as well, as are the engine's other holds (single replica,
 * rollout, lock, HPA-managed resource), so none of them depends on the UI.
 *
 * <h2>An apply never changes the policy</h2>
 *
 * The policy an apply resolves, and records when it has to create a row, is the one endpoint 1
 * reported: a stored row, or the environment's configured defaults. Nothing here requires
 * {@code AUTO}: this endpoint is the one-time, user-initiated apply, and it is the only caller of the
 * executor.
 */
@Service
public class VerticalApplyService {

    private static final Logger logger = LoggerFactory.getLogger(VerticalApplyService.class);

    /**
     * How long a submitted command stays applicable.
     *
     * <p>Short on purpose: the numbers in the body were computed against a measurement window that
     * ends at the read, and a command applied long afterwards writes a request derived from
     * something that is no longer true. The executor refuses a command past this instant rather than
     * applying it late.
     */
    static final Duration COMMAND_VALIDITY = Duration.ofMinutes(5);

    private final WorkloadAutoscalingProperties properties;
    private final AutomationWritePermission writePermission;
    private final List<ClusterAccessProvider> accessProviders;
    private final ClusterWorkloadInventory inventory;
    private final UpstreamVpaReader vpaReader;
    private final DirectInPlaceResizeExecutor executor;
    private final WorkloadPolicyService policyService;
    private final WorkloadActionRecorder actionRecorder;
    private final VerticalMetricsProperties metricsProperties;
    private final Clock clock;
    /**
     * The workloads a real apply is running on right now. In memory, so it holds per backend
     * instance; with more than one instance behind a balancer this would need a shared lock.
     */
    private final Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Autowired
    public VerticalApplyService(WorkloadAutoscalingProperties properties,
                               AutomationWritePermission writePermission,
                               List<ClusterAccessProvider> accessProviders,
                               ClusterWorkloadInventory inventory,
                               UpstreamVpaReader vpaReader,
                               DirectInPlaceResizeExecutor executor,
                               WorkloadPolicyService policyService,
                               WorkloadActionRecorder actionRecorder,
                               VerticalMetricsProperties metricsProperties) {
        this(properties, writePermission, accessProviders, inventory, vpaReader, executor, policyService,
                actionRecorder, metricsProperties, Clock.systemUTC());
    }

    VerticalApplyService(WorkloadAutoscalingProperties properties,
                         AutomationWritePermission writePermission,
                         List<ClusterAccessProvider> accessProviders,
                         ClusterWorkloadInventory inventory,
                         UpstreamVpaReader vpaReader,
                         DirectInPlaceResizeExecutor executor,
                         WorkloadPolicyService policyService,
                         WorkloadActionRecorder actionRecorder,
                         VerticalMetricsProperties metricsProperties,
                         Clock clock) {
        this.properties = properties;
        this.writePermission = writePermission;
        this.inventory = inventory;
        this.vpaReader = vpaReader;
        this.executor = executor;
        this.policyService = policyService;
        this.actionRecorder = actionRecorder;
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
    public ApplyResponseDto apply(ClientUserDetails principal, CloudAccount account,
                                  ClusterTarget target, WorkloadRef ref, ApplyRequestDto request) {
        return apply(principal, account, target, ref, request, ActionTrigger.MANUAL);
    }

    /**
     * The same apply, naming who initiated it.
     *
     * <p>The five-argument overload defaults the trigger to {@link ActionTrigger#MANUAL}, so
     * {@code VerticalAutomationController} (endpoint 3) keeps calling the existing signature and
     * records a {@code MANUAL} action, while FEAT-002's scheduled loop passes
     * {@link ActionTrigger#AUTO} here. <strong>The gate order is identical for both</strong>: the
     * trigger only labels the recorded action, it changes no check.
     *
     * @param trigger who initiated the apply; the recorded action's {@code trigger_source}
     */
    public ApplyResponseDto apply(ClientUserDetails principal, CloudAccount account,
                                  ClusterTarget target, WorkloadRef ref, ApplyRequestDto request,
                                  ActionTrigger trigger) {
        requirePermission(principal, account);
        requireWriteConnection(account);
        requireControllerReady();

        ApplyMethod method = requireApplyMethod(request);

        ClusterAccessProvider provider = accessProviders.stream()
                .filter(candidate -> candidate.supports(target))
                .findFirst()
                .orElseThrow(() -> new ApplyRefusedException(HttpStatus.FORBIDDEN,
                        ReasonCode.CLUSTER_ACCESS_NOT_CONFIGURED.refusalCode(), null,
                        "XamOps has no credential path into this cluster, so nothing can be applied."
                                + " Create an EKS access entry for the role registered for this"
                                + " account."));

        try (ClusterAccess access = open(provider, target)) {
            requireGitOpsSafe(method, ref);
            List<ApplyRequestDto.Container> submitted = requireBody(request, ref);

            ClusterWorkloadInventory.Workload workload = inventory.read(access.client()).workloads()
                    .stream()
                    .filter(candidate -> matches(candidate, ref))
                    .findFirst()
                    .orElseThrow(() -> new ApplyRefusedException(HttpStatus.BAD_REQUEST,
                            RefusalDto.INVALID_POLICY, "name",
                            "This cluster has no " + ref.kind() + " " + ref.describe()
                                    + " that XamOps can rightsize, so there is nothing to apply."));

            boolean dryRun = Boolean.TRUE.equals(request.dryRun());
            // One real apply per workload at a time (review-2 SF5), held from the cooldown check to
            // the cooldown stamp: a second request that read last_applied_at while the first was
            // still rolling out would otherwise start a second rollout on the same pods. A dry run
            // sends nothing, so it takes no lock.
            String lock = lockKey(target, workload.ref());
            if (!dryRun && !inFlight.add(lock)) {
                throw new ApplyRefusedException(HttpStatus.CONFLICT, RefusalDto.APPLY_IN_PROGRESS, null,
                        "Another change to " + workload.ref().describe() + " is still being applied."
                                + " Nothing was sent; wait for it to finish, then refresh.");
            }
            try {
                return applyLocked(target, workload, submitted, method, dryRun, access, trigger);
            } finally {
                if (!dryRun) {
                    inFlight.remove(lock);
                }
            }
        }
    }

    private ApplyResponseDto applyLocked(ClusterTarget target,
                                         ClusterWorkloadInventory.Workload workload,
                                         List<ApplyRequestDto.Container> submitted, ApplyMethod method,
                                         boolean dryRun, ClusterAccess access, ActionTrigger trigger) {
        // The no-row case resolves to the environment's configured defaults -- the policy the
        // read endpoint reported -- so the row recordApplied creates carries that policy and
        // not the bare Java defaults, whose mode is OFF.
        WorkloadPolicy policy = policyService.resolve(target.clusterConfigId(), workload.ref(),
                metricsProperties.defaultsPolicy());
        // Review-2 item 15: the engine skips a workload whose policy is OFF and publishes nothing for
        // it, so a direct call must not resize it either. Dry runs too, so they predict the apply.
        if (policy.mode() == WorkloadMode.OFF) {
            throw invalid("name", "POLICY_OFF: rightsizing is off for " + workload.ref().describe()
                    + ", so XamOps does not change it. Nothing was sent to the cluster.");
        }
        // VPA-guard-first (design D1.3b): before re-validation, which could otherwise surface a
        // floor/QoS refusal instead of the more actionable VPA ownership. A mutating or unreadable
        // native VPA is refused for dry runs and real applies alike, before the executor is reached.
        requireNativeVpaClear(access, workload.ref());
        List<ContainerApplyTarget> targets = revalidate(submitted, workload, policy);
        // The engine's holds, dry runs included, so a dry run predicts the real apply.
        requireEngineHoldsClear(workload, policy, submitted);
        if (!isMemoryOnlyIncrease(submitted, workload)) {
            requireCooldownElapsed(policy, workload.ref());
        }

        ActionCommand command = new ActionCommand(UUID.randomUUID().toString(), target,
                workload.ref(), targets, method, dryRun, null,
                clock.instant().plus(COMMAND_VALIDITY));

        ActionSubmission submission = executor.submit(command, access, workload);
        ResizeProof proof = executor.proof(command.correlationId()).orElse(null);

        // The cooldown starts whenever a pod changed or may still change (review-2 SF4): a
        // partial rollout, a restart or a lost readiness all leave pods at the new size, and a
        // declined pod XamOps could not prove back where it was may still be resized by the
        // kubelet. A declined pod that was put back and proved back changed nothing, so it
        // starts no cooldown and the user can try again once there is room.
        if (!command.dryRun() && proof != null && proof.anyPodChanged()) {
            Instant appliedAt = clock.instant();
            policyService.recordApplied(target.clientId(), target.clusterConfigId(),
                    workload.ref(), policy, appliedAt);
            // The durable audit row, written from this same human path with the trigger labelling
            // who asked. A dry run or an apply that changed no pod records nothing, exactly as the
            // cooldown stamp above does.
            actionRecorder.record(target.clientId(), target, workload.ref(), trigger,
                    command.dryRun(), targets, proof, appliedAt);
        }

        logger.info("Vertical apply {} for {} on {}: state {} outcome {}",
                command.correlationId(), workload.ref().describe(), target.describe(),
                submission.state(), proof == null ? "unknown" : proof.outcome());

        return ApplyResponseDto.of(command.correlationId(), submission.state().name(),
                command.dryRun(), method.name(), proof);
    }

    /**
     * Endpoint 2 (mode only): set one workload's policy mode to {@code OFF}, {@code RECOMMEND} or
     * {@code AUTO}.
     *
     * <h2>A mode change is an automation change, so it runs the apply gates</h2>
     *
     * The first three gates of {@link #apply} run here, in the same order and through the same
     * code — {@link #requirePermission} ({@link AutomationWritePermission}: demo, no-permission
     * including a missing TAB_GRANT, a Login-As session, a non-XamOps role), then
     * {@link #requireWriteConnection} (read-only connection), then {@link #requireControllerReady}.
     * Setting {@code AUTO} therefore requires the <strong>same write permission as applying</strong>:
     * a caller who could not apply a change cannot arm one to run unattended either. The cluster is
     * never opened and no pod is touched; a mode change is a database write, so the per-workload
     * holds, the cooldown and the body re-validation that only make sense for a resize are not run.
     *
     * <p>The new mode is validated against {@link WorkloadMode}; anything else is a
     * {@code 400 K8S_INVALID_POLICY} naming the {@code mode} field, the same refusal shape a bad
     * apply body gets. The write is {@link WorkloadPolicyService#setMode}, which changes only the
     * {@code mode} column and leaves every other policy field at its stored or default value. The
     * change is recorded as a {@link WorkloadActionRecorder#OUTCOME_POLICY_CHANGE} audit row, so the
     * activity history shows who switched a workload to Automatic and when.
     *
     * @param modeValue the requested mode, case-insensitive; {@code OFF | RECOMMEND | AUTO}
     * @return the saved mode and the policy source, so endpoint 1's state is reflected to the caller
     */
    public PolicyResponseDto setMode(ClientUserDetails principal, CloudAccount account,
                                     ClusterTarget target, WorkloadRef ref, String modeValue) {
        return setMode(principal, account, target, ref, modeValue, ActionTrigger.MANUAL);
    }

    /**
     * The same mode change, naming who initiated it. The trigger only labels the recorded action;
     * the gate order is identical for every caller.
     */
    public PolicyResponseDto setMode(ClientUserDetails principal, CloudAccount account,
                                     ClusterTarget target, WorkloadRef ref, String modeValue,
                                     ActionTrigger trigger) {
        // The automation-change gates, in the published order, through the same checks as an apply.
        requirePermission(principal, account);
        requireWriteConnection(account);
        requireControllerReady();

        WorkloadMode newMode = requireMode(modeValue);

        // The policy endpoint 1 reported for a workload with no row: the environment's configured
        // defaults, so a first mode write records the operator's own view and not the bare OFF.
        WorkloadPolicy inForce = policyService.resolve(target.clusterConfigId(), ref,
                metricsProperties.defaultsPolicy());
        WorkloadMode previous = policyService.setMode(target.clientId(), target.clusterConfigId(), ref,
                newMode, inForce);

        Instant at = clock.instant();
        // The durable audit row, written from this same human path with the trigger labelling who
        // asked. Recorded even when the mode did not change, so a deliberate re-confirm is auditable.
        actionRecorder.recordPolicyChange(target.clientId(), target, ref, trigger,
                previous.name(), newMode.name(), at);

        logger.info("Vertical mode change for {} on {}: {} -> {} by {}", ref.describe(),
                target.describe(), previous, newMode,
                principal == null ? "unknown" : principal.getId());

        // A mode write always leaves a stored row, so endpoint 1's policySource is WORKLOAD_ROW.
        return new PolicyResponseDto(newMode.name(), WorkloadPolicy.Source.WORKLOAD_ROW.name());
    }

    /** The requested mode as a {@link WorkloadMode}, else a 400 naming the {@code mode} field. */
    private static WorkloadMode requireMode(String modeValue) {
        if (modeValue == null || modeValue.isBlank()) {
            throw new ApplyRefusedException(HttpStatus.BAD_REQUEST, RefusalDto.INVALID_POLICY, "mode",
                    "A mode change needs a mode: one of OFF, RECOMMEND or AUTO.");
        }
        try {
            return WorkloadMode.valueOf(modeValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ApplyRefusedException(HttpStatus.BAD_REQUEST, RefusalDto.INVALID_POLICY, "mode",
                    "mode must be OFF, RECOMMEND or AUTO, got '" + modeValue + "'.");
        }
    }

    /** The registered cluster and the workload's identity: what two applies must not share at once. */
    private static String lockKey(ClusterTarget target, WorkloadRef ref) {
        return target.clusterConfigId() + "|" + ref.namespace() + "|"
                + ref.kind().toLowerCase(Locale.ROOT) + "|" + ref.name();
    }

    /**
     * The caller-level write gates this endpoint would refuse with, as shared gate codes without the
     * {@code K8S_} prefix, in the published order: {@code DEMO} or {@code NO_USER_PERMISSION}
     * (which covers a Login-As session), then {@code READ_ONLY_CONNECTION}.
     *
     * <p>Endpoint 1 reports these in {@code gateReasons} so the UI can grey Apply and say why
     * before anybody clicks. They are computed by the same two predicates {@link #apply} enforces,
     * so the read cannot promise an apply the write would refuse. Entitlement is not repeated here:
     * the controller has already refused an unentitled caller, and {@code K8S_NO_ACCOUNT_ACCESS} is
     * not a gate code. The codes of {@link #callerWriteGates}.
     */
    public List<String> writeGates(ClientUserDetails principal, CloudAccount account) {
        return callerWriteGates(principal, account).codes();
    }

    /**
     * {@link #writeGates} with the permission rule's {@link AutomationWritePermission.Detail} for
     * each gate that has one — the same value {@link #apply} puts in a refusal's {@code detail} — so
     * endpoint 1 can say <em>which</em> permission rule applies and the UI can show that rule's
     * sentence and next step before anybody clicks.
     */
    public WriteGates callerWriteGates(ClientUserDetails principal, CloudAccount account) {
        List<String> gates = new ArrayList<>();
        Map<String, String> details = new LinkedHashMap<>();
        if (principal == null) {
            gates.add(ReasonCode.NO_USER_PERMISSION.name());
            details.put(ReasonCode.NO_USER_PERMISSION.name(), AutomationWritePermission.Detail.NO_PRINCIPAL);
        } else {
            AutomationWritePermission.Decision decision = writePermission.decide(principal, account);
            if (!decision.permitted() && decision.reason() != null) {
                gates.add(decision.reason().name());
                if (decision.detail() != null) {
                    details.put(decision.reason().name(), decision.detail());
                }
            }
        }
        if (account == null || !"read-write".equalsIgnoreCase(account.getAccessType())) {
            gates.add(ReasonCode.READ_ONLY_CONNECTION.name());
        }
        return new WriteGates(gates, details);
    }

    /**
     * The caller's write gates in the published order, and the permission rule's detail per gate.
     *
     * @param codes   shared gate codes without the {@code K8S_} prefix
     * @param details gate code to {@link AutomationWritePermission.Detail}, for the gates that have one
     */
    public record WriteGates(List<String> codes, Map<String, String> details) {

        public WriteGates {
            codes = codes == null ? List.of() : List.copyOf(codes);
            details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }

    // ---------------------------------------------------------------- gates

    /**
     * The user gate, delegated to {@link AutomationWritePermission} so this and the CloudK8s UI
     * revamp branch answer the same question the same way.
     *
     * <p>Note which refusals it can produce: {@code K8S_DEMO} for the shared demo identity,
     * {@code K8S_NO_ACCOUNT_ACCESS} for an unentitled account, and {@code K8S_NO_USER_PERMISSION}
     * for an impersonated session, a non-XamOps role or a restricted user without the automation
     * grant. An owner demoing through <em>Login As</em> is refused by the impersonation rule, so a
     * demo has to be a direct login.
     */
    private void requirePermission(ClientUserDetails principal, CloudAccount account) {
        if (principal == null) {
            // Reachable only if this path were ever left out of SecurityConfig's authenticated
            // catch-all; failing closed here means the guard does not depend on that staying true.
            throw new ApplyRefusedException(HttpStatus.UNAUTHORIZED, RefusalDto.UNAUTHENTICATED, null,
                    "Sign in again to apply automation changes.");
        }
        AutomationWritePermission.Decision decision = writePermission.decide(principal, account);
        if (!decision.permitted()) {
            logger.info("Vertical apply refused for user {}: {} ({})", principal.getId(),
                    decision.refusalCode(), decision.detail());
            // The detail is on the wire as well as in the log: one K8S_NO_USER_PERMISSION covers a
            // missing tab grant, a Login-As session, a BillOps role and a lost principal, and each
            // has a different remedy.
            throw new ApplyRefusedException(HttpStatus.FORBIDDEN, decision.refusalCode(), null,
                    decision.message(), decision.detail());
        }
    }

    private static void requireWriteConnection(CloudAccount account) {
        if (account == null || !"read-write".equalsIgnoreCase(account.getAccessType())) {
            throw new ApplyRefusedException(HttpStatus.FORBIDDEN,
                    ReasonCode.READ_ONLY_CONNECTION.refusalCode(), null,
                    "This AWS account is connected read-only, so XamOps will not change anything in"
                            + " it. Reconnect it with read-write access to apply recommendations.");
        }
    }

    private void requireControllerReady() {
        if (!properties.isEnabled() || !properties.isReady() || !properties.isExecutorPresent()) {
            throw new ApplyRefusedException(HttpStatus.FORBIDDEN,
                    ReasonCode.CONTROLLER_NOT_READY.refusalCode(), null,
                    "Workload vertical rightsizing has not been enabled and validated in this"
                            + " environment, so nothing can be applied.");
        }
    }

    private static ApplyMethod requireApplyMethod(ApplyRequestDto request) {
        String declared = request == null ? null : request.applyMethod();
        if (declared == null || declared.isBlank()) {
            return ApplyMethod.IN_PLACE;
        }
        try {
            return ApplyMethod.valueOf(declared.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ApplyRefusedException(HttpStatus.BAD_REQUEST, RefusalDto.INVALID_POLICY,
                    "applyMethod", "applyMethod must be IN_PLACE or IN_PLACE_AND_PERSIST, got '"
                    + declared + "'.");
        }
    }

    /**
     * {@code IN_PLACE_AND_PERSIST} is refused.
     *
     * <p>Persisting a change means patching the workload's <em>template</em>, which restarts every
     * pod and is exactly what a GitOps controller reverts minutes later. XamOps has no GitOps
     * detection yet, so it cannot prove a given workload is not Argo- or Flux-owned, and it holds no
     * role that could write a template. Refusing with the published code says so instead of
     * pretending the method is available.
     */
    private static void requireGitOpsSafe(ApplyMethod method, WorkloadRef ref) {
        if (method == ApplyMethod.IN_PLACE_AND_PERSIST) {
            throw new ApplyRefusedException(HttpStatus.FORBIDDEN,
                    ReasonCode.GITOPS_MANAGED.refusalCode(), null,
                    "Template persistence is not available for " + ref.describe()
                            + ": XamOps does not patch a workload template, and whether this"
                            + " workload's template is owned by Argo CD or Flux cannot be"
                            + " established. Use IN_PLACE, which resizes the running pods without"
                            + " restarting them.");
        }
    }

    /**
     * The engine's per-workload holds, re-checked here so that a direct call cannot apply what the
     * engine refuses to touch (review S1). Before this, they were enforced only by the UI choosing
     * what to submit.
     *
     * <p>Same predicates as {@code WorkloadAutoscalingEngine}'s gate and HPA reconciliation, and the
     * engine's own codes at the head of the message: {@code SINGLE_REPLICA} (when the policy
     * protects single-replica workloads), {@code ROLLOUT_IN_PROGRESS}, {@code LOCKED}, and
     * {@code HPA_CPU_MANAGED} / {@code HPA_MEMORY_MANAGED} when a submitted value would move a
     * resource the workload's HPA scales on. Refused with {@code K8S_INVALID_POLICY}, the way a floor
     * or a protected namespace is, and applied to dry runs too.
     */
    private static void requireEngineHoldsClear(ClusterWorkloadInventory.Workload workload,
                                                WorkloadPolicy policy,
                                                List<ApplyRequestDto.Container> submitted) {
        WorkloadRef ref = workload.ref();
        if (workload.replicas() <= 1 && policy.protectSingleReplica()) {
            throw invalid("name", "SINGLE_REPLICA: " + ref.describe() + " runs "
                    + workload.replicas() + " replica, and its policy protects single-replica"
                    + " workloads: resizing the only pod leaves nothing to fall back on.");
        }
        if (workload.rolloutInProgress()) {
            throw invalid("name", "ROLLOUT_IN_PROGRESS: " + ref.describe() + " is rolling out, so"
                    + " its pods belong to two templates. Apply after the rollout has finished.");
        }
        if (workload.lockAnnotationActive()) {
            throw invalid("name", "LOCKED: " + ref.describe() + " carries "
                    + WorkloadPolicyDefaults.LOCK_ANNOTATION + "="
                    + WorkloadPolicyDefaults.LOCK_ANNOTATION_ACTIVE
                    + ", which suspends automation for it.");
        }
        HpaView hpa = workload.hpa() != null && workload.hpa().targets(ref) ? workload.hpa() : null;
        if (hpa == null) {
            return;
        }
        for (int i = 0; i < submitted.size(); i++) {
            ApplyRequestDto.Container container = submitted.get(i);
            ResourceEnvelope current = workload.containers().stream()
                    .filter(candidate -> candidate.name().equals(container.name()))
                    .map(ClusterWorkloadInventory.ContainerSpec::current)
                    .findFirst()
                    .orElse(ResourceEnvelope.UNSET);
            String field = "containers[" + i + "]";
            if (hpa.cpuResourceMetricPresent()
                    && (moves(container.cpuRequestM(), current.cpuRequestMillis())
                    || moves(container.cpuLimitM(), current.cpuLimitMillis()))) {
                throw invalid(field + ".cpuRequestM", "HPA_CPU_MANAGED: the HPA " + hpa.name()
                        + " scales " + ref.describe() + " on CPU utilisation, which is usage divided"
                        + " by the request, so moving the CPU request changes how many replicas it"
                        + " runs. CPU is left alone; memory can still be applied.");
            }
            Long memRequestBytes = container.memRequestMi() == null
                    ? null : Quantities.miToBytes(container.memRequestMi());
            Long memLimitBytes = container.memLimitMi() == null
                    ? null : Quantities.miToBytes(container.memLimitMi());
            if (hpa.memResourceMetricPresent()
                    && (moves(memRequestBytes, current.memRequestBytes())
                    || moves(memLimitBytes, current.memLimitBytes()))) {
                throw invalid(field + ".memRequestMi", "HPA_MEMORY_MANAGED: the HPA " + hpa.name()
                        + " scales " + ref.describe() + " on memory utilisation, so moving the"
                        + " memory request changes how many replicas it runs. Memory is left"
                        + " alone; CPU can still be applied.");
            }
        }
    }

    /**
     * The write-path VPA coexistence guard (design §D1.3b): XamOps must never write pod requests on
     * a workload a mutating native VPA owns. Reuses the same {@link UpstreamVpaReader} and the same
     * open {@link ClusterAccess} client the engine read surface uses — no second interpretation of
     * {@code updateMode}, no new RBAC.
     *
     * <p>Fail closed: an unreadable VPA ({@code 403}/{@code 404}) refuses the apply with
     * {@code VPA_NOT_READABLE}, a mutating VPA with {@code VPA_MANAGED}. {@code NONE}/{@code OFF}
     * fall through. Refused with the existing {@code K8S_INVALID_POLICY} shape, the rule named at the
     * head of the message, exactly like the engine's other holds. Runs for dry runs too, so a dry
     * run returns the refusal instead of {@code ACCEPTED/DRY_RUN}.
     */
    private void requireNativeVpaClear(ClusterAccess access, WorkloadRef ref) {
        NativeVpaMode mode = vpaReader.read(access.client()).modeFor(ref);
        if (mode == NativeVpaMode.UNREADABLE) {
            throw invalid("name", "VPA_NOT_READABLE: XamOps could not read whether a native VPA owns "
                    + ref.describe() + ", so it will not change it. Nothing was sent to the cluster.");
        }
        if (mode.refusesVertical()) {
            throw invalid("name", "VPA_MANAGED: a native VerticalPodAutoscaler in a mutating update"
                    + " mode owns " + ref.describe() + ", so XamOps will not change its pod resources."
                    + " Nothing was sent to the cluster.");
        }
    }

    /** A submitted value moves a resource when it is present and differs from the current one. */
    private static boolean moves(Number submitted, Number current) {
        return submitted != null && (current == null || submitted.longValue() != current.longValue());
    }

    /**
     * An apply inside the policy's cooldown is refused, so the server enforces the same hold the
     * engine publishes as {@code COOLDOWN} and the UI disables Apply for.
     *
     * <p>Same boundary as the engine's own guard: the hold lasts while {@code last_applied_at} is
     * not older than {@code cooldown_minutes}. Dry runs are held too, so a dry run predicts the real
     * apply. Refused with {@code K8S_INVALID_POLICY} and the rule named in the message, the way a
     * floor or a protected namespace is. A memory-only increase is not held
     * ({@link #isMemoryOnlyIncrease}), as in the engine.
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
        throw invalid("name", "COOLDOWN: a change was applied to " + ref.describe() + " at "
                + lastApplied + ", and the policy's " + policy.cooldownMinutes() + "-minute cooldown"
                + " runs until " + endsAt + ". This apply is refused and nothing was sent to the"
                + " cluster.");
    }

    /**
     * Whether every submitted field is a memory increase over the container's current value, with no
     * CPU field at all — the one change the engine publishes inside a cooldown, for a container that
     * OOM-killed after the last apply (review-2 SF1).
     *
     * <p>The cooldown damps the engine arguing with its own write. An OOM kill after a decrease is
     * not that: refusing the raise would leave the container being killed for the rest of the hour
     * while the screen promises "XamOps will only raise its memory, even right after a change". A
     * raise cannot cause an OOM kill, and CPU is never exempt. The floors, QoS and limit checks in
     * {@link #revalidate} still apply.
     */
    static boolean isMemoryOnlyIncrease(List<ApplyRequestDto.Container> submitted,
                                        ClusterWorkloadInventory.Workload workload) {
        if (submitted == null || submitted.isEmpty()) {
            return false;
        }
        for (ApplyRequestDto.Container container : submitted) {
            if (container.cpuRequestM() != null || container.cpuLimitM() != null) {
                return false;
            }
            if (container.memRequestMi() == null && container.memLimitMi() == null) {
                return false;
            }
            ClusterWorkloadInventory.ContainerSpec spec = workload.containers().stream()
                    .filter(candidate -> candidate.name().equals(container.name()))
                    .findFirst()
                    .orElse(null);
            if (spec == null) {
                return false;
            }
            // When the live pods disagree, a raise is judged against the smallest size a live pod
            // runs with, the same basis the engine's OOM guard and its cooldown exemption use.
            ResourceEnvelope live = spec.smallestLive();
            if (!raises(container.memRequestMi(), smaller(spec.current().memRequestBytes(),
                    live == null ? null : live.memRequestBytes()))
                    || !raises(container.memLimitMi(), smaller(spec.current().memLimitBytes(),
                    live == null ? null : live.memLimitBytes()))) {
                return false;
            }
        }
        return true;
    }

    private static Long smaller(Long a, Long b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.min(a, b);
    }

    /** A submitted MiB value that is absent, or strictly above a current value that exists. */
    private static boolean raises(Long submittedMi, Long currentBytes) {
        if (submittedMi == null) {
            return true;
        }
        return currentBytes != null && Quantities.miToBytes(submittedMi) > currentBytes;
    }

    private ClusterAccess open(ClusterAccessProvider provider, ClusterTarget target) {
        try {
            return provider.open(target);
        } catch (ClusterAccessException unreachable) {
            throw new ApplyRefusedException(HttpStatus.FORBIDDEN,
                    "K8S_" + unreachable.reasonCode(), null, unreachable.getMessage());
        }
    }

    // ---------------------------------------------------------------- the body

    /** Shape only: everything that can be judged without reading the cluster. */
    private static List<ApplyRequestDto.Container> requireBody(ApplyRequestDto request, WorkloadRef ref) {
        if (request == null) {
            throw invalid("containers", "An apply needs a body naming the containers to change.");
        }
        if (request.dryRun() == null) {
            // A primitive boolean would have defaulted this to false, which is the real-apply case.
            throw invalid("dryRun", "dryRun is required on every apply, and must be true or false."
                    + " It is echoed in the response and on the audit record, so it is never"
                    + " inferred.");
        }
        if (request.containers() == null || request.containers().isEmpty()) {
            throw invalid("containers", "An apply with no container targets would change nothing.");
        }
        if (WorkloadPolicyDefaults.PROTECTED_NAMESPACES.contains(
                ref.namespace().toLowerCase(Locale.ROOT))) {
            throw invalid("namespace", "PROTECTED_NAMESPACE: " + ref.namespace() + " is never"
                    + " rightsized by XamOps. That set is a code constant, not a policy a request"
                    + " can override.");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < request.containers().size(); i++) {
            ApplyRequestDto.Container container = request.containers().get(i);
            String field = "containers[" + i + "]";
            if (container == null || container.name() == null || container.name().isBlank()) {
                throw invalid(field + ".name", "Each container target must name its container.");
            }
            if (!seen.add(container.name())) {
                throw invalid(field + ".name", "Container " + container.name() + " appears twice."
                        + " One metric applied to several containers is the mistake this engine"
                        + " exists to prevent, and two targets for one container is the same"
                        + " mistake from the other direction.");
            }
            if (container.isEmpty()) {
                throw invalid(field, "Container " + container.name() + " names no resource to change."
                        + " A null field means 'leave that resource alone', so a target of all nulls"
                        + " is a no-op.");
            }
            requirePositive(field + ".cpuRequestM", container.cpuRequestM());
            requirePositive(field + ".cpuLimitM", container.cpuLimitM());
            requirePositive(field + ".memRequestMi", container.memRequestMi());
            requirePositive(field + ".memLimitMi", container.memLimitMi());
        }
        return request.containers();
    }

    private static void requirePositive(String field, Number value) {
        if (value != null && value.longValue() <= 0) {
            throw invalid(field, field + " must be a positive whole number, got " + value
                    + ". Kubernetes cannot express a request of zero, and a recommendation of zero is"
                    + " the measured failure this engine exists to prevent.");
        }
    }

    // ---------------------------------------------------------------- re-validation

    /**
     * Checks every submitted number against the container's <strong>current</strong> envelope and the
     * resolved policy's floors, and converts it to the executor's units.
     *
     * <p>Refusals, never clamps. A clamp would apply a number nobody submitted and nobody
     * recommended.
     */
    private static List<ContainerApplyTarget> revalidate(List<ApplyRequestDto.Container> submitted,
                                                         ClusterWorkloadInventory.Workload workload,
                                                         WorkloadPolicy policy) {
        List<ContainerApplyTarget> targets = new ArrayList<>();
        for (int i = 0; i < submitted.size(); i++) {
            ApplyRequestDto.Container container = submitted.get(i);
            String field = "containers[" + i + "]";
            ClusterWorkloadInventory.ContainerSpec spec = workload.containers().stream()
                    .filter(candidate -> candidate.name().equals(container.name()))
                    .findFirst()
                    .orElseThrow(() -> invalid(field + ".name", "Container " + container.name()
                            + " is not part of " + workload.ref().describe() + " right now."));
            ResourceEnvelope current = spec.current();

            Long memRequestBytes = container.memRequestMi() == null
                    ? null : Quantities.miToBytes(container.memRequestMi());
            Long memLimitBytes = container.memLimitMi() == null
                    ? null : Quantities.miToBytes(container.memLimitMi());

            requireNotBelowFloor(field + ".cpuRequestM", container.cpuRequestM(),
                    current.cpuRequestMillis(), policy.cpuFloorMillis(), "m CPU");
            requireNotBelowFloor(field + ".memRequestMi", memRequestBytes,
                    current.memRequestBytes(), policy.memFloorBytes(), " bytes of memory");

            ContainerApplyTarget target;
            try {
                target = new ContainerApplyTarget(container.name(), container.cpuRequestM(),
                        container.cpuLimitM(), memRequestBytes, memLimitBytes);
            } catch (IllegalArgumentException unsatisfiable) {
                // ContainerApplyTarget rejects a request above its own limit and a non-positive
                // value at construction, so an unsatisfiable command cannot become durable.
                throw invalid(field, unsatisfiable.getMessage());
            }

            ResourceEnvelope wanted = DirectInPlaceResizeExecutor.merge(current, target);
            if (QosRules.shapeOf(current) != QosRules.shapeOf(wanted)) {
                throw invalid(field, "Container " + container.name() + ": this would change the"
                        + " container's QoS shape from " + QosRules.shapeOf(current) + " to "
                        + QosRules.shapeOf(wanted) + ". QoS class is fixed when a pod is created and"
                        + " the in-place resize subresource rejects a change to it.");
            }
            if (wanted.cpuRequestMillis() != null && wanted.cpuLimitMillis() != null
                    && wanted.cpuRequestMillis() > wanted.cpuLimitMillis()) {
                throw invalid(field + ".cpuRequestM", "Container " + container.name()
                        + ": a CPU request of " + wanted.cpuRequestMillis() + "m would exceed its"
                        + " limit of " + wanted.cpuLimitMillis() + "m.");
            }
            if (wanted.memRequestBytes() != null && wanted.memLimitBytes() != null
                    && wanted.memRequestBytes() > wanted.memLimitBytes()) {
                throw invalid(field + ".memRequestMi", "Container " + container.name()
                        + ": a memory request of " + Quantities.formatMem(wanted.memRequestBytes())
                        + " would exceed its limit of " + Quantities.formatMem(wanted.memLimitBytes())
                        + ".");
            }
            targets.add(target);
        }
        return List.copyOf(targets);
    }

    /**
     * A floor clamps <strong>decreases only</strong>, and a container already below its floor is
     * never raised to reach it. So a submitted value below the floor is refused when it would take
     * the container <em>down</em> there, and accepted when it is at or above where the container
     * already sits.
     */
    private static void requireNotBelowFloor(String field, Number submitted, Number current,
                                             long floor, String unit) {
        if (submitted == null || submitted.longValue() >= floor) {
            return;
        }
        if (current != null && submitted.longValue() >= current.longValue()) {
            return;         // already below the floor; this does not take it any lower
        }
        throw invalid(field, "The engine's floor is " + floor + unit + " and " + submitted
                + " is below it, so this decrease is refused rather than clamped to the floor."
                + " Floors exist because the measured reference implementation recommended 1m CPU"
                + " and 1Mi of memory.");
    }

    private static boolean matches(ClusterWorkloadInventory.Workload candidate, WorkloadRef ref) {
        return candidate.ref().namespace().equals(ref.namespace())
                && candidate.ref().kind().equalsIgnoreCase(ref.kind())
                && candidate.ref().name().equals(ref.name());
    }

    private static ApplyRefusedException invalid(String field, String message) {
        return new ApplyRefusedException(HttpStatus.BAD_REQUEST, RefusalDto.INVALID_POLICY, field,
                message);
    }

    /**
     * A typed refusal carrying the contract's {@code code} and, on a {@code 400}, the offending
     * {@code field}.
     *
     * <p>Handled by a controller-local {@code @ExceptionHandler} rather than left to
     * {@code GlobalExceptionHandler}, whose {@code @ExceptionHandler(Exception.class)} catch-all
     * would otherwise turn every one of these into a {@code 500 K8S_INTERNAL} — telling the caller
     * the server is broken when a safety rule declined.
     */
    public static class ApplyRefusedException extends RuntimeException {

        private final HttpStatus status;
        private final String code;
        private final String field;
        private final String detail;

        public ApplyRefusedException(HttpStatus status, String code, String field, String message) {
            this(status, code, field, message, null);
        }

        /** @param detail the rule's finer name ({@link AutomationWritePermission.Detail}), or null */
        public ApplyRefusedException(HttpStatus status, String code, String field, String message,
                                     String detail) {
            super(message);
            this.status = status;
            this.code = code;
            this.field = field;
            this.detail = detail;
        }

        public HttpStatus status() {
            return status;
        }

        public String code() {
            return code;
        }

        /** The offending request field on a {@code 400}, else null. */
        public String field() {
            return field;
        }

        /** Which permission rule refused, on a caller-level refusal, else null. */
        public String detail() {
            return detail;
        }

        public RefusalDto body() {
            return new RefusalDto(code, getMessage(), field, detail);
        }
    }
}
