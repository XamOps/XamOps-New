package com.xammer.cloud.service.autospotting.core;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.core.AccountHasManagedGroupsException.ManagedGroup;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a cloud account may be deleted while AutoSpotting manages groups in it (PO-21, FR-SPN-14,
 * design-core.md section 7.5). Read-only: it reads the account's {@code spot_group} rows and never writes, and
 * the deletion path it guards never touches {@code spot_*}, ledger, statement or audit tables, so all of them
 * stay after deletion (no table has a foreign key to the account).
 *
 * <p>Deletion is blocked while any group is APPLYING, ACTIVE, FALLBACK or REVERTING. DEGRADED, PAUSED and FAILED
 * groups are still converted in the cloud but do not block, because PO-21 does not list them; whether they
 * should is the open PO-21a. {@link #check} reports them as {@code stillConverted} so the deletion dialog can
 * name them ("Revert all" includes them). If PO-21a is decided the other way, only {@link #BLOCKING} changes.
 */
@Service
public class AccountDeletionGuard {

    /** PO-21: the states that block account deletion. */
    public static final Set<GroupState> BLOCKING =
            EnumSet.of(GroupState.APPLYING, GroupState.ACTIVE, GroupState.FALLBACK, GroupState.REVERTING);

    /** PO-21a (open, default no): converted but not blocking; listed in the dialog as "still converted". */
    public static final Set<GroupState> STILL_CONVERTED =
            EnumSet.of(GroupState.DEGRADED, GroupState.PAUSED, GroupState.FAILED);

    /** The groups that block deletion and the groups that stay converted if the account is deleted anyway. */
    public record Check(List<ManagedGroup> blocking, List<ManagedGroup> stillConverted, String revertAllPath) {
        public boolean deletable() {
            return blocking.isEmpty();
        }
    }

    private final SpotGroupRepository groups;

    public AccountDeletionGuard(SpotGroupRepository groups) {
        this.groups = groups;
    }

    /** UI path of the "Revert all" flow for an account. */
    public static String revertAllPath(Long accountId) {
        return "/autospotting/accounts/" + accountId + "/revert-all";
    }

    public Check check(Long accountId) {
        List<SpotGroup> rows = groups.findByCloudAccountId(accountId);
        return new Check(pick(rows, BLOCKING), pick(rows, STILL_CONVERTED), revertAllPath(accountId));
    }

    /** @throws AccountHasManagedGroupsException when a group is in a PO-21 blocking state; nothing is written */
    public void assertDeletable(Long accountId) {
        Check check = check(accountId);
        if (!check.deletable()) {
            throw new AccountHasManagedGroupsException(check.blocking(), check.revertAllPath());
        }
    }

    private static List<ManagedGroup> pick(List<SpotGroup> rows, Set<GroupState> states) {
        return rows.stream()
                .filter(g -> states.contains(g.getState()))
                .sorted(Comparator.comparing(SpotGroup::getGroupKey))
                .map(g -> new ManagedGroup(g.getId(), g.getGroupKey(), g.getState().name()))
                .toList();
    }
}
