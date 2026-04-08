package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SecurityScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SecurityScheduler.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final ProwlerService prowlerService;

    @Autowired
    public SecurityScheduler(CloudAccountRepository cloudAccountRepository,
            ProwlerService prowlerService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.prowlerService = prowlerService;
    }

    /**
     * Runs every night at 2 AM server time.
     * Triggers Prowler scans for all known AWS accounts.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void runNightlySecurityScans() {
        logger.info("🕒 Starting Nightly Security Scan for all accounts...");

        List<CloudAccount> accounts = cloudAccountRepository.findAll();

        if (accounts.isEmpty()) {
            logger.info("No accounts found to scan.");
            return;
        }

        for (CloudAccount account : accounts) {
            try {
                logger.info("Triggering background scan for account: {}", account.getAwsAccountId());

                // Trigger Prowler asynchronously with compliance frameworks.
                // The ProwlerService is responsible for updating the Cache/DB when it finishes.
                String[] services = {
                        "s3", "ec2", "iam", "rds", "cloudtrail", "cloudwatch",
                        "kms", "lambda", "vpc", "config", "sns", "sqs",
                        "efs", "elb", "elbv2", "secretsmanager", "guardduty"
                };
                String[] complianceFrameworks = {
                        "cis_3.0_aws", "soc2_aws", "pci_3.2.1_aws",
                        "hipaa_aws", "nist_csf_1.1_aws", "nist_800_53_revision_5_aws",
                        "gdpr_aws", "iso27001_2013_aws", "mitre_attack_aws",
                        "cisa_aws", "fedramp_moderate_revision_4_aws",
                        "ffiec_aws", "rbi_cyber_security_framework_aws",
                        "ens_rd2022_aws", "aws_well_architected_framework_security_pillar_aws",
                        "aws_foundational_security_best_practices_aws",
                        "gxp_21_cfr_part_11_aws"
                };
                prowlerService.triggerScanAsync(
                        account.getAwsAccountId(),
                        "us-east-1",
                        services,
                        complianceFrameworks
                );

            } catch (Exception e) {
                logger.error("Failed to trigger scheduled scan for account {}", account.getAwsAccountId(), e);
            }
        }
        logger.info("🕒 All nightly scans triggered.");
    }
}