package com.xammer.cloud.service.autospotting.core;

import java.util.List;
import java.util.UUID;

/**
 * Thrown by {@link AccountDeletionGuard} when an account still has a group in one of PO-21's blocking states.
 * The deletion endpoint maps it to 409 {@code ACCOUNT_HAS_MANAGED_GROUPS} with the group list and the
 * "Revert all" link (design-core.md section 7.5, AC-CMP-03). Nothing has been deleted when it is thrown.
 */
public class AccountHasManagedGroupsException extends RuntimeException {

    public static final String CODE = "ACCOUNT_HAS_MANAGED_GROUPS";

    /** One group the customer must revert (or wait out) before the account can be deleted. */
    public record ManagedGroup(UUID groupId, String groupKey, String state) {
    }

    private final transient List<ManagedGroup> groups;
    private final String revertAllPath;

    public AccountHasManagedGroupsException(List<ManagedGroup> groups, String revertAllPath) {
        super("This account still has groups managed by AutoSpotting. Revert them before deleting the account.");
        this.groups = List.copyOf(groups);
        this.revertAllPath = revertAllPath;
    }

    public List<ManagedGroup> getGroups() {
        return groups;
    }

    /** The UI path of the "Revert all" flow for this account. */
    public String getRevertAllPath() {
        return revertAllPath;
    }
}
