package com.xammer.cloud.service.autospotting.core.lifecycle;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.Result;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The operator pause/resume of a managed group (design-core.md §6.4, §6.6; plan-phase-09.md task 9.4).
 * An operator pauses a running group to {@code PAUSED(OPERATOR)} and resumes it to its
 * {@code paused_from_state}, without an operation: there is no lease holder, so the write goes
 * through {@link GroupLifecycleService#transitionUnderRowLock}, which locks the row with
 * {@code SELECT ... FOR UPDATE} under the {@code (state, version)} guard and writes the append-only
 * transition and the audit row in the same transaction.
 *
 * <p>Pause is allowed only from ACTIVE, DEGRADED or FALLBACK; anything else is 409
 * {@code PLAN_KIND_NOT_ALLOWED}. Resume is allowed only from {@code PAUSED(OPERATOR)}. The
 * transition's {@code effective_at} is the request time, clamped by {@code GroupLifecycleService}
 * (DR-01).
 */
@Service
public class OperatorGroupService {

    private static final Logger log = LoggerFactory.getLogger(OperatorGroupService.class);

    /** The states an operator may pause from (design-core.md §6.6). */
    private static final Set<GroupState> PAUSABLE =
            EnumSet.of(GroupState.ACTIVE, GroupState.DEGRADED, GroupState.FALLBACK);

    private final SpotGroupRepository groups;
    private final GroupLifecycleService lifecycle;

    public OperatorGroupService(SpotGroupRepository groups, GroupLifecycleService lifecycle) {
        this.groups = groups;
        this.lifecycle = lifecycle;
    }

    /**
     * Operator pause to {@code PAUSED(OPERATOR)} from ACTIVE/DEGRADED/FALLBACK (design-core.md §6.6).
     *
     * @throws AutoSpottingApiException 404 when the group is absent, or 409
     *         {@code PLAN_KIND_NOT_ALLOWED} when the group is not in a pausable state
     */
    public GroupState pause(UUID groupId, Actor actor, String reason) {
        SpotGroup group = load(groupId);
        if (!PAUSABLE.contains(group.getState())) {
            throw AutoSpottingApiException.planKindNotAllowed(
                    "This group can't be paused from " + group.getState() + ".");
        }
        GroupState from = group.getState();
        TransitionRequest request = TransitionRequest
                .of(GroupState.PAUSED, "OPERATOR_PAUSE", actor, null, Instant.now())
                .withPause(PauseReason.OPERATOR, from);
        Result result = lifecycle.transitionUnderRowLock(group, request);
        if (result == Result.STALE) {
            throw AutoSpottingApiException.planKindNotAllowed(
                    "This group changed state before the pause could be applied. Reload and try again.");
        }
        log.info("operator paused group {} from {} ({})", groupId, from, result);
        return GroupState.PAUSED;
    }

    /**
     * Operator resume from {@code PAUSED(OPERATOR)} back to {@code paused_from_state} (design-core.md
     * §6.6).
     *
     * @throws AutoSpottingApiException 404 when the group is absent, or 409
     *         {@code PLAN_KIND_NOT_ALLOWED} when the group is not operator-paused
     */
    public GroupState resume(UUID groupId, Actor actor, String reason) {
        SpotGroup group = load(groupId);
        if (group.getState() != GroupState.PAUSED || group.getPauseReason() != PauseReason.OPERATOR) {
            throw AutoSpottingApiException.planKindNotAllowed(
                    "This group is not operator-paused, so it can't be resumed here.");
        }
        GroupState target = group.getPausedFromState() != null ? group.getPausedFromState() : GroupState.ACTIVE;
        TransitionRequest request = TransitionRequest
                .of(target, "OPERATOR_RESUME", actor, null, Instant.now());
        Result result = lifecycle.transitionUnderRowLock(group, request);
        if (result == Result.STALE) {
            throw AutoSpottingApiException.planKindNotAllowed(
                    "This group changed state before the resume could be applied. Reload and try again.");
        }
        log.info("operator resumed group {} to {} ({})", groupId, target, result);
        return target;
    }

    private SpotGroup load(UUID groupId) {
        return groups.findById(groupId)
                .orElseThrow(() -> new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Group not found."));
    }
}
