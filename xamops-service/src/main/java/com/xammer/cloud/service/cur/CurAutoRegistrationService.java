package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.AwsBillingSource;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CurSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Connects an account to its Cost and Usage Report without anyone having to call the
 * admin API by hand.
 *
 * <h2>Why this exists</h2>
 * Onboarding already asks where cost data should come from, and for "create a report in
 * this account" it sends {@code CurS3Bucket=AUTO} to the CloudFormation stack — which
 * creates a bucket at a name both stacks derive independently. But nothing persisted
 * that choice, so the report existed in S3 with a role able to read it, and XamOps still
 * knew nothing about it. Finishing the setup meant three hand-written API calls, which
 * is not a reasonable thing to ask of the person who already answered the question in
 * the UI.
 *
 * <h2>What it will and will not guess</h2>
 * It reconstructs only the location the XamOps cost reporting stack itself creates:
 * bucket {@code xamops-cur-<accountId>}, prefix {@code cur}, report {@code xamops-cur}.
 * Those are the stack's own defaults, not a hopeful guess — if that report is not there,
 * the manifest read fails and the caller gets the reason.
 *
 * <p>It never guesses for a customer's pre-existing report. Those have arbitrary names,
 * and inventing one would produce a source that validates against nothing and reports $0
 * — the failure this whole feature is built to avoid.
 */
@Service
public class CurAutoRegistrationService {

    private static final Logger logger = LoggerFactory.getLogger(CurAutoRegistrationService.class);

    /** Defaults baked into xamops-cur-report.yml. Keep in step with that template. */
    private static final String CONVENTIONAL_PREFIX = "cur";
    private static final String CONVENTIONAL_REPORT = "xamops-cur";

    /** AWS::CUR::ReportDefinition is only supported here, so the bucket is here too. */
    private static final String CUR_REGION = "us-east-1";

    private final CurSourceRepository curSourceRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final CurSourceValidationService validationService;
    private final CurCostDataSource curCostDataSource;

    public CurAutoRegistrationService(CurSourceRepository curSourceRepository,
                                      CloudAccountRepository cloudAccountRepository,
                                      CurSourceValidationService validationService,
                                      CurCostDataSource curCostDataSource) {
        this.curSourceRepository = curSourceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.validationService = validationService;
        this.curCostDataSource = curCostDataSource;
    }

    /**
     * Makes this account's CUR usable, doing whatever is still outstanding.
     *
     * <p>Idempotent and safe to call on every button press: an account already wired up
     * returns its existing source untouched.
     *
     * @return the now-ACTIVE source
     * @throws IllegalStateException with a message naming what could not be completed
     */
    @Transactional
    public CurSource ensureRegistered(CloudAccount account) {
        String accountId = account.getAwsAccountId();

        // 1. Already wired up and healthy — nothing to do, unless the discovered metadata
        //    is incomplete.
        //
        //    ACTIVE is not the same as fully known. Granularity is probed with a real
        //    query, and that probe swallows its own failure so a transient Athena problem
        //    cannot fail an otherwise good source. The cost is that a source can sit ACTIVE
        //    with timeUnit null — and null means supportsDailyGranularity() is false, so
        //    every daily chart silently routes to Cost Explorer forever. Re-validating here
        //    is what repairs it, rather than waiting for someone to notice the daily charts
        //    never came from the report.
        List<CurSource> active = curSourceRepository.findActiveByAwsAccountId(accountId);
        if (!active.isEmpty()) {
            CurSource source = active.get(0);
            if (source.getTimeUnit() != null) {
                return source;
            }
            logger.info("[CUR] Source {} is ACTIVE but its granularity is unknown — re-validating",
                    source.getId());
            CurSource revalidated = validationService.validate(source, account);
            // A failed re-validation must not take a working source offline: it still
            // answers every query that does not need day buckets.
            return revalidated.isActive() ? revalidated : source;
        }

        // 2. A source is attached but not ACTIVE. Usually it was registered before the
        //    first delivery landed, so validation had no manifest to read. Retry it —
        //    that is the whole reason a FAILED source is worth keeping rather than
        //    deleting.
        CurSource attached = account.getCurSource();
        if (attached != null) {
            logger.info("[CUR] Re-validating {} source {} for account {}",
                    attached.getStatus(), attached.getId(), accountId);
            CurSource revalidated = validationService.validate(attached, account);
            if (!revalidated.isActive()) {
                throw new IllegalStateException("The report for this account could not be read: "
                        + revalidated.getStatusDetail());
            }
            return revalidated;
        }

        // 3. Nothing attached. Adopt a source already pointing at this account's
        //    conventional bucket before creating another — re-onboarding the same account
        //    must not accumulate duplicate rows for one report.
        String bucket = conventionalBucket(accountId);
        CurSource source = curSourceRepository
                .findByS3BucketAndReportName(bucket, CONVENTIONAL_REPORT)
                .orElseGet(() -> createConventionalSource(account, bucket));

        CurSource validated = validationService.validate(source, account);
        if (!validated.isActive()) {
            throw new IllegalStateException(
                    "No Cost and Usage Report found at s3://" + bucket + "/" + CONVENTIONAL_PREFIX
                    + "/" + CONVENTIONAL_REPORT + " — " + validated.getStatusDetail()
                    + ". If this account uses a report at a different location, register it"
                    + " explicitly instead.");
        }

        attachToAccount(account, validated);
        return validated;
    }

    private String conventionalBucket(String accountId) {
        return "xamops-cur-" + accountId;
    }

    private CurSource createConventionalSource(CloudAccount account, String bucket) {
        CurSource source = new CurSource();
        source.setName(account.getAccountName() + " (" + account.getAwsAccountId() + ")");
        source.setOwnerAccountId(account.getAwsAccountId());
        // The bucket is in the customer's own account, so it is reached by assuming that
        // account's onboarding role — the same path every other AWS call for it takes.
        source.setXamopsOwned(false);
        source.setS3Bucket(bucket);
        source.setS3Prefix(CONVENTIONAL_PREFIX);
        source.setReportName(CONVENTIONAL_REPORT);
        source.setRegion(CUR_REGION);
        source.setAthenaDatabase("xamops_cur");
        source.setAthenaTable("xamops_cur_" + account.getAwsAccountId());
        // Must match the prefix the CloudFormation role policy grants s3:PutObject on.
        source.setAthenaResultsLocation("s3://" + bucket + "/xamops-athena-results/");
        source.setStatus("PENDING");

        logger.info("[CUR] Auto-registering conventional source for account {} at s3://{}/{}",
                account.getAwsAccountId(), bucket, CONVENTIONAL_PREFIX);
        return curSourceRepository.save(source);
    }

    /**
     * Points the account at the source it was just validated against.
     *
     * <p>The presence check every other assignment runs is skipped deliberately: this
     * source was derived from the account's own id, so it cannot be the wrong payer's
     * report — the mistake that check exists to catch. A brand-new report may also have
     * no rows for the current month yet, and refusing on that would block the very case
     * this method is for.
     */
    private void attachToAccount(CloudAccount account, CurSource source) {
        account.setCurSource(source);
        account.setAwsBillingSource(AwsBillingSource.OWN_CUR);
        cloudAccountRepository.save(account);
        logger.info("✅ [CUR] Account {} now reads from source {}",
                account.getAwsAccountId(), source.getId());
    }

    /** Whether this account has an ACTIVE source right now, without changing anything. */
    public Optional<CurSource> currentSource(String awsAccountId) {
        return curSourceRepository.findActiveByAwsAccountId(awsAccountId).stream().findFirst();
    }
}
