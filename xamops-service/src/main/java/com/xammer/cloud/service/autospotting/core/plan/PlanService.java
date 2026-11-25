package com.xammer.cloud.service.autospotting.core.plan;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanOptions;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plan creation (design-core.md §7.1-§7.4; plan-phase-11.md task 11.2). A plan is computed through
 * the provider's read-only view and inserted OPEN with a 15-minute expiry; no cloud is mutated here,
 * and no state changes except the §2.3 PLANNED exception an ENABLE plan makes under a row lock.
 *
 * <p>Order (before any cloud read):
 * <ol>
 *   <li>resolve the group (by {@code spot_group.id} or {@code groupRef}) and its state (null = no row);</li>
 *   <li>kind/state check (409 {@code PLAN_KIND_NOT_ALLOWED}); REVERT of an unmanaged group → 409
 *       {@code NOT_MANAGED}, a REVERTING group → 409 {@code AS_GROUP_BUSY} (§7.4);</li>
 *   <li>idempotency: same key + hash returns the existing plan, same key + different hash → 422
 *       {@code IDEMPOTENCY_KEY_REUSED} (§3.7);</li>
 *   <li>{@code checkEligibility(PLAN)} and {@code createPlan} through the provider's
 *       {@link ReadOnlyCloudView} (read-only by construction, AC-PLN-01);</li>
 *   <li>supersede the prior OPEN plan; insert OPEN with {@code expires_at = now + ttl}; an ENABLE
 *       plan moves the group to PLANNED under the row lock, a superseding ENABLE inherits the prior
 *       plan's {@code from_state}.</li>
 * </ol>
 *
 * <p>With no {@link SpotProvider} registered (dark in prod until Phases 12/26) a plan can't be
 * computed: the request is refused 409 {@code AS_PROVIDER_NOT_READY} after the cheap checks, so no
 * partial plan is written. No conversion, mutation or billing flag is involved.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    /** The current plan body schema version written to {@code spot_plan.schema_version}. */
    public static final int PLAN_SCHEMA_VERSION = 1;

    private final SpotPlanRepository plans;
    private final SpotGroupRepository groups;
    private final SpotOperationRepository operations;
    private final ProviderRegistry providers;
    private final FeeBasisResolver feeBasisResolver;
    private final RevertPreconditions revertPreconditions;
    private final SwitchService switchService;
    private final AutoSpottingSwitchProperties switchProperties;
    private final GroupLifecycleService lifecycle;
    private final Duration planTtl;

    public PlanService(SpotPlanRepository plans, SpotGroupRepository groups, SpotOperationRepository operations,
                       ProviderRegistry providers,
                       FeeBasisResolver feeBasisResolver, RevertPreconditions revertPreconditions,
                       SwitchService switchService, AutoSpottingSwitchProperties switchProperties,
                       GroupLifecycleService lifecycle,
                       @Value("${autospotting.plan.ttl:15m}") Duration planTtl) {
        this.plans = plans;
        this.groups = groups;
        this.operations = operations;
        this.providers = providers;
        this.feeBasisResolver = feeBasisResolver;
        this.revertPreconditions = revertPreconditions;
        this.switchService = switchService;
        this.switchProperties = switchProperties;
        this.lifecycle = lifecycle;
        this.planTtl = planTtl;
    }

    /** The command a plan request carries (controller-resolved; the account is already authorized). */
    public record CreateCommand(OperationKind kind, UUID groupId, String groupRef, String profileId,
                                boolean dryRunRequested, String idempotencyKey, String requestSha256,
                                String createdBy, String policyEditJson, Boolean allowBurstable) {
        /** A command with a policy edit and no plan options. */
        public CreateCommand(OperationKind kind, UUID groupId, String groupRef, String profileId,
                             boolean dryRunRequested, String idempotencyKey, String requestSha256,
                             String createdBy, String policyEditJson) {
            this(kind, groupId, groupRef, profileId, dryRunRequested, idempotencyKey, requestSha256, createdBy,
                    policyEditJson, null);
        }

        /** A command without a policy edit. */
        public CreateCommand(OperationKind kind, UUID groupId, String groupRef, String profileId,
                             boolean dryRunRequested, String idempotencyKey, String requestSha256, String createdBy) {
            this(kind, groupId, groupRef, profileId, dryRunRequested, idempotencyKey, requestSha256, createdBy, null,
                    null);
        }
    }

    /**
     * Create (or return the idempotent) plan. Runs in a new transaction so the superseding update and
     * the OPEN insert commit together.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SpotPlan createPlan(CloudAccount account, ClientUserDetails user, CreateCommand cmd) {
        Provider provider = providerOf(account);

        // 0. A MIG policy update (or a policy edit riding on CONFIG_APPLY) is GCP-only: refuse before any row or cloud read.
        if (provider != Provider.GCP
                && (cmd.kind() == OperationKind.MIG_POLICY_UPDATE || cmd.policyEditJson() != null)) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_KIND_NOT_SUPPORTED",
                    "This plan kind is not supported for this cloud.");
        }
        // 0b. Plan options are AWS-only (design-core.md §7.3): any option on GCP is refused before any row or cloud read.
        if (provider == Provider.GCP && cmd.allowBurstable() != null) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OPTION_NOT_APPLICABLE",
                    "This option does not apply to this cloud.");
        }
        // 1. Resolve the group and its state (null = no row yet).
        SpotGroup group = resolveGroup(account, provider, cmd);
        GroupState state = group == null ? null : group.getState();

        // 2. Kind/state check before any cloud read.
        checkKindState(cmd.kind(), group, state);

        // 3. Idempotency: a prior plan with the same key returns (same hash) or 422 (different hash).
        if (cmd.idempotencyKey() != null) {
            Optional<SpotPlan> prior = plans.findByCloudAccountIdAndIdempotencyKey(account.getId(), cmd.idempotencyKey());
            if (prior.isPresent()) {
                if (!sameHash(prior.get(), cmd.requestSha256())) {
                    throw AutoSpottingApiException.idempotencyKeyReused();
                }
                return prior.get();
            }
        }

        // 4. Read-only plan computation. REVERT also runs the shared switch preconditions.
        SwitchState switches = switchService.current();
        if (cmd.kind() == OperationKind.REVERT) {
            revertPreconditions.checkSwitches(switches);
        }
        SpotProvider spotProvider = providers.find(provider).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_PROVIDER_NOT_READY",
                        "AutoSpotting is not ready to plan for this cloud yet."));

        GroupRef ref = refFor(account, provider, group, cmd);
        ReadOnlyCloudView view = readOnlyView(spotProvider);
        Profile profile = new Profile(cmd.profileId() == null || cmd.profileId().isBlank()
                ? "conservative" : cmd.profileId(), cmd.policyEditJson());
        boolean policyChange = cmd.kind() == OperationKind.MIG_POLICY_UPDATE
                || (cmd.kind() == OperationKind.CONFIG_APPLY && cmd.policyEditJson() != null);

        // A revert is never blocked by the conversion-only eligibility checks (DR-03): it takes the warnings of
        // its own plan, and the provider's restore-permission pre-check (design-core.md §7.4) is part of it.
        // AD-09: the customer's burstable choice for this plan, and the group's last confirmed one (desired_config).
        // D-23b-01: ownership markers in desired_config count only while XamOps' configuration is on the group.
        PlanOptions options = PlanOptions.forGroup(cmd.allowBurstable(), group);
        EligibilityResult eligibility = cmd.kind() == OperationKind.REVERT || policyChange ? null
                : spotProvider.checkEligibility(ref, view, profile, EvalMode.PLAN, options);
        com.xammer.cloud.service.autospotting.spi.PlanRequest planRequest =
                new com.xammer.cloud.service.autospotting.spi.PlanRequest(ref, cmd.kind(), profile, options);
        PlanBody body = spotProvider.createPlan(planRequest, view);
        if (group == null && cmd.kind() == OperationKind.MIG_POLICY_UPDATE) {
            // The MIG must exist before a row does: a missing MIG or a failed read creates nothing.
            if (groupMissingOrUnread(body)) {
                boolean missing = body.eligibility().blockers().stream().anyMatch(c -> "GCP_MIG_NOT_FOUND".equals(c.code()));
                throw missing
                        ? new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Instance group not found.")
                        : new AutoSpottingApiException(HttpStatus.CONFLICT, "CHECK_FAILED",
                                "Could not read the instance group. Try again.");
            }
            // §2.1, §7.4: a MIG with no row gets an UNMANAGED row once its existence is confirmed, so the plan,
            // the op and the group lease have a home. Its state never changes and UNMANAGED stays non-billable.
            group = insertUnmanaged(account, provider, ref, body.providerStableId());
            state = group.getState(); // the new row's UNMANAGED, so the plan's from_state is never null
        } else if (group == null && cmd.kind() != OperationKind.REVERT && !groupMissingOrUnread(body)) {
            // §2.1: a row appears with the first plan. Discovery creates rows only for legacy and marked
            // groups, so the first plan for any other group (an AWS ENABLE of a plain ASG) is rowless.
            // Without a row its plan got a synthetic group id and the confirmed op stayed QUEUED forever,
            // because nothing can lease or run a group that has no row (phase 23 defect D-23-06).
            group = insertUnmanaged(account, provider, ref, body.providerStableId());
            state = group.getState();
        }
        if (eligibility == null) {
            eligibility = body.eligibility() != null ? body.eligibility() : EligibilityResult.eligible(List.of(), List.of());
        }

        String feeBasis = feeBasisResolver.resolve(account.getId(), switches);
        PlanBody bodyWithFee = withFeeBasis(body, feeBasis);

        // 5. Supersede the prior OPEN plan and insert OPEN. An ENABLE moves the group to PLANNED.
        GroupState fromState = resolveFromState(group, state, cmd.kind());
        if (group != null) {
            // Serialize concurrent plans for one group: with no OPEN plan yet the supersede UPDATE locks nothing,
            // so two first plans would both insert OPEN and trip the one-OPEN-plan index. Held to commit.
            plans.lockGroupForPlan(group.getId());
        }
        plans.supersedeOpenForGroup(groupIdFor(group));

        SpotPlan plan = new SpotPlan();
        plan.setId(UUID.randomUUID());
        plan.setGroupId(groupIdFor(group));
        plan.setCloudAccountId(account.getId());
        plan.setKind(cmd.kind());
        plan.setStatus(SpotPlan.PlanStatus.OPEN);
        plan.setFromState(fromState);
        plan.setVersion(1);
        plan.setSchemaVersion(PLAN_SCHEMA_VERSION);
        plan.setLiveFingerprint(fingerprintOf(spotProvider, group, view, body));
        plan.setBody(serialize(bodyWithFee));
        plan.setRequiresFullSpotOptin(requiresFullSpotOptin(eligibility, bodyWithFee));
        plan.setWarningIds(warningIdsJson(eligibility));
        // dry_run per request or per environment (§7.2, plan-phase-11.md 11.3). A dry-run ENABLE
        // plan does not move the group to PLANNED (below), so a dry-run can't make a group ACTIVE.
        boolean dryRun = cmd.dryRunRequested() || switchProperties.mutationsDryRun();
        plan.setDryRun(dryRun);
        plan.setIdempotencyKey(cmd.idempotencyKey());
        plan.setRequestSha256(cmd.requestSha256());
        plan.setCreatedBy(cmd.createdBy() == null ? actorName(user) : cmd.createdBy());
        Instant now = Instant.now();
        plan.setCreatedAt(now);
        plan.setExpiresAt(now.plus(planTtl));
        plans.saveAndFlush(plan);

        // An ENABLE plan moves the group to PLANNED under the row lock (§2.3, §7.4). A superseding
        // ENABLE for a PLANNED group inherits the prior from_state (handled above) and stays PLANNED.
        // A dry-run ENABLE plan does NOT move the group to PLANNED (§7.2), so a dry-run never changes
        // state and the ledger never sees it (PO-04).
        if (cmd.kind() == OperationKind.ENABLE && !dryRun && group != null
                && group.getState() != GroupState.PLANNED) {
            try {
                moveToPlanned(group, user);
            } catch (DataIntegrityViolationException e) {
                throw duplicateProviderAccount();
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("plan {} OPEN kind={} group={} fromState={} expiresAt={}", plan.getId(), cmd.kind(),
                    plan.getGroupId(), fromState, plan.getExpiresAt());
        }
        return plan;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Provider providerOf(CloudAccount account) {
        return "GCP".equalsIgnoreCase(account.getProvider()) ? Provider.GCP : Provider.AWS;
    }

    private SpotGroup resolveGroup(CloudAccount account, Provider provider, CreateCommand cmd) {
        if (cmd.groupId() != null) {
            SpotGroup group = groups.findById(cmd.groupId()).orElseThrow(() ->
                    new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Group not found."));
            if (!account.getId().equals(group.getCloudAccountId())) {
                // Never leak existence across tenants: the same opaque 404.
                throw new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Group not found.");
            }
            return group;
        }
        if (cmd.groupRef() == null || cmd.groupRef().isBlank()) {
            throw new AutoSpottingApiException(HttpStatus.BAD_REQUEST, "CHECK_FAILED",
                    "Either groupId or groupRef is required.");
        }
        return groups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(
                provider, account.getId(), cmd.groupRef(), GroupState.GONE).orElse(null);
    }

    private void checkKindState(OperationKind kind, SpotGroup group, GroupState state) {
        boolean regrant = false;
        if (kind == OperationKind.REVERT) {
            // §7.4 REVERT exceptions, resolved before the general table (GR-02 regrant, configOnGroup, an unfinished
            // confirmed enable): one rule shared with ConfirmService (RevertHistory.revertGate).
            RevertHistory.RevertGate gate = RevertHistory.revertGate(operations, plans, group, state);
            regrant = gate.regrant();
            if ("AS_GROUP_BUSY".equals(gate.refusal())) {
                throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_GROUP_BUSY",
                        "AutoSpotting is already reverting this group.");
            }
            if (gate.refusal() != null) {
                throw new AutoSpottingApiException(HttpStatus.CONFLICT, "NOT_MANAGED",
                        "XamOps does not manage this group, so there is nothing to revert.");
            }
        }
        if (!PlanKinds.isAllowed(kind, state, regrant)) {
            throw AutoSpottingApiException.planKindNotAllowed(PlanKinds.notAllowedDetail(kind, state));
        }
    }

    private GroupRef refFor(CloudAccount account, Provider provider, SpotGroup group, CreateCommand cmd) {
        if (group != null) {
            return new GroupRef(provider, account.getId(), group.getGroupKey(), group.getProviderStableId());
        }
        return new GroupRef(provider, account.getId(), cmd.groupRef(), null);
    }

    private GroupState resolveFromState(SpotGroup group, GroupState state, OperationKind kind) {
        if (group == null) {
            return GroupState.UNMANAGED; // a row appears with the first plan; its from_state is UNMANAGED
        }
        if (kind == OperationKind.ENABLE && state == GroupState.PLANNED) {
            // A superseding ENABLE inherits the superseded plan's from_state (§7.4), so expiry returns
            // the group to UNMANAGED/REVERTED, never to PLANNED with no open plan.
            return plans.findOpenByGroup(group.getId())
                    .map(SpotPlan::getFromState)
                    .orElse(GroupState.UNMANAGED);
        }
        return state;
    }

    private UUID groupIdFor(SpotGroup group) {
        // Every non-REVERT plan for an existing group now has a row (inserted above, D-23-06). A synthetic
        // id remains only for a plan whose group is missing or unreadable; such a plan carries that
        // blocker, so it can't be confirmed, and a stray op on it fails fast in the dispatcher.
        return group != null ? group.getId() : UUID.randomUUID();
    }

    /** True when the provider's plan says the group is gone or could not be read: no row is created then. */
    private static boolean groupMissingOrUnread(PlanBody body) {
        return body.eligibility() != null && body.eligibility().blockers().stream().anyMatch(c ->
                "GCP_MIG_NOT_FOUND".equals(c.code()) || "GROUP_NOT_FOUND".equals(c.code()) || c.errorKind() != null);
    }

    /**
     * The group's UNMANAGED row, inserted if absent. Race-safe: two concurrent first plans for one group
     * both insert with ON CONFLICT DO NOTHING on {@code ux_spot_group_identity}, then read back the single
     * live row (D-23-06), so neither transaction fails on a unique violation.
     */
    private SpotGroup insertUnmanaged(CloudAccount account, Provider provider, GroupRef ref, String bodyStableId) {
        String stableId = ref.providerStableId() != null ? ref.providerStableId() : bodyStableId;
        if (stableId != null && groups.findByProviderAndProviderStableId(provider, stableId).stream()
                .anyMatch(r -> isLive(r) && !(account.getId().equals(r.getCloudAccountId())
                        && ref.groupKey().equals(r.getGroupKey())))) {
            throw duplicateProviderAccount();
        }
        groups.insertUnmanagedIfAbsent(UUID.randomUUID(), provider.name(), account.getId(),
                account.getClient() == null ? null : account.getClient().getId(), ref.groupKey(),
                stableId, "plan", Instant.now());
        return groups.findByProviderAndCloudAccountIdAndGroupKeyAndStateNot(provider, account.getId(),
                        ref.groupKey(), GroupState.GONE)
                .orElseThrow(() -> new IllegalStateException("UNMANAGED row for " + ref.groupKey() + " not found after insert"));
    }

    private static boolean isLive(SpotGroup row) {
        return row.getState() != GroupState.GONE && row.getState() != GroupState.UNMANAGED
                && row.getState() != GroupState.REVERTED;
    }

    private static AutoSpottingApiException duplicateProviderAccount() {
        return new AutoSpottingApiException(HttpStatus.CONFLICT, "DUPLICATE_PROVIDER_ACCOUNT",
                "This group is already managed through another account.");
    }

    private void moveToPlanned(SpotGroup group, ClientUserDetails user) {
        TransitionRequest request = TransitionRequest.of(GroupState.PLANNED, "ENABLE_PLAN_CREATED",
                Actor.user(user == null ? null : user.getId(), null), null, Instant.now());
        GroupLifecycleService.Result result = lifecycle.transitionUnderRowLock(group, request);
        if (result == GroupLifecycleService.Result.STALE) {
            // WARN: a missed PLANNED move makes the plan unconfirmable (confirm requires PLANNED, D-23-07/08).
            log.warn("plan: group {} move to PLANNED missed the guard (stale)", group.getId());
        }
    }

    private boolean requiresFullSpotOptin(EligibilityResult eligibility, PlanBody body) {
        // The provider's plan body knows whether the target is 100% Spot; the fake carries it in the
        // rollout. This phase only needs the flag persisted for confirm step 6 (PROGRESS kinds).
        // A provider says so either through the rollout (the fake) or in its On-Demand floor
        // ({@code "fullSpot":true}: base 0 and no On-Demand share, AWS design-aws.md §3.1, FR-PLN-04).
        return body != null && ((body.rollout() != null && "100".equals(body.rollout().headroom()))
                || (body.onDemandFloorJson() != null && body.onDemandFloorJson().contains("\"fullSpot\":true")));
    }

    private static boolean sameHash(SpotPlan prior, String requestSha256) {
        return prior.getRequestSha256() == null
                ? requestSha256 == null
                : prior.getRequestSha256().equals(requestSha256);
    }

    private static String warningIdsJson(EligibilityResult eligibility) {
        if (eligibility == null || eligibility.warnings().isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        List<EligibilityResult.Check> warnings = eligibility.warnings();
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(warnings.get(i).id())).append('"');
        }
        return sb.append(']').toString();
    }

    private ReadOnlyCloudView readOnlyView(SpotProvider spotProvider) {
        // Each provider supplies its own read-only view in its phase; until then the fake's view
        // stands in. The planning methods accept any ReadOnlyCloudView (AC-PLN-01 is structural).
        return new ReadOnlyCloudView() {
        };
    }

    private String fingerprintOf(SpotProvider provider, SpotGroup group, ReadOnlyCloudView view, PlanBody body) {
        if (group != null) {
            // Ownership comes from the plan's target, not the row's old desired_config (a first enable has none).
            String fp = body == null ? null : provider.liveFingerprintFor(group, body.targetJson(), view);
            return fp != null ? fp : provider.getStatus(group, view).liveFingerprint();
        }
        // No row: derive a stable fingerprint from the plan body's current config so confirm's re-read
        // can compare. The provider's getStatus needs a row; for a rowless plan the body's current is
        // the baseline.
        String current = body == null ? "" : String.valueOf(body.currentJson());
        return "fp:" + Integer.toHexString(current.hashCode());
    }

    private static String serialize(PlanBody body) {
        // PlanBody carries provider JSON fragments as strings; assemble the stored body document. The
        // fragments are produced by createPlan (never a raw provider message), so this is safe text.
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"current\":").append(orNull(body.currentJson()));
        sb.append(",\"target\":").append(orNull(body.targetJson()));
        sb.append(",\"diff\":").append(orNull(body.diffJson()));
        sb.append(",\"onDemandFloor\":").append(orNull(body.onDemandFloorJson()));
        sb.append(",\"feeBasis\":").append(orNull(body.feeBasisJson()));
        sb.append(",\"revertPath\":").append(orNull(body.revertPathJson()));
        sb.append(",\"rollout\":").append(rolloutJson(body.rollout()));
        sb.append(",\"eligibility\":").append(eligibilityJson(body));
        sb.append(",\"estimates\":").append(estimatesJson(body));
        if (body.optionsJson() != null) {
            sb.append(",\"options\":").append(body.optionsJson());
        }
        sb.append("}");
        return sb.toString();
    }

    private static PlanBody withFeeBasis(PlanBody body, String feeBasis) {
        return new PlanBody(body.currentJson(), body.targetJson(), body.diffJson(), body.snapshotToStore(),
                body.eligibility(), body.rollout(), body.estimates(), body.onDemandFloorJson(),
                feeBasis, body.revertPathJson(), body.providerStableId(), body.optionsJson());
    }

    /**
     * The definitive blockers recorded with the plan ({@code {"blockers":[{code,text}]}}), so confirm
     * can refuse a blocked PROGRESS plan with 422 before any operation exists. A failed read
     * ({@code errorKind} set) is not a blocker here: op step 0 re-evaluates eligibility in CONFIRM mode.
     */
    private static String eligibilityJson(PlanBody body) {
        StringBuilder sb = new StringBuilder("{\"blockers\":[");
        boolean first = true;
        if (body.eligibility() != null) {
            for (var b : body.eligibility().blockers()) {
                if (b.errorKind() != null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"code\":\"").append(escape(b.code())).append("\",\"text\":\"")
                        .append(escape(b.text())).append("\"}");
            }
        }
        sb.append("],\"warnings\":[");
        first = true;
        if (body.eligibility() != null) {
            for (var w : body.eligibility().warnings()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"id\":\"").append(escape(w.id())).append("\",\"code\":\"").append(escape(w.code()))
                        .append("\",\"text\":\"").append(escape(w.text())).append("\"}");
            }
        }
        return sb.append("]}").toString();
    }

    private static String rolloutJson(PlanBody.Rollout r) {
        if (r == null) {
            return "null";
        }
        return "{\"method\":\"" + escape(r.method()) + "\",\"instancesToReplace\":" + r.instancesToReplace()
                + ",\"surge\":" + r.surge() + ",\"durationRange\":\"" + escape(r.durationRange())
                + "\",\"headroom\":\"" + escape(r.headroom()) + "\",\"quota\":" + orNull(r.quotaJson()) + "}";
    }

    private static String estimatesJson(PlanBody body) {
        StringBuilder sb = new StringBuilder("[");
        List<PlanBody.Estimate> estimates = body.estimates();
        for (int i = 0; i < estimates.size(); i++) {
            PlanBody.Estimate e = estimates.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"profile\":\"").append(escape(e.profile())).append("\",\"monthlySavings\":")
                    .append(e.monthlySavings() == null ? "null" : e.monthlySavings())
                    .append(",\"priceSources\":").append(orNull(e.priceSourcesJson()))
                    .append(",\"label\":\"").append(escape(e.label())).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String orNull(String json) {
        return json == null || json.isBlank() ? "null" : json;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String actorName(ClientUserDetails user) {
        if (user == null) {
            return "system";
        }
        return user.getUsername() != null ? user.getUsername() : String.valueOf(user.getId());
    }
}
