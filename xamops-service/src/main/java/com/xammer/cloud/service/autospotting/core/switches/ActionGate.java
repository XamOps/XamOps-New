package com.xammer.cloud.service.autospotting.core.switches;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The kill-switch action matrix (design-core.md §6.3, plan-phase-05.md task 5.1). Every mutation
 * passes {@link #check(ActionKind, CloudAccount, Provider)} before it touches the cloud;
 * {@link Decision} is {@link Decision#allow()} or a block carrying the exact §6.3 error code, its
 * HTTP status and the engaged switch name for the error envelope (FR-SPN-09).
 *
 * <p>The effective value of a switch is {@code property AND NOT db.engaged} (§6.1): a switch blocks
 * when the deployed property is off <em>or</em> the DB row is engaged. The property half is read
 * here from {@link AutoSpottingSwitchProperties} (row b master, row c' allowlist-only, row p
 * programs-ready), {@link AutoSpottingFeatureFlags} (row c conversions) and (row a)
 * {@code autospotting.enabled}; the DB half is the {@link SwitchState} passed in (its engaged scope
 * keys, including the row c {@code CONVERSIONS:{aws,gcp}} tighten-only override, and allowlist
 * membership). Row a is wired in the constructor from the one flag set true only
 * in {@code application-prod.properties} (code default false, AC-SPN-11).
 *
 * <p>Evaluation follows the §6.3 column order a, b, c, c', d, e, p with the store-unreadable column
 * first: an unreadable switch store makes {@link SwitchState} report (a)-(e) engaged, and the matrix
 * returns {@code AS_SWITCH_UNREADABLE} for that whole column, so it is checked before the individual
 * switches. Freeze (e) overrides everything (§6.1), so REVERT and MIG_POLICY_UPDATE still stop under
 * it. {@link ActionKind#MIG_POLICY_UPDATE} ignores (c), (c') and (p); {@link ActionKind#REVERT}
 * ignores (a)-(d) and (c'); both are blocked only by (e) and (p) (and store-unreadable), per §6.3.
 */
@Component
public class ActionGate {

    /** The mutation kinds the matrix gates (design-core.md §6.2, §6.3, spine {@code spot_operation.kind}). */
    public enum ActionKind {
        /** PROGRESS conversions gated by the full column: ENABLE, ADOPT. */
        ENABLE,
        ADOPT,
        /** PROGRESS config changes gated by the full column: CONFIG_APPLY, PROFILE_CHANGE, ACCEPT_BASELINE. */
        CONFIG_APPLY,
        PROFILE_CHANGE,
        ACCEPT_BASELINE,
        /** GCP MIG policy update on an unmanaged MIG: not a conversion, ignores (c), (c') and (p) (§6.3, §7.4). */
        MIG_POLICY_UPDATE,
        /** SAFETY restore: ignores (a)-(d) and (c'); only (e) and (p) stop it (§6.3). */
        REVERT;

        boolean isConversion() {
            return this == ENABLE || this == ADOPT;
        }

        boolean isRevert() {
            return this == REVERT;
        }

        boolean isMigPolicyUpdate() {
            return this == MIG_POLICY_UPDATE;
        }
    }

    private final AutoSpottingSwitchProperties properties;
    private final AutoSpottingFeatureFlags featureFlags;
    private final boolean globalEnabledProperty;

    /**
     * @param globalEnabledProperty row (a) {@code autospotting.enabled}: code default false, true
     *                              only in {@code application-prod.properties} (AC-SPN-11).
     */
    public ActionGate(AutoSpottingSwitchProperties properties,
                      AutoSpottingFeatureFlags featureFlags,
                      @org.springframework.beans.factory.annotation.Value("${autospotting.enabled:false}")
                      boolean globalEnabledProperty) {
        this.properties = properties;
        this.featureFlags = featureFlags;
        this.globalEnabledProperty = globalEnabledProperty;
    }

    /**
     * Evaluate the matrix for {@code kind} on {@code account}. {@code state} is the DB half
     * (normally {@link SwitchService#readUncached()} at a step boundary, or {@link SwitchService#current()}
     * on the request path).
     */
    public Decision check(ActionKind kind, CloudAccount account, Provider provider, SwitchState state) {
        boolean aws = provider == Provider.AWS;
        long accountId = account.getId();

        // Store-unreadable column: AS_SWITCH_UNREADABLE for every gated action (including REVERT and
        // MIG_POLICY_UPDATE), because the store that says whether FREEZE is engaged can't be read.
        if (state.switchStoreUnreadable()) {
            return Decision.block(Code.AS_SWITCH_UNREADABLE, "SWITCH_STORE");
        }

        // Freeze (e) overrides everything (§6.1): it is the only switch that stops REVERT.
        if (state.freezeEngaged()) {
            return Decision.block(Code.AS_FROZEN, "FREEZE");
        }

        if (kind.isRevert()) {
            // REVERT ignores (a)-(d) and (c'); (e) handled above; (p) below.
            if (!properties.v2MutationsEnabled(aws)) {
                return Decision.block(Code.AS_PROVIDER_NOT_READY, "PROVIDER");
            }
            return Decision.allow();
        }

        // Global (a): property AND NOT db. Still gates MIG_POLICY_UPDATE; only (c), (c') and (p) skip it.
        if (!globalEnabledProperty || state.globalEngaged()) {
            return Decision.block(Code.AS_DISABLED, "GLOBAL");
        }

        // Per-cloud master (b): property AND NOT db.
        if (!properties.cloudEnabled(aws) || state.cloudEngaged(aws)) {
            return Decision.block(Code.AS_CLOUD_DISABLED, "CLOUD");
        }

        // Conversions (c) and lift allowlist (c') — skipped for MIG_POLICY_UPDATE (not a conversion).
        if (!kind.isMigPolicyUpdate()) {
            // Conversions (c): property AND NOT db.engaged — the DB CONVERSIONS:{aws,gcp} row tightens.
            boolean conversionsProperty = aws
                    ? featureFlags.isAwsConversionsEnabled()
                    : featureFlags.isGcpConversionsEnabled();
            if (!conversionsProperty || state.conversionsEngaged(aws)) {
                return Decision.block(Code.AS_CONVERSIONS_PAUSED, "CONVERSIONS");
            }
            // (c') only gates ENABLE/ADOPT: an account not on the allowlist while allowlist-only is on.
            if (kind.isConversion()
                    && properties.conversionsAllowlistOnly(aws)
                    && !state.allowlisted(accountId)) {
                return Decision.block(Code.AS_NOT_ALLOWLISTED, "ALLOWLIST");
            }
        }

        // Per-account (d): db only.
        if (state.accountEngaged(accountId)) {
            return Decision.block(Code.AS_ACCOUNT_DISABLED, "ACCOUNT");
        }

        // Provider programs ready (p) — skipped for MIG_POLICY_UPDATE (its program ships in B3, §7.4).
        if (!kind.isMigPolicyUpdate() && !properties.v2MutationsEnabled(aws)) {
            return Decision.block(Code.AS_PROVIDER_NOT_READY, "PROVIDER");
        }

        return Decision.allow();
    }

    /** The §6.3 error codes and their HTTP statuses. */
    public enum Code {
        AS_DISABLED(HttpStatus.SERVICE_UNAVAILABLE),
        AS_CLOUD_DISABLED(HttpStatus.SERVICE_UNAVAILABLE),
        AS_CONVERSIONS_PAUSED(HttpStatus.CONFLICT),
        AS_NOT_ALLOWLISTED(HttpStatus.CONFLICT),
        AS_ACCOUNT_DISABLED(HttpStatus.CONFLICT),
        AS_FROZEN(HttpStatus.SERVICE_UNAVAILABLE),
        AS_PROVIDER_NOT_READY(HttpStatus.CONFLICT),
        AS_SWITCH_UNREADABLE(HttpStatus.SERVICE_UNAVAILABLE);

        private final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    /**
     * The gate's verdict: either {@link #allow()} or a block carrying the §6.3 {@link Code}, its
     * HTTP status and the engaged switch name for the error envelope ({@code "GLOBAL"},
     * {@code "CLOUD"}, {@code "CONVERSIONS"}, {@code "ALLOWLIST"}, {@code "ACCOUNT"}, {@code "FREEZE"},
     * {@code "PROVIDER"}, {@code "SWITCH_STORE"}).
     */
    public record Decision(boolean allowed, Code code, String switchName) {

        private static final Decision ALLOW = new Decision(true, null, null);

        public static Decision allow() {
            return ALLOW;
        }

        static Decision block(Code code, String switchName) {
            return new Decision(false, code, switchName);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public HttpStatus status() {
            return code == null ? HttpStatus.OK : code.status();
        }
    }
}
