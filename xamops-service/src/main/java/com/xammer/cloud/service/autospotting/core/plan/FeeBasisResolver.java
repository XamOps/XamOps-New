package com.xammer.cloud.service.autospotting.core.plan;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.AutoSpottingAllowlistEntry;
import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.ledger.AutoSpottingContractRepository;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the {@code feeBasis} a plan carries (design-core.md §7.3, PO-16; plan-phase-11.md task
 * 11.2, plan-phase-15.md task 15.4). {@code feeBasis} is {@code {contractId, sharePct, monthlyCap}}
 * when the account's client has an {@code autospotting_contract} in force and the account is not
 * recorded {@code NO_FEE} on the allowlist; otherwise {@code {contractId: null, reason:
 * NO_CONTRACT | NO_FEE_ACCOUNT}}.
 *
 * <p>Order: a {@code NO_FEE} allowlist row wins even when the client has a contract (those groups never
 * bill). Otherwise the contract in force now decides. Anything unreadable resolves to
 * {@code NO_CONTRACT}: no fee is ever implied by a failed read. Confirm records the plan's
 * {@code feeBasis} exactly as shown, without recomputing it (§7.3). Shares and caps are written from
 * the stored decimals with {@code toPlainString} (no floating point). Read-only: no billing flag is
 * lifted.
 */
@Component
public class FeeBasisResolver {

    private static final Logger log = LoggerFactory.getLogger(FeeBasisResolver.class);

    /** {@code feeBasis} when no contract applies: the plan dialog shows "No AutoSpotting fee applies". */
    public static final String NO_CONTRACT = "{\"contractId\":null,\"reason\":\"NO_CONTRACT\"}";
    public static final String NO_FEE_ACCOUNT = "{\"contractId\":null,\"reason\":\"NO_FEE_ACCOUNT\"}";

    private final AutoSpottingAllowlistEntryRepository allowlist;
    private final AutoSpottingContractRepository contracts;
    private final CloudAccountRepository accounts;

    public FeeBasisResolver(AutoSpottingAllowlistEntryRepository allowlist,
                            AutoSpottingContractRepository contracts,
                            CloudAccountRepository accounts) {
        this.allowlist = allowlist;
        this.contracts = contracts;
        this.accounts = accounts;
    }

    /**
     * The {@code feeBasis} JSON for a plan on {@code cloudAccountId}.
     *
     * @param cloudAccountId the account the plan is for
     * @param switchState    the current switch snapshot (unused: the allowlist basis is read directly)
     */
    public String resolve(long cloudAccountId, SwitchState switchState) {
        try {
            Optional<AutoSpottingAllowlistEntry> row = allowlist.findById(cloudAccountId);
            if (row.isPresent() && "NO_FEE".equals(row.get().getBillingBasis())) {
                return NO_FEE_ACCOUNT;
            }
            Long clientId = accounts.findById(cloudAccountId)
                    .map(CloudAccount::getClient)
                    .map(c -> c.getId())
                    .orElse(null);
            if (clientId == null) {
                return NO_CONTRACT;
            }
            List<AutoSpottingContract> inForce = contracts.findInForce(clientId, Instant.now());
            if (inForce.isEmpty()) {
                return NO_CONTRACT;
            }
            return json(inForce.get(0));
        } catch (RuntimeException e) {
            log.warn("fee basis unreadable for account {}; resolving to NO_CONTRACT: {}", cloudAccountId,
                    e.getClass().getSimpleName());
            return NO_CONTRACT;
        }
    }

    private static String json(AutoSpottingContract c) {
        return "{\"contractId\":\"" + c.getId() + "\",\"sharePct\":" + c.getSharePct().toPlainString()
                + ",\"monthlyCap\":" + (c.getMonthlyCap() == null ? "null" : c.getMonthlyCap().toPlainString())
                + "}";
    }
}
