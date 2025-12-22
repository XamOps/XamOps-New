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

                // Trigger Prowler asynchronously.
                // The ProwlerService is responsible for updating the Cache/DB when it finishes.
                prowlerService.triggerScanAsync(
                        account.getAwsAccountId(),
                        "us-east-1", // Default region entry point
                        "s3", "ec2", "iam", "rds" // Scan critical services
                );

            } catch (Exception e) {
                logger.error("Failed to trigger scheduled scan for account {}", account.getAwsAccountId(), e);
            }
        }
        logger.info("🕒 All nightly scans triggered.");
    }
}