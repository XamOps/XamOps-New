package com.xammer.cloud.controller;

import com.xammer.cloud.domain.AwsBillingSource;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CurSourceRepository;
import com.xammer.cloud.service.cur.CurCostDataSource;
import com.xammer.cloud.service.cur.CurIngestionService;
import com.xammer.cloud.service.cur.CurReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registers and inspects the Cost and Usage Reports XamOps can read.
 *
 * <p>Admin-only and deliberately small: this is the surface that decides which
 * accounts stop reading from the Cost Explorer API, and pointing an account at a
 * report that does not contain it makes every cost widget render $0 rather than
 * fail. Assigning an account to a source therefore requires an explicit billing
 * source, and the source must already have validated.
 */
@RestController
@RequestMapping("/api/xamops/admin/cur-sources")
// Matches the platform-admin set the rest of the codebase already uses — see
// CloudAccountAccessService.isPlatformAdmin, which treats these three as equivalent for
// reaching any account. Restricting this to ROLE_SUPER_ADMIN alone was an arbitrary
// choice that made the endpoints unreachable for admins who administer everything else.
// Still admin-only: registering a report and assigning accounts changes which source a
// customer's cost figures come from.
@PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'ROLE_XAMOPS_ADMIN', 'ROLE_BILLOPS_ADMIN')")
public class CurSourceAdminController {

    private static final Logger logger = LoggerFactory.getLogger(CurSourceAdminController.class);

    private final CurSourceRepository curSourceRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final CurIngestionService ingestionService;
    private final CurReconciliationService reconciliationService;
    private final CurCostDataSource curCostDataSource;

    public CurSourceAdminController(CurSourceRepository curSourceRepository,
                                    CloudAccountRepository cloudAccountRepository,
                                    CurIngestionService ingestionService,
                                    CurReconciliationService reconciliationService,
                                    CurCostDataSource curCostDataSource) {
        this.curSourceRepository = curSourceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.ingestionService = ingestionService;
        this.reconciliationService = reconciliationService;
        this.curCostDataSource = curCostDataSource;
    }

    @GetMapping
    public ResponseEntity<List<CurSource>> list() {
        return ResponseEntity.ok(curSourceRepository.findAll());
    }

    /**
     * Registers a report and immediately validates it.
     *
     * <p>The source is saved before validation so a failure is recorded against a
     * real row with a readable {@code statusDetail}, rather than being lost in a
     * 500 the operator has to correlate with logs.
     */
    @PostMapping
    public ResponseEntity<CurSource> register(@RequestBody CurSource request) {
        CurSource source = new CurSource();
        source.setName(request.getName());
        source.setOwnerAccountId(request.getOwnerAccountId());
        source.setXamopsOwned(request.isXamopsOwned());
        // Required for every XamOps MPA except the one the application itself runs in —
        // those are separate AWS accounts that host credentials cannot reach.
        source.setAssumeRoleArn(request.getAssumeRoleArn());
        source.setAssumeRoleExternalId(request.getAssumeRoleExternalId());
        source.setS3Bucket(request.getS3Bucket());
        source.setS3Prefix(request.getS3Prefix());
        source.setReportName(request.getReportName());
        source.setRegion(request.getRegion());

        // Athena names and the results location are derivable, and getting them wrong is
        // the difference between a working source and one that fails at provisioning
        // time. Default them so a caller supplies only the S3 coordinates.
        source.setAthenaDatabase(orDefault(request.getAthenaDatabase(), "xamops_cur"));
        source.setAthenaTable(orDefault(request.getAthenaTable(),
                sanitiseIdentifier(request.getReportName())));
        // Must match the prefix the CloudFormation role policy grants s3:PutObject on.
        source.setAthenaResultsLocation(orDefault(request.getAthenaResultsLocation(),
                "s3://" + request.getS3Bucket() + "/xamops-athena-results/"));
        if (request.getAthenaWorkgroup() != null) {
            source.setAthenaWorkgroup(request.getAthenaWorkgroup());
        }
        if (request.getCurVersion() != null) {
            source.setCurVersion(request.getCurVersion());
        }
        source.setStatus("PENDING");

        CurSource saved = curSourceRepository.save(source);
        logger.info("[CUR] Registered source {} ({}), validating", saved.getId(), saved.getS3Bucket());
        return ResponseEntity.ok(ingestionService.revalidate(saved));
    }

    /** Re-reads the manifest for one source. */
    @PostMapping("/{id}/validate")
    public ResponseEntity<?> validate(@PathVariable Long id) {
        Optional<CurSource> source = curSourceRepository.findById(id);
        if (source.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ingestionService.revalidate(source.get()));
    }

    /**
     * Compares CUR and Cost Explorer totals for one account over one closed month.
     *
     * <p>Run this before switching an account: the two sources must agree, because a
     * user changing a date filter can move between them and must not see the total
     * jump. Defaults to last month, the most recent month stable on both sides.
     */
    @GetMapping("/reconcile/{awsAccountId}")
    public ResponseEntity<?> reconcile(@PathVariable String awsAccountId,
                                       @RequestParam(required = false) String period,
                                       @RequestParam(required = false) Long curSourceId) {

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(awsAccountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CloudAccount account = accounts.get(0);

        CurSource source = curSourceId != null
                ? curSourceRepository.findById(curSourceId).orElse(null)
                : curSourceRepository.findActiveByAwsAccountId(awsAccountId).stream().findFirst().orElse(null);
        if (source == null) {
            return badRequest("No CUR source resolved for " + awsAccountId
                    + " — pass curSourceId, or assign one first");
        }

        YearMonth target = period != null ? YearMonth.parse(period) : YearMonth.now().minusMonths(1);
        try {
            return ResponseEntity.ok(reconciliationService.reconcile(source, account, target).toMap());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /** The accounts currently served by a source. */
    @GetMapping("/{id}/accounts")
    public ResponseEntity<List<String>> accounts(@PathVariable Long id) {
        return ResponseEntity.ok(cloudAccountRepository.findByCurSourceId(id).stream()
                .map(CloudAccount::getAwsAccountId)
                .toList());
    }

    /**
     * Points an AWS account at a CUR source, or back at Cost Explorer.
     *
     * <p>Refuses to attach a source that is not ACTIVE. An account attached to an
     * unvalidated source reads a table that may not exist or may not contain it,
     * and a CUR query returning no rows is indistinguishable from an account that
     * spent nothing — the GCP reseller path shipped that exact bug
     * (V20260818_2__gcp_billing_source.sql) and it is worth one guard here.
     */
    @PutMapping("/assign/{awsAccountId}")
    public ResponseEntity<?> assign(@PathVariable String awsAccountId,
                                    @RequestParam AwsBillingSource billingSource,
                                    @RequestParam(required = false) Long curSourceId,
                                    @RequestParam(defaultValue = "false") boolean skipPresenceCheck) {

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(awsAccountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CurSource source = null;
        if (billingSource.usesCur()) {
            if (curSourceId == null) {
                return badRequest("billingSource " + billingSource + " requires a curSourceId");
            }
            source = curSourceRepository.findById(curSourceId).orElse(null);
            if (source == null) {
                return badRequest("No CUR source with id " + curSourceId);
            }
            if (!source.isActive()) {
                return badRequest("CUR source " + curSourceId + " is " + source.getStatus()
                        + " (" + source.getStatusDetail() + ") — validate it before assigning accounts");
            }
            // A XamOps-owned report is read with our own credentials; a customer-owned one
            // by assuming that account's role. Mismatching these produces an access denial
            // at query time that reads as a broken CUR rather than a mis-registration.
            if (billingSource.usesXamopsOwnedCur() != source.isXamopsOwned()) {
                return badRequest("billingSource " + billingSource + " does not match source ownership"
                        + " (xamopsOwned=" + source.isXamopsOwned() + ")");
            }
            // With several XamOps MPAs, each with its own CUR, picking the wrong source is
            // an easy mistake and a silent one: the query succeeds, matches nothing, and
            // the account renders $0. Refuse the assignment instead.
            if (!skipPresenceCheck && !curCostDataSource.containsAccount(source, accounts.get(0))) {
                return badRequest("Account " + awsAccountId + " has no rows in CUR source "
                        + curSourceId + " (" + source.getName() + "). It is probably under a"
                        + " different payer account. Pass skipPresenceCheck=true only if the"
                        + " account is newly added and its first CUR delivery has not landed.");
            }
        }

        for (CloudAccount account : accounts) {
            account.setAwsBillingSource(billingSource);
            account.setCurSource(source);
            cloudAccountRepository.save(account);
        }

        logger.info("[CUR] Account {} assigned billingSource={} curSourceId={}",
                awsAccountId, billingSource, curSourceId);

        Map<String, Object> body = new HashMap<>();
        body.put("awsAccountId", awsAccountId);
        body.put("billingSource", billingSource);
        body.put("curSourceId", curSourceId);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Report names allow characters Athena identifiers do not, e.g. {@code xamops-cur}. */
    private static String sanitiseIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "cur";
        }
        String cleaned = value.toLowerCase().replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        // Athena identifiers cannot start with a digit.
        return cleaned.matches("^[0-9].*") ? "t_" + cleaned : cleaned;
    }
}
