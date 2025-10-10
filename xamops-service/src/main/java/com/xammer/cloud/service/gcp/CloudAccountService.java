package com.xammer.cloud.service.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class CloudAccountService {

    private final CloudAccountRepository cloudAccountRepository;
    private final GcpClientProvider gcpClientProvider;

    public CloudAccountService(
            CloudAccountRepository cloudAccountRepository,
            GcpClientProvider gcpClientProvider) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.gcpClientProvider = gcpClientProvider;
    }

    /**
     * Find cloud account by GCP project ID and auto-populate billing account ID if missing
     */
    public CloudAccount findByGcpProjectId(String gcpProjectId) {
        CloudAccount account = cloudAccountRepository.findByGcpProjectId(gcpProjectId)
                .orElseThrow(() -> new RuntimeException("Cloud account not found for project: " + gcpProjectId));

        // Auto-populate billing account ID if it's missing
        if (account.getGcpBillingAccountId() == null || account.getGcpBillingAccountId().isEmpty()) {
            log.info("Billing account ID not found for project {}. Attempting to fetch from GCP.", gcpProjectId);

            Optional<String> billingAccountId = gcpClientProvider.getBillingAccountIdForProject(gcpProjectId);

            if (billingAccountId.isPresent()) {
                account.setGcpBillingAccountId(billingAccountId.get());
                cloudAccountRepository.save(account);
                log.info("Automatically populated billing account ID {} for project {}",
                        billingAccountId.get(), gcpProjectId);
            } else {
                log.warn("Could not retrieve billing account ID from GCP for project {}", gcpProjectId);
            }
        }

        return account;
    }

    /**
     * Find cloud account by GCP billing account ID
     */
    public Optional<CloudAccount> findByGcpBillingAccountId(String gcpBillingAccountId) {
        return cloudAccountRepository.findByGcpBillingAccountId(gcpBillingAccountId);
    }

    /**
     * Save or update cloud account
     */
    public CloudAccount save(CloudAccount cloudAccount) {
        log.info("Saving cloud account: {}", cloudAccount.getAccountName());
        return cloudAccountRepository.save(cloudAccount);
    }

    /**
     * Manually refresh billing account ID for a project
     */
    public void refreshBillingAccountId(String gcpProjectId) {
        CloudAccount account = cloudAccountRepository.findByGcpProjectId(gcpProjectId)
                .orElseThrow(() -> new RuntimeException("Cloud account not found for project: " + gcpProjectId));

        Optional<String> billingAccountId = gcpClientProvider.getBillingAccountIdForProject(gcpProjectId);

        if (billingAccountId.isPresent()) {
            account.setGcpBillingAccountId(billingAccountId.get());
            cloudAccountRepository.save(account);
            log.info("Refreshed billing account ID {} for project {}", billingAccountId.get(), gcpProjectId);
        } else {
            log.warn("Could not refresh billing account ID for project {}", gcpProjectId);
        }
    }
}
