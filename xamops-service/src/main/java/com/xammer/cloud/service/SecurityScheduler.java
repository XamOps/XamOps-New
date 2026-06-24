package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    // Gap A: GCP Prowler frameworks — parity with AWS list
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
     * 2:00 AM daily — triggers Prowler for ALL accounts (AWS + GCP).
     */
    public void runNightlySecurityScans() {
        logger.info("🕒 Starting Nightly Security Scan for all accounts...");

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
                    // Gap A: GCP Prowler nightly scan
                    String gcpProjectId = account.getGcpProjectId();
                    if (gcpProjectId == null || gcpProjectId.isEmpty()) continue;

                    Map<String, Object> status = prowlerService.getGcpScanStatus(gcpProjectId);
                    if ("RUNNING".equals(status.get("status"))) {
                        logger.info("⏭ GCP Prowler already running for project={} — skipping", gcpProjectId);
                        continue;
                    }

                    logger.info("🔍 Triggering GCP Prowler scan for project={}", gcpProjectId);
                    prowlerService.triggerGcpScanAsync(gcpProjectId, GCP_FRAMEWORKS);
                    gcpCount++;

                } else {
                    // AWS (default)
                    String awsAccountId = account.getAwsAccountId();
                    if (awsAccountId == null || awsAccountId.isEmpty()) continue;

                    logger.info("🔍 Triggering AWS Prowler scan for account={}", awsAccountId);
                    prowlerService.triggerScanAsync(awsAccountId, "us-east-1", AWS_SERVICES, AWS_FRAMEWORKS);
                    awsCount++;
                }

            } catch (Exception e) {
                logger.error("Failed to trigger nightly scan for account {}", account.getId(), e);
            }
        }

        logger.info("🕒 Nightly scans triggered — AWS: {} accounts, GCP: {} projects", awsCount, gcpCount);
    }
}
