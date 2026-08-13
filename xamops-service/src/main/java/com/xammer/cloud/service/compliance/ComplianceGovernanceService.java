package com.xammer.cloud.service.compliance;

import com.xammer.cloud.domain.compliance.ComplianceControlOwner;
import com.xammer.cloud.domain.compliance.ComplianceException;
import com.xammer.cloud.domain.compliance.ComplianceRemediation;
import com.xammer.cloud.repository.compliance.ComplianceControlOwnerRepository;
import com.xammer.cloud.repository.compliance.ComplianceExceptionRepository;
import com.xammer.cloud.repository.compliance.ComplianceRemediationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The human side of compliance: who owns a control, what has been formally accepted,
 * and what work is outstanding.
 *
 * Kept separate from {@link ComplianceOpsService} because the lifecycles differ. Scan
 * data is written once by a machine and never edited; these records are edited by people
 * and must outlive any individual scan.
 */
@Service
public class ComplianceGovernanceService {

    private static final Logger logger = LoggerFactory.getLogger(ComplianceGovernanceService.class);

    /**
     * Longest exception a request can ask for. An indefinite waiver is just an
     * undocumented risk acceptance, and a year is already long enough that the original
     * reasoning will have gone stale.
     */
    private static final int MAX_EXCEPTION_DAYS = 365;

    private final ComplianceExceptionRepository exceptionRepo;
    private final ComplianceControlOwnerRepository ownerRepo;
    private final ComplianceRemediationRepository remediationRepo;

    public ComplianceGovernanceService(ComplianceExceptionRepository exceptionRepo,
            ComplianceControlOwnerRepository ownerRepo,
            ComplianceRemediationRepository remediationRepo) {
        this.exceptionRepo = exceptionRepo;
        this.ownerRepo = ownerRepo;
        this.remediationRepo = remediationRepo;
    }

    // ── Exceptions ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ComplianceException> listExceptions(String accountId) {
        return exceptionRepo.findByCloudAccountIdOrderByCreatedAtDesc(accountId);
    }

    /**
     * Raise an exception request. Always created PENDING: the same person requesting a
     * waiver should not be able to grant it in one call, because separation of request
     * and approval is the only thing that makes the record mean anything.
     */
    @Transactional
    public ComplianceException requestException(String accountId, String checkId,
            String resourceUid, String justification, String compensatingControl,
            String requestedBy, Instant expiresAt) {

        require(notBlank(accountId), "accountId is required");
        require(notBlank(checkId), "checkId is required");
        require(notBlank(requestedBy), "requestedBy is required");
        require(notBlank(justification) && justification.trim().length() >= 20,
                "A justification of at least 20 characters is required — an auditor will read it");
        require(expiresAt != null, "expiresAt is required; exceptions cannot be open-ended");

        Instant now = Instant.now();
        require(expiresAt.isAfter(now), "expiresAt must be in the future");
        require(expiresAt.isBefore(now.plus(Duration.ofDays(MAX_EXCEPTION_DAYS))),
                "expiresAt cannot be more than " + MAX_EXCEPTION_DAYS + " days out");

        ComplianceException e = new ComplianceException();
        e.setCloudAccountId(accountId);
        e.setCheckId(checkId);
        e.setResourceUid(blankToNull(resourceUid));
        e.setJustification(justification.trim());
        e.setCompensatingControl(blankToNull(compensatingControl));
        e.setRequestedBy(requestedBy);
        e.setStatus(ComplianceException.PENDING);
        e.setCreatedAt(now);
        e.setExpiresAt(expiresAt);
        ComplianceException saved = exceptionRepo.save(e);
        logger.info("Compliance exception {} requested for {}/{} by {}",
                saved.getId(), accountId, checkId, requestedBy);
        return saved;
    }

    @Transactional
    public ComplianceException decideException(Long id, boolean approve, String approvedBy) {
        require(notBlank(approvedBy), "approvedBy is required");
        ComplianceException e = exceptionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exception not found: " + id));
        require(ComplianceException.PENDING.equals(e.getStatus()),
                "Only a PENDING exception can be decided; this one is " + e.getStatus());
        require(!approvedBy.equalsIgnoreCase(e.getRequestedBy()),
                "An exception cannot be approved by the person who requested it");

        e.setStatus(approve ? ComplianceException.APPROVED : ComplianceException.REJECTED);
        e.setApprovedBy(approvedBy);
        e.setApprovedAt(Instant.now());
        logger.info("Compliance exception {} {} by {}", id, e.getStatus(), approvedBy);
        return exceptionRepo.save(e);
    }

    @Transactional
    public ComplianceException revokeException(Long id, String reason, String actor) {
        ComplianceException e = exceptionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exception not found: " + id));
        require(ComplianceException.APPROVED.equals(e.getStatus()),
                "Only an APPROVED exception can be revoked");
        e.setStatus(ComplianceException.REVOKED);
        e.setRevokedAt(Instant.now());
        e.setRevokedReason(blankToNull(reason));
        logger.info("Compliance exception {} revoked by {}", id, actor);
        return exceptionRepo.save(e);
    }

    // ── Ownership ───────────────────────────────────────────────────────────

    /** Upsert, because ownership is a single current fact per control, not a log. */
    @Transactional
    public ComplianceControlOwner setOwner(String accountId, String checkId, String ownerName,
            String ownerEmail, String team, String notes, String actor) {
        require(notBlank(accountId), "accountId is required");
        require(notBlank(checkId), "checkId is required");
        require(notBlank(ownerName) || notBlank(ownerEmail) || notBlank(team),
                "Provide at least one of owner name, email or team");

        ComplianceControlOwner owner = ownerRepo
                .findByCloudAccountIdAndCheckId(accountId, checkId)
                .orElseGet(ComplianceControlOwner::new);
        owner.setCloudAccountId(accountId);
        owner.setCheckId(checkId);
        owner.setOwnerName(blankToNull(ownerName));
        owner.setOwnerEmail(blankToNull(ownerEmail));
        owner.setTeam(blankToNull(team));
        owner.setNotes(blankToNull(notes));
        owner.setUpdatedAt(Instant.now());
        owner.setUpdatedBy(actor);
        return ownerRepo.save(owner);
    }

    @Transactional(readOnly = true)
    public List<ComplianceControlOwner> listOwners(String accountId) {
        return ownerRepo.findByCloudAccountId(accountId);
    }

    // ── Remediation ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ComplianceRemediation> listRemediations(String accountId) {
        return remediationRepo.findByCloudAccountId(accountId);
    }

    @Transactional
    public ComplianceRemediation createRemediation(String accountId, String checkId,
            String resourceUid, String assigneeName, String assigneeEmail, String ticketRef,
            Instant dueAt, String notes, String actor) {
        require(notBlank(accountId), "accountId is required");
        require(notBlank(checkId), "checkId is required");

        ComplianceRemediation t = new ComplianceRemediation();
        t.setCloudAccountId(accountId);
        t.setCheckId(checkId);
        t.setResourceUid(blankToNull(resourceUid));
        t.setStatus(ComplianceRemediation.OPEN);
        t.setAssigneeName(blankToNull(assigneeName));
        t.setAssigneeEmail(blankToNull(assigneeEmail));
        t.setTicketRef(blankToNull(ticketRef));
        t.setDueAt(dueAt);
        t.setNotes(blankToNull(notes));
        t.setCreatedAt(Instant.now());
        t.setCreatedBy(actor);
        return remediationRepo.save(t);
    }

    /**
     * Move a remediation along.
     *
     * VERIFIED is deliberately not settable here. It is only ever set by
     * {@link ComplianceScanRecorder} when a scan observes the control passing, so the
     * status cannot be talked into existence.
     */
    @Transactional
    public ComplianceRemediation updateRemediation(Long id, String status, String assigneeName,
            String assigneeEmail, String ticketRef, Instant dueAt, String notes) {
        ComplianceRemediation t = remediationRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Remediation not found: " + id));

        if (notBlank(status)) {
            String next = status.toUpperCase();
            require(!ComplianceRemediation.VERIFIED.equals(next),
                    "VERIFIED is set by a scan that observes the control passing, not manually");
            require(List.of(ComplianceRemediation.OPEN, ComplianceRemediation.IN_PROGRESS,
                            ComplianceRemediation.RESOLVED, ComplianceRemediation.WONT_FIX).contains(next),
                    "Unknown remediation status: " + status);
            t.setStatus(next);
            if (ComplianceRemediation.RESOLVED.equals(next) && t.getResolvedAt() == null) {
                t.setResolvedAt(Instant.now());
            }
        }
        if (assigneeName != null) t.setAssigneeName(blankToNull(assigneeName));
        if (assigneeEmail != null) t.setAssigneeEmail(blankToNull(assigneeEmail));
        if (ticketRef != null) t.setTicketRef(blankToNull(ticketRef));
        if (dueAt != null) t.setDueAt(dueAt);
        if (notes != null) t.setNotes(blankToNull(notes));
        t.setUpdatedAt(Instant.now());
        return remediationRepo.save(t);
    }

    // ── Ownership lookups for authorization ─────────────────────────────────

    /*
     * The approve/reject/revoke/update endpoints address a record by its own id and never
     * name an account, so the caller has nothing to authorize against without first
     * asking which account the record belongs to. Both methods return null for an id
     * that does not exist, letting the caller answer "unknown" and "forbidden"
     * identically rather than confirming that someone else's record exists.
     */

    @Transactional(readOnly = true)
    public String findExceptionAccountId(Long id) {
        if (id == null) return null;
        return exceptionRepo.findById(id)
                .map(e -> e.getCloudAccountId())
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public String findRemediationAccountId(Long id) {
        if (id == null) return null;
        return remediationRepo.findById(id)
                .map(r -> r.getCloudAccountId())
                .orElse(null);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s.trim() : null;
    }
}
