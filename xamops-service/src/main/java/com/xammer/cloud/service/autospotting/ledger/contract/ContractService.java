package com.xammer.cloud.service.autospotting.ledger.contract;

import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.autospotting.ledger.AutoSpottingContractRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contract records (design-ledger.md §2.3, §11.4; PO-01, PO-06, PO-16). A contract is a record only:
 * nothing is invoiced from it while billing stays off. The database trigger is the backstop for
 * non-overlap, no backdating and immutable terms; this service validates first so the admin gets a
 * named error, and maps the trigger's rejection when two requests race.
 *
 * <p>Rules enforced here: {@code 0 < sharePct <= 100}; a cap is null or {@code > 0} with at most two
 * decimals and is always USD; {@code discountDeclaration} is required, {@code DECLARED} needs a
 * percentage in {@code [0,100)} (0 means "declared none") and {@code NOT_DECLARED} stores null, so a
 * missing value is never stored as 0 (ledger §5.1); dates are UTC instants no earlier than the
 * request time. A contract's terms never change: it can only be ended earlier.
 */
@Service
public class ContractService {

    public static final String DECLARED = "DECLARED";
    public static final String NOT_DECLARED = "NOT_DECLARED";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);

    private final AutoSpottingContractRepository contracts;
    private final ClientRepository clients;
    private final AutoSpottingAuditWriter auditWriter;

    public ContractService(AutoSpottingContractRepository contracts, ClientRepository clients,
                           AutoSpottingAuditWriter auditWriter) {
        this.contracts = contracts;
        this.clients = clients;
        this.auditWriter = auditWriter;
    }

    /** The new-contract input. {@code effectiveFrom} null means "now". */
    public record CreateCommand(BigDecimal sharePct, BigDecimal monthlyCap, String capCurrency,
                                String discountDeclaration, BigDecimal declaredDiscountPct,
                                Instant effectiveFrom, Instant effectiveTo, String reason) {
    }

    @Transactional(readOnly = true)
    public List<AutoSpottingContract> list(long clientId) {
        return contracts.findByClientIdOrderByEffectiveFromDesc(clientId);
    }

    @Transactional
    public AutoSpottingContract create(long clientId, CreateCommand cmd, Actor actor, String actorLabel) {
        requireReason(cmd.reason());
        if (!clients.existsById(clientId)) {
            throw new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Client not found.");
        }
        BigDecimal share = cmd.sharePct();
        if (share == null || share.signum() <= 0 || share.compareTo(HUNDRED) > 0) {
            throw invalid("sharePct must be greater than 0 and at most 100.");
        }
        if (share.scale() > 6 && share.stripTrailingZeros().scale() > 6) {
            throw invalid("sharePct allows at most 6 decimals.");
        }
        BigDecimal cap = cmd.monthlyCap();
        if (cap != null && (cap.signum() <= 0 || cap.stripTrailingZeros().scale() > 2)) {
            throw invalid("monthlyCap must be greater than 0 with at most 2 decimals.");
        }
        if (cmd.capCurrency() != null && !"USD".equals(cmd.capCurrency())) {
            throw invalid("The cap is in USD only.");
        }
        BigDecimal discount = resolveDiscount(cmd.discountDeclaration(), cmd.declaredDiscountPct());

        Instant now = Instant.now();
        Instant from = cmd.effectiveFrom() == null ? now : cmd.effectiveFrom();
        if (cmd.effectiveFrom() != null && cmd.effectiveFrom().isBefore(now)) {
            throw invalid("effectiveFrom cannot be in the past.");
        }
        if (cmd.effectiveTo() != null
                && (cmd.effectiveTo().isBefore(now) || !cmd.effectiveTo().isAfter(from))) {
            throw invalid("effectiveTo must be in the future and after effectiveFrom.");
        }
        for (AutoSpottingContract other : contracts.findByClientIdOrderByEffectiveFromDesc(clientId)) {
            if (overlaps(other.getEffectiveFrom(), other.getEffectiveTo(), from, cmd.effectiveTo())) {
                throw overlap();
            }
        }

        AutoSpottingContract contract = new AutoSpottingContract();
        contract.setId(UUID.randomUUID());
        contract.setClientId(clientId);
        contract.setSharePct(share);
        contract.setMonthlyCap(cap);
        contract.setCapCurrency("USD");
        contract.setDeclaredDiscountPct(discount);
        contract.setEffectiveFrom(from);
        contract.setEffectiveTo(cmd.effectiveTo());
        contract.setCreatedBy(actorLabel);
        contract.setCreatedAt(Instant.now());
        AutoSpottingContract saved = flush(() -> contracts.saveAndFlush(contract));
        auditWriter.write(AuditRecord.builder("BILLOPS_ADMIN", actor, "CONTRACT_CREATED", "SUCCESS")
                .clientId(clientId)
                .afterState(describe(saved, cmd.reason()))
                .build());
        return saved;
    }

    /**
     * Ends a contract earlier ({@code effectiveTo} null means now). Terms never change, and a contract
     * is never extended: that is a new record from a later month start (ledger §2.3).
     */
    @Transactional
    public AutoSpottingContract end(UUID contractId, Instant effectiveTo, Actor actor, String reason) {
        requireReason(reason);
        AutoSpottingContract contract = contracts.findById(contractId).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Contract not found."));
        Instant now = Instant.now();
        Instant to = effectiveTo == null ? now : effectiveTo;
        if (effectiveTo != null && effectiveTo.isBefore(now)) {
            throw invalid("effectiveTo cannot be in the past.");
        }
        if (!to.isAfter(contract.getEffectiveFrom())) {
            throw invalid("effectiveTo must be after the contract's effectiveFrom.");
        }
        if (contract.getEffectiveTo() != null && !to.isBefore(contract.getEffectiveTo())) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "CONTRACT_CANNOT_EXTEND",
                    "A contract can only be ended earlier. Create a new contract for later terms.");
        }
        String before = describe(contract, null);
        contract.setEffectiveTo(to);
        AutoSpottingContract saved = flush(() -> contracts.saveAndFlush(contract));
        auditWriter.write(AuditRecord.builder("BILLOPS_ADMIN", actor, "CONTRACT_ENDED", "SUCCESS")
                .clientId(saved.getClientId())
                .beforeState(before)
                .afterState(describe(saved, reason))
                .build());
        return saved;
    }

    static boolean overlaps(Instant aFrom, Instant aTo, Instant bFrom, Instant bTo) {
        boolean aStartsBeforeBEnds = bTo == null || aFrom.isBefore(bTo);
        boolean bStartsBeforeAEnds = aTo == null || bFrom.isBefore(aTo);
        return aStartsBeforeBEnds && bStartsBeforeAEnds;
    }

    private static BigDecimal resolveDiscount(String declaration, BigDecimal pct) {
        if (declaration == null) {
            throw invalid("discountDeclaration is required: DECLARED or NOT_DECLARED.");
        }
        switch (declaration) {
            case DECLARED:
                if (pct == null || pct.signum() < 0 || pct.compareTo(HUNDRED) >= 0) {
                    throw invalid("declaredDiscountPct is required with DECLARED, from 0 up to but not including 100.");
                }
                return pct;
            case NOT_DECLARED:
                if (pct != null) {
                    throw invalid("declaredDiscountPct must be absent with NOT_DECLARED.");
                }
                return null;
            default:
                throw invalid("discountDeclaration must be DECLARED or NOT_DECLARED.");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw invalid("reason is required, 1 to 500 characters.");
        }
    }

    private static AutoSpottingApiException invalid(String detail) {
        return new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED", detail);
    }

    private static AutoSpottingApiException overlap() {
        return new AutoSpottingApiException(HttpStatus.CONFLICT, "CONTRACT_OVERLAP",
                "The contract overlaps another contract of this client.");
    }

    /** Runs a flush and maps the contract trigger's rejection (a racing request) to a named error. */
    private static <T> T flush(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                String message = t.getMessage();
                if (message != null && message.contains("contract overlaps")) {
                    throw overlap();
                }
                if (message != null && message.contains("cannot be backdated")) {
                    throw invalid("Contract dates cannot be in the past.");
                }
                if (t.getCause() == t) {
                    break;
                }
            }
            throw e;
        }
    }

    private static String describe(AutoSpottingContract c, String reason) {
        StringBuilder sb = new StringBuilder("{\"contractId\":\"").append(c.getId()).append('"')
                .append(",\"sharePct\":\"").append(c.getSharePct().toPlainString()).append('"')
                .append(",\"monthlyCap\":").append(c.getMonthlyCap() == null ? "null"
                        : "\"" + c.getMonthlyCap().toPlainString() + "\"")
                .append(",\"declaredDiscountPct\":").append(c.getDeclaredDiscountPct() == null ? "null"
                        : "\"" + c.getDeclaredDiscountPct().toPlainString() + "\"")
                .append(",\"effectiveFrom\":\"").append(c.getEffectiveFrom()).append('"')
                .append(",\"effectiveTo\":").append(c.getEffectiveTo() == null ? "null"
                        : "\"" + c.getEffectiveTo() + "\"");
        if (reason != null) {
            sb.append(",\"reason\":\"").append(reason.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
