package com.xammer.cloud.service.autospotting.core.allowlist;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.AutoSpottingAllowlistEntry;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.ledger.AutoSpottingContractRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The operator half of the conversion allowlist (design-core.md §6.1, §6.6, DC-11, plan-phase-15.md
 * task 15.4). An account joins the allowlist only with a recorded billing basis:
 * <ul>
 *   <li>{@code CONTRACT}, accepted only while the account's client has an
 *       {@code autospotting_contract} in force; its id is recorded;</li>
 *   <li>{@code NO_FEE}, with a reason: the account's groups never bill;</li>
 *   <li>anything else, or either basis without what it needs: 409
 *       {@code ALLOWLIST_NEEDS_BILLING_BASIS}.</li>
 * </ul>
 * The check runs when a row is added or its basis is changed, and each change is audited in the same
 * transaction. A contract that ends later does not remove the row; the listing shows whether the
 * recorded contract is still in force. Removing deletes the row; groups already converted are
 * unaffected. This only records who may convert: no conversion flag changes.
 */
@Service
public class AllowlistOperatorService {

    public static final String CONTRACT = "CONTRACT";
    public static final String NO_FEE = "NO_FEE";

    private final AutoSpottingAllowlistEntryRepository allowlist;
    private final AutoSpottingContractRepository contracts;
    private final CloudAccountRepository accounts;
    private final AutoSpottingAuditWriter auditWriter;

    public AllowlistOperatorService(AutoSpottingAllowlistEntryRepository allowlist,
                                    AutoSpottingContractRepository contracts,
                                    CloudAccountRepository accounts,
                                    AutoSpottingAuditWriter auditWriter) {
        this.allowlist = allowlist;
        this.contracts = contracts;
        this.accounts = accounts;
        this.auditWriter = auditWriter;
    }

    /** One allowlist row with its account and contract context. */
    public record AllowlistView(long cloudAccountId, String provider, String accountName, Long clientId,
                                String billingBasis, UUID contractId, boolean contractInForce,
                                String reason, String addedBy, Instant addedAt) {
    }

    /**
     * What the Add dialog may offer for one account: "Client contract" only while the account's client
     * has a contract in force (design-core.md §6.1); "Never bills" is always possible with a reason.
     */
    public record BasisOptions(long cloudAccountId, String provider, String accountName, Long clientId,
                               boolean contractInForce, UUID contractId) {
    }

    @Transactional(readOnly = true)
    public BasisOptions options(long cloudAccountId) {
        CloudAccount account = accounts.findById(cloudAccountId).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Account not found."));
        Provider provider = providerOf(account);
        Long clientId = account.getClient() == null ? null : account.getClient().getId();
        List<AutoSpottingContract> inForce = clientId == null ? List.of()
                : contracts.findInForce(clientId, Instant.now());
        return new BasisOptions(cloudAccountId, provider.name(), account.getAccountName(), clientId,
                !inForce.isEmpty(), inForce.isEmpty() ? null : inForce.get(0).getId());
    }

    @Transactional(readOnly = true)
    public List<AllowlistView> list() {
        Instant now = Instant.now();
        List<AutoSpottingAllowlistEntry> rows = allowlist.findAll();
        Map<Long, CloudAccount> byId = accounts.findAllById(
                rows.stream().map(AutoSpottingAllowlistEntry::getCloudAccountId).toList())
                .stream().collect(Collectors.toMap(CloudAccount::getId, Function.identity(), (a, b) -> a));
        List<AllowlistView> views = new ArrayList<>();
        for (AutoSpottingAllowlistEntry row : rows) {
            CloudAccount account = byId.get(row.getCloudAccountId());
            Long clientId = account != null && account.getClient() != null ? account.getClient().getId() : null;
            boolean inForce = row.getContractId() != null && contracts.findById(row.getContractId())
                    .map(c -> c.inForceAt(now)).orElse(false);
            views.add(new AllowlistView(row.getCloudAccountId(), row.getProvider().name(),
                    account == null ? null : account.getAccountName(), clientId, row.getBillingBasis(),
                    row.getContractId(), inForce, row.getReason(), row.getAddedBy(), row.getAddedAt()));
        }
        views.sort(java.util.Comparator.comparing(AllowlistView::addedAt).reversed());
        return views;
    }

    /** Adds the account, or changes the basis of an existing row. */
    @Transactional
    public AllowlistView add(long cloudAccountId, String billingBasis, String reason, Actor actor,
                             String actorLabel) {
        CloudAccount account = accounts.findById(cloudAccountId).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Account not found."));
        Provider provider = providerOf(account);
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.length() > 500) {
            throw invalid("reason is at most 500 characters.");
        }
        UUID contractId = null;
        if (CONTRACT.equals(billingBasis)) {
            if (trimmedReason.isEmpty()) {
                throw invalid("reason is required, 1 to 500 characters.");
            }
            Long clientId = account.getClient() == null ? null : account.getClient().getId();
            List<AutoSpottingContract> inForce = clientId == null ? List.of()
                    : contracts.findInForce(clientId, Instant.now());
            if (inForce.isEmpty()) {
                throw needsBasis("The account's client has no contract in force. Choose \"Never bills\" with a reason.");
            }
            contractId = inForce.get(0).getId();
        } else if (NO_FEE.equals(billingBasis)) {
            if (trimmedReason.isEmpty()) {
                throw needsBasis("A reason is required when the account never bills.");
            }
        } else {
            throw needsBasis("A billing basis is required: CONTRACT or NO_FEE.");
        }

        AutoSpottingAllowlistEntry existing = allowlist.findById(cloudAccountId).orElse(null);
        String before = existing == null ? null : describe(existing);
        AutoSpottingAllowlistEntry row = existing != null ? existing : new AutoSpottingAllowlistEntry();
        row.setCloudAccountId(cloudAccountId);
        row.setProvider(provider);
        row.setBillingBasis(billingBasis);
        row.setContractId(contractId);
        row.setReason(trimmedReason);
        row.setAddedBy(actorLabel);
        row.setAddedAt(Instant.now());
        AutoSpottingAllowlistEntry saved = allowlist.save(row);
        auditWriter.write(AuditRecord.builder("OPERATOR_ALLOWLIST", actor,
                        (existing == null ? "ALLOWLIST_ADD:" : "ALLOWLIST_CHANGE:") + cloudAccountId, "SUCCESS")
                .provider(provider.name())
                .cloudAccountId(cloudAccountId)
                .clientId(account.getClient() == null ? null : account.getClient().getId())
                .beforeState(before)
                .afterState(describe(saved))
                .build());
        return view(saved, account);
    }

    @Transactional
    public void remove(long cloudAccountId, String reason, Actor actor) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 500) {
            throw invalid("reason is required, 1 to 500 characters.");
        }
        AutoSpottingAllowlistEntry row = allowlist.findById(cloudAccountId).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Allowlist entry not found."));
        String before = describe(row);
        CloudAccount account = accounts.findById(cloudAccountId).orElse(null);
        allowlist.delete(row);
        auditWriter.write(AuditRecord.builder("OPERATOR_ALLOWLIST", actor,
                        "ALLOWLIST_REMOVE:" + cloudAccountId, "SUCCESS")
                .provider(row.getProvider().name())
                .cloudAccountId(cloudAccountId)
                .clientId(account == null || account.getClient() == null ? null : account.getClient().getId())
                .beforeState(before)
                .afterState("{\"removed\":true,\"reason\":\"" + escape(trimmed) + "\"}")
                .build());
    }

    private AllowlistView view(AutoSpottingAllowlistEntry row, CloudAccount account) {
        Instant now = Instant.now();
        boolean inForce = row.getContractId() != null && contracts.findById(row.getContractId())
                .map(c -> c.inForceAt(now)).orElse(false);
        return new AllowlistView(row.getCloudAccountId(), row.getProvider().name(), account.getAccountName(),
                account.getClient() == null ? null : account.getClient().getId(), row.getBillingBasis(),
                row.getContractId(), inForce, row.getReason(), row.getAddedBy(), row.getAddedAt());
    }

    private static Provider providerOf(CloudAccount account) {
        if ("AWS".equalsIgnoreCase(account.getProvider())) {
            return Provider.AWS;
        }
        if ("GCP".equalsIgnoreCase(account.getProvider())) {
            return Provider.GCP;
        }
        throw invalid("Only AWS and GCP accounts can be allowlisted.");
    }

    private static String describe(AutoSpottingAllowlistEntry row) {
        return "{\"billingBasis\":\"" + row.getBillingBasis() + "\",\"contractId\":"
                + (row.getContractId() == null ? "null" : "\"" + row.getContractId() + "\"")
                + ",\"reason\":\"" + escape(row.getReason()) + "\"}";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static AutoSpottingApiException invalid(String detail) {
        return new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED", detail);
    }

    private static AutoSpottingApiException needsBasis(String detail) {
        return new AutoSpottingApiException(HttpStatus.CONFLICT, "ALLOWLIST_NEEDS_BILLING_BASIS", detail);
    }
}
