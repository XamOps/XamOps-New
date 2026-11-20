package com.xammer.cloud.service.autospotting.core.plan;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.dto.autospotting.v2.PlanDto;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmCommand;
import com.xammer.cloud.service.autospotting.core.plan.ConfirmService.ConfirmResult;
import com.xammer.cloud.service.autospotting.core.plan.PlanService.CreateCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Revert all" for one account (design-core.md section 7.5, PO-21, FR-SPN-14, AC-CMP-03). Creates one REVERT
 * plan per group in a revertable state, all sharing a {@code batch_id}, and confirms the batch plan by plan
 * through {@link ConfirmService#confirm}, the normal confirm path (switches, PO-14 authorization, preemption,
 * the section 7.4 checks). One plan's failure never stops the others.
 *
 * <p>Nothing here calls a cloud API or enqueues work beyond what the normal plan and confirm services do.
 * Plan creation and confirm each run in their own transaction ({@code REQUIRES_NEW}), so this service is
 * deliberately not transactional: a failure on one group can't roll back another group's plan or op.
 *
 * <p>Idempotency: the batch id is derived from the account and the request's Idempotency-Key, and every plan
 * and confirm key is derived from the batch and the group, so a replayed request returns the same batch, the
 * same plans and the same operations.
 */
@Service
public class RevertAllService {

    private static final Logger log = LoggerFactory.getLogger(RevertAllService.class);

    /** Design-core 7.4: the states a REVERT plan is allowed from, FAILED carrying REVERT_FROM_FAILED. */
    public static final Set<GroupState> REVERTABLE = EnumSet.of(GroupState.ACTIVE, GroupState.DEGRADED,
            GroupState.FALLBACK, GroupState.PAUSED, GroupState.APPLYING, GroupState.LEGACY_UNCONFIRMED,
            GroupState.FAILED);

    /**
     * One group of a batch. {@code plan} is null when the plan could not be created ({@code errorCode} says
     * why); {@code operationId} and {@code operationStatus} are set once the plan is confirmed.
     */
    public record Item(UUID groupId, String groupKey, String groupState, PlanDto plan, String errorCode,
                       String errorDetail, UUID operationId, String operationStatus) {
        Item withError(String code, String detail) {
            return new Item(groupId, groupKey, groupState, plan, code, detail, operationId, operationStatus);
        }
    }

    /**
     * A batch. {@code reverted} counts groups now REVERTED, {@code failed} groups with a creation or confirm
     * error or a FAILED state, and {@code total} every group in the batch ("n of m reverted").
     * {@code alreadyReverting} lists groups a revert is already running on; they are not part of the batch, and
     * account deletion stays blocked until they finish.
     */
    public record Batch(UUID batchId, Long accountId, List<Item> items, int total, int reverted, int failed,
                        List<UUID> alreadyReverting) {
    }

    private final SpotGroupRepository groups;
    private final SpotPlanRepository plans;
    private final SpotOperationRepository operations;
    private final PlanService planService;
    private final ConfirmService confirmService;
    private final IdempotencyService idempotency;

    public RevertAllService(SpotGroupRepository groups, SpotPlanRepository plans,
                            SpotOperationRepository operations, PlanService planService,
                            ConfirmService confirmService, IdempotencyService idempotency) {
        this.groups = groups;
        this.plans = plans;
        this.operations = operations;
        this.planService = planService;
        this.confirmService = confirmService;
        this.idempotency = idempotency;
    }

    /** The batch id a request maps to: stable for the same account and Idempotency-Key. */
    public static UUID batchIdFor(Long accountId, String idempotencyKey) {
        return UUID.nameUUIDFromBytes(("revert-all:" + accountId + ":" + idempotencyKey)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Create the batch: one REVERT plan per revertable group of the account. */
    public Batch create(CloudAccount account, ClientUserDetails user, String idempotencyKey) {
        UUID batchId = batchIdFor(account.getId(), idempotencyKey);
        List<SpotGroup> rows = groups.findByCloudAccountId(account.getId()).stream()
                .sorted(Comparator.comparing(SpotGroup::getGroupKey)).toList();
        List<Item> items = new ArrayList<>();
        List<UUID> reverting = new ArrayList<>();
        for (SpotGroup group : rows) {
            if (group.getState() == GroupState.REVERTING) {
                reverting.add(group.getId());
                continue;
            }
            if (!REVERTABLE.contains(group.getState())) {
                continue;
            }
            items.add(createOne(account, user, batchId, group));
        }
        return summarize(batchId, account.getId(), items, reverting);
    }

    /** Read a batch back with each group's current state and operation status ("n of m reverted"). */
    public Batch get(CloudAccount account, UUID batchId) {
        List<SpotPlan> batchPlans = batchPlans(account, batchId);
        if (batchPlans.isEmpty()) {
            throw new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch not found.");
        }
        List<Item> items = batchPlans.stream().map(this::itemFor).toList();
        List<UUID> reverting = groups.findByCloudAccountId(account.getId()).stream()
                .filter(g -> g.getState() == GroupState.REVERTING
                        && batchPlans.stream().noneMatch(p -> p.getGroupId().equals(g.getId())))
                .map(SpotGroup::getId).toList();
        return summarize(batchId, account.getId(), items, reverting);
    }

    /**
     * Confirm every plan of the batch through the normal confirm path. A plan whose confirm is refused (a
     * missing acknowledgment, a stale plan, a busy group, a switch, a role) is reported with its error code and
     * the rest still run. Returns the batch with each group's operation id or error code.
     */
    public Batch confirm(CloudAccount account, ClientUserDetails user, UUID batchId,
                         List<String> acknowledgedWarningIds, String reason) {
        List<SpotPlan> batchPlans = batchPlans(account, batchId);
        if (batchPlans.isEmpty()) {
            throw new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch not found.");
        }
        List<Item> items = new ArrayList<>();
        for (SpotPlan plan : batchPlans) {
            Item item = itemFor(plan);
            try {
                String key = "rc-" + UUID.nameUUIDFromBytes((batchId + ":" + plan.getId())
                        .getBytes(StandardCharsets.UTF_8));
                String hash = idempotency.hash("{\"planId\":\"" + plan.getId() + "\",\"planVersion\":"
                        + plan.getVersion() + ",\"acks\":" + acknowledgedWarningIds + "}");
                ConfirmResult result = confirmService.confirm(account, user, plan.getId(),
                        new ConfirmCommand(plan.getVersion(), acknowledgedWarningIds, false, reason, false,
                                key, hash));
                item = new Item(item.groupId(), item.groupKey(), item.groupState(), item.plan(), null, null,
                        result.operationId(), result.status());
            } catch (AutoSpottingApiException e) {
                item = item.withError(e.getCode(), e.getMessage());
            } catch (RuntimeException e) {
                // Any other failure of this one plan (a lease or store error) is reported, never rethrown:
                // the other groups must still get their confirm.
                log.warn("revert-all {}: confirm of plan {} failed: {}", batchId, plan.getId(),
                        e.getClass().getSimpleName());
                item = item.withError("CONFIRM_FAILED", "This group could not be confirmed. Try again.");
            }
            items.add(item);
        }
        return summarize(batchId, account.getId(), items, List.of());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Item createOne(CloudAccount account, ClientUserDetails user, UUID batchId, SpotGroup group) {
        Item base = new Item(group.getId(), group.getGroupKey(), group.getState().name(), null, null, null, null,
                null);
        try {
            String key = "ra-" + UUID.nameUUIDFromBytes((batchId + ":" + group.getId())
                    .getBytes(StandardCharsets.UTF_8));
            String hash = idempotency.hash("{\"kind\":\"REVERT\",\"groupId\":\"" + group.getId() + "\"}");
            SpotPlan plan = planService.createPlan(account, user, new CreateCommand(OperationKind.REVERT,
                    group.getId(), null, null, false, key, hash, actorName(user)));
            plans.assignBatch(plan.getId(), batchId);
            plan.setBatchId(batchId);
            return new Item(group.getId(), group.getGroupKey(), group.getState().name(), PlanDto.from(plan), null,
                    null, null, null);
        } catch (AutoSpottingApiException e) {
            return base.withError(e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("revert-all {}: plan for group {} failed: {}", batchId, group.getId(),
                    e.getClass().getSimpleName());
            return base.withError("PLAN_FAILED", "A revert plan could not be created for this group.");
        }
    }

    private List<SpotPlan> batchPlans(CloudAccount account, UUID batchId) {
        // The account check keeps a batch id from leaking another tenant's plans (opaque 404 upstream).
        return plans.findByBatchId(batchId).stream()
                .filter(p -> account.getId().equals(p.getCloudAccountId()))
                .sorted(Comparator.comparing(SpotPlan::getCreatedAt).thenComparing(SpotPlan::getId))
                .toList();
    }

    private Item itemFor(SpotPlan plan) {
        Optional<SpotGroup> group = groups.findById(plan.getGroupId());
        Optional<SpotOperation> op = operations.findByPlanId(plan.getId());
        return new Item(plan.getGroupId(), group.map(SpotGroup::getGroupKey).orElse(null),
                group.map(g -> g.getState().name()).orElse(null), PlanDto.from(plan), null, null,
                op.map(SpotOperation::getId).orElse(null), op.map(o -> o.getStatus().name()).orElse(null));
    }

    private static Batch summarize(UUID batchId, Long accountId, List<Item> items, List<UUID> reverting) {
        int reverted = (int) items.stream().filter(i -> GroupState.REVERTED.name().equals(i.groupState())).count();
        int failed = (int) items.stream().filter(i -> i.errorCode() != null
                || GroupState.FAILED.name().equals(i.groupState()) && i.operationId() != null).count();
        return new Batch(batchId, accountId, List.copyOf(items), items.size(), reverted, failed,
                List.copyOf(reverting));
    }

    private static String actorName(ClientUserDetails user) {
        if (user == null) {
            return "system";
        }
        return user.getUsername() != null ? user.getUsername() : String.valueOf(user.getId());
    }
}
