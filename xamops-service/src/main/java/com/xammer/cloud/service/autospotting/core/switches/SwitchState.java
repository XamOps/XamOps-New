package com.xammer.cloud.service.autospotting.core.switches;

import java.util.Set;

/**
 * An immutable snapshot of the DB half of the kill-switch hierarchy (design-core.md §6.1): the set
 * of engaged {@code autospotting_switch} scope keys and the set of allowlisted
 * {@code cloud_account_id}s, plus two fail-closed markers.
 *
 * <p>Fail closed, per store (design-core.md §6.1): an unreadable {@code autospotting_switch} table
 * counts as switches (a) to (e) engaged ({@link #switchStoreUnreadable} true), so every mutation
 * stops while reads continue; an unreadable {@code autospotting_allowlist} table counts as the
 * empty allowlist ({@link #allowlistUnreadable} true), so no account is allowlisted. The two stores
 * fail independently.
 *
 * @param engagedScopeKeys     engaged scope keys, e.g. {@code GLOBAL}, {@code CLOUD:aws},
 *                             {@code CONVERSIONS:gcp}, {@code ACCOUNT:123}, {@code FREEZE}
 * @param allowlistedAccountIds allowlisted {@code cloud_account_id}s (empty when unreadable)
 * @param switchStoreUnreadable true when {@code autospotting_switch} could not be read
 * @param allowlistUnreadable   true when {@code autospotting_allowlist} could not be read
 */
public record SwitchState(
        Set<String> engagedScopeKeys,
        Set<Long> allowlistedAccountIds,
        boolean switchStoreUnreadable,
        boolean allowlistUnreadable) {

    public static final String GLOBAL = "GLOBAL";
    public static final String FREEZE = "FREEZE";

    public SwitchState {
        engagedScopeKeys = Set.copyOf(engagedScopeKeys);
        allowlistedAccountIds = Set.copyOf(allowlistedAccountIds);
    }

    public static String cloudKey(boolean aws) {
        return "CLOUD:" + (aws ? "aws" : "gcp");
    }

    public static String conversionsKey(boolean aws) {
        return "CONVERSIONS:" + (aws ? "aws" : "gcp");
    }

    public static String accountKey(long cloudAccountId) {
        return "ACCOUNT:" + cloudAccountId;
    }

    /** True when the global switch (a) blocks: an engaged GLOBAL row, or the switch store is unreadable. */
    public boolean globalEngaged() {
        return switchStoreUnreadable || engagedScopeKeys.contains(GLOBAL);
    }

    /** True when the per-cloud master (b) blocks for this cloud. */
    public boolean cloudEngaged(boolean aws) {
        return switchStoreUnreadable || engagedScopeKeys.contains(cloudKey(aws));
    }

    /** True when the conversions switch (c) is engaged in the DB for this cloud, tightening the property. */
    public boolean conversionsEngaged(boolean aws) {
        return switchStoreUnreadable || engagedScopeKeys.contains(conversionsKey(aws));
    }

    /** True when the per-account switch (d) blocks this account. */
    public boolean accountEngaged(long cloudAccountId) {
        return switchStoreUnreadable || engagedScopeKeys.contains(accountKey(cloudAccountId));
    }

    /** True when the emergency freeze (e) is engaged. */
    public boolean freezeEngaged() {
        return switchStoreUnreadable || engagedScopeKeys.contains(FREEZE);
    }

    /** True when {@code cloudAccountId} is on the allowlist (never true when the allowlist is unreadable). */
    public boolean allowlisted(long cloudAccountId) {
        return allowlistedAccountIds.contains(cloudAccountId);
    }
}
