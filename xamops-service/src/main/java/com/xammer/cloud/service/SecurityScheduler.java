package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
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

    private static final String[] AWS_SERVICES = {
            "s3", "ec2", "iam", "rds", "cloudtrail", "cloudwatch",
            "kms", "lambda", "vpc", "config", "sns", "sqs",
            "efs", "elb", "elbv2", "secretsmanager", "guardduty"
    };

    private static final String[] AWS_FRAMEWORKS = {
            "cis_3.0_aws",
            "soc2_aws",
            "pci_3.2.1_aws",
            "nist_csf_1.1_aws",
            "aws_foundational_security_best_practices_aws",
            "iso27001_2013_aws"
    };

    // Key regions that cover most AWS customer deployments.
    // Scanned sequentially per account — memory stays bounded even for 500 accounts.
    // IAM, S3, CloudTrail, Route53 are global and appear in any region scan.
    private static final List<String> AWS_REGIONS = List.of(
            "ap-south-1",    // Mumbai       — primary for India-based customers
            "ap-south-2",    // Hyderabad    — newer India region
            "us-east-1",     // N. Virginia  — many global-service endpoints report here
            "ap-southeast-1" // Singapore    — common SE Asia
    );

    private static final String[] GCP_FRAMEWORKS = {
            "cis_2.0_gcp", "soc2_gcp", "pci_3.2.1_gcp",
            "iso27001_2013_gcp", "mitre_attack_gcp",
            "hipaa_gcp", "gdpr_gcp", "nist_csf_1.1_gcp"
    };

    @Autowired
    public SecurityScheduler(CloudAccountRepository cloudAccountRepository,
            ProwlerService prowlerService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.prowlerService = prowlerService;
    }

    /**
     * Every Saturday at 11:00 PM IST — scans ALL accounts (AWS + GCP) across
     * all key regions. Results are saved to DB and Redis so the UI reads cached
     * data until the user explicitly clicks "Run Compliance Scan".
     *
     * The Semaphore inside ProwlerService caps concurrent Prowler processes at 2,
     * so all 500+ accounts queue up safely without OOM.
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

        for (CloudAccount account : accounts) {
            try {
                String provider = account.getProvider();

                if ("GCP".equalsIgnoreCase(provider)) {
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;

                    Map<String, Object> status = prowlerService.getGcpScanStatus(gcpProjectId);
                    if ("RUNNING".equals(status.get("status"))) {
                        logger.info("GCP Prowler already running for project={} — skipping", gcpProjectId);
                        continue;
                    }

                    logger.info("Triggering GCP Prowler scan for project={}", gcpProjectId);
                    prowlerService.triggerGcpScanAsync(gcpProjectId, GCP_FRAMEWORKS);
                    gcpCount++;

                } else {
                    // AWS (default)
                    String awsAccountId = account.getAwsAccountId();
                    if (awsAccountId == null || awsAccountId.isEmpty()) continue;

                    Map<String, Object> status = prowlerService.getScanStatus(awsAccountId);
                    if ("RUNNING".equals(status.get("status"))) {
                        logger.info("AWS Prowler already running for account={} — skipping", awsAccountId);
                        continue;
                    }

                    logger.info("Triggering AWS Prowler scan for account={} across {} regions",
                            awsAccountId, AWS_REGIONS.size());
                    prowlerService.triggerScanAsync(awsAccountId, AWS_REGIONS, AWS_SERVICES, AWS_FRAMEWORKS);
                    awsCount++;
                }

            } catch (Exception e) {
                logger.error("Failed to trigger Saturday scan for account {}", account.getId(), e);
            }
        }

        logger.info("Saturday scan triggered — AWS: {} accounts, GCP: {} projects", awsCount, gcpCount);
    }
}
