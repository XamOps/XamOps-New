package com.xammer.cloud.service.autospotting.core.plan;

import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@code feeBasis} a plan carries (design-core.md §7.3, PO-16; plan-phase-11.md task
 * 11.2). {@code feeBasis} is {@code {contractId, sharePct, monthlyCap}} when the client has an
 * {@code autospotting_contract} in force and the account is not recorded {@code NO_FEE} on the
 * allowlist; otherwise {@code {contractId: null, reason: NO_CONTRACT | NO_FEE_ACCOUNT}}.
 *
 * <p>Until Phase 15 creates the contract table, there is never a contract in force, so this always
 * returns {@code {contractId:null, reason:NO_CONTRACT}} — except for an allowlisted account recorded
 * {@code NO_FEE}, which returns {@code {contractId:null, reason:NO_FEE_ACCOUNT}}. Confirm records the
 * plan's {@code feeBasis} exactly as shown, without recomputing it (§7.3). No billing flag is lifted;
 * this is read-only JSON for the plan body.
 */
@Component
public class FeeBasisResolver {

    /** {@code feeBasis} when no contract applies: the plan dialog shows "No AutoSpotting fee applies". */
    public static final String NO_CONTRACT = "{\"contractId\":null,\"reason\":\"NO_CONTRACT\"}";
    public static final String NO_FEE_ACCOUNT = "{\"contractId\":null,\"reason\":\"NO_FEE_ACCOUNT\"}";

    /**
     * The {@code feeBasis} JSON for a plan on {@code cloudAccountId}. The {@code switchState} carries
     * the allowlist; a future phase reads the allowlist's {@code billing_basis} here. Today the
     * contract table does not exist (Phase 15), so this is NO_CONTRACT for every account that is not
     * recorded NO_FEE.
     *
     * @param cloudAccountId the account the plan is for
     * @param switchState    the current switch/allowlist snapshot
     */
    public String resolve(long cloudAccountId, SwitchState switchState) {
        // Phase 15 adds the contract table and the NO_FEE allowlist read. Until then, the only branch
        // that differs is a NO_FEE allowlist row, and that read also lands with the ledger; the safe
        // default now is NO_CONTRACT (no fee applies, no billing flag lifted). AC-PLN-09 (unpriced
        // "Not priced", not 0) is on the estimate, not the fee basis.
        return NO_CONTRACT;
    }
}
