package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.security.ProwlerFrameworks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SecurityScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SecurityScheduler.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final ProwlerService prowlerService;

    // Service groups and framework lists live in ProwlerFrameworks so this scheduler and the
    // manual-trigger controllers cannot drift. They had: the GCP list here asked Prowler for
    // pci_3.2.1_gcp and iso27001_2013_gcp, which GCP does not offer, and one unknown name makes
    // Prowler reject the whole --compliance argument and scan nothing.

    // Key regions that cover most AWS customer deployments.
    // Scanned sequentially per account — memory stays bounded even for 500 accounts.
    // IAM, S3, CloudTrail, Route53 are global and appear in any region scan.
    private static final List<String> AWS_REGIONS = List.of(
            "ap-south-1",    // Mumbai       — primary for India-based customers
            "ap-south-2",    // Hyderabad    — newer India region
            "us-east-1",     // N. Virginia  — many global-service endpoints report here
            "ap-southeast-1" // Singapore    — common SE Asia
    );

    @Autowired
    public SecurityScheduler(CloudAccountRepository cloudAccountRepository,
            ProwlerService prowlerService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.prowlerService = prowlerService;
    }

    /**
     * Every Saturday at 11:00 PM IST — scans ALL accounts (AWS, GCP and Azure). Results are
     * saved to DB and Redis so the UI reads cached data until the user explicitly clicks
     * "Run Compliance Scan".
     *
     * The Semaphore inside ProwlerService caps concurrent Prowler processes at 2,
     * so all 500+ accounts queue up safely without OOM.
     *
     * <p>
     * Azure used to be missing here. The loop branched on GCP and fell through to an
     * {@code else} that assumed AWS, so an Azure subscription reached the AWS branch, found a
     * null {@code awsAccountId} and skipped — silently, once a week, forever. Azure compliance
     * was therefore only ever as fresh as the last time somebody clicked the button by hand,
     * and ComplianceOps had nothing to report for those subscriptions. The dispatch is now an
     * explicit switch on the provider so an unrecognised one is logged rather than
     * mistaken for AWS.
     */
    @Scheduled(cron = "0 0 23 * * SAT", zone = "Asia/Kolkata")
    public void runWeeklySaturdayScan() {
        logger.info("Starting Weekly Saturday Compliance Scan for all accounts...");

        List<CloudAccount> accounts = cloudAccountRepository.findAll();
        if (accounts.isEmpty()) {
            logger.info("No accounts found to scan.");
            return;
        }

        int awsCount = 0;
        int gcpCount = 0;
        int azureCount = 0;
        int skipped = 0;

        for (CloudAccount account : accounts) {
            try {
                String provider = account.getProvider() == null ? "" : account.getProvider();

                switch (provider.toUpperCase(java.util.Locale.ROOT)) {
                    case "GCP" -> {
                        String gcpProjectId = account.getGcpProjectId();
                        if (isBlank(gcpProjectId)) {
                            skipped++;
                            logger.warn("Skipping GCP account {} — no project id", account.getId());
                            continue;
                        }
                        if (alreadyRunning(prowlerService.getGcpScanStatus(gcpProjectId), "GCP", gcpProjectId)) {
                            continue;
                        }
                        logger.info("Triggering GCP Prowler scan for project={}", gcpProjectId);
                        prowlerService.triggerGcpScanAsync(gcpProjectId, ProwlerFrameworks.GCP);
                        gcpCount++;
                    }
                    case "AZURE" -> {
                        String subscriptionId = account.getAzureSubscriptionId();
                        if (isBlank(subscriptionId)) {
                            skipped++;
                            logger.warn("Skipping Azure account {} — no subscription id", account.getId());
                            continue;
                        }
                        if (alreadyRunning(prowlerService.getAzureScanStatus(subscriptionId), "Azure",
                                subscriptionId)) {
                            continue;
                        }
                        logger.info("Triggering Azure Prowler scan for subscription={}", subscriptionId);
                        prowlerService.triggerAzureScanAsync(subscriptionId, ProwlerFrameworks.AZURE);
                        azureCount++;
                    }
                    case "AWS" -> {
                        String awsAccountId = account.getAwsAccountId();
                        if (isBlank(awsAccountId)) {
                            skipped++;
                            logger.warn("Skipping AWS account {} — no account id", account.getId());
                            continue;
                        }
                        if (alreadyRunning(prowlerService.getScanStatus(awsAccountId), "AWS", awsAccountId)) {
                            continue;
                        }
                        logger.info("Triggering AWS Prowler scan for account={} across {} regions",
                                awsAccountId, AWS_REGIONS.size());
                        prowlerService.triggerScanAsync(awsAccountId, AWS_REGIONS,
                                ProwlerFrameworks.AWS_SERVICES, ProwlerFrameworks.AWS);
                        awsCount++;
                    }
                    // Named rather than silently treated as AWS. The previous else-means-AWS
                    // default is exactly how Azure came to be skipped without a trace.
                    default -> {
                        skipped++;
                        logger.warn("No compliance scan configured for provider '{}' (account {}) — skipping",
                                provider.isBlank() ? "unset" : provider, account.getId());
                    }
                }

            } catch (Exception e) {
                logger.error("Failed to trigger Saturday scan for account {}", account.getId(), e);
            }
        }

        logger.info("Saturday scan triggered — AWS: {} accounts, GCP: {} projects, Azure: {} subscriptions, "
                + "{} skipped", awsCount, gcpCount, azureCount, skipped);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Avoids queueing a second Prowler process for an account already mid-scan. */
    private boolean alreadyRunning(Map<String, Object> status, String provider, String id) {
        if (status != null && "RUNNING".equals(status.get("status"))) {
            logger.info("{} Prowler already running for {} — skipping", provider, id);
            return true;
        }
        return false;
    }
}
