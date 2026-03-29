package com.xammer.cloud.service.marketplace;

import com.xammer.cloud.domain.MarketplaceAccount;
import com.xammer.cloud.domain.MarketplacePayerAccount;
import com.xammer.cloud.dto.marketplace.ConnectMarketplaceRequest;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.MarketplaceAccountRepository;
import com.xammer.cloud.repository.MarketplacePayerAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Account;
import software.amazon.awssdk.services.organizations.paginators.ListAccountsIterable;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MarketplaceAccountDiscoveryService {

    private final StsClient stsClient;
    private final MarketplacePayerAccountRepository payerAccountRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CloudAccountRepository cloudAccountRepository;

    public MarketplaceAccountDiscoveryService(StsClient stsClient,
                                               MarketplacePayerAccountRepository payerAccountRepository,
                                               MarketplaceAccountRepository marketplaceAccountRepository,
                                               CloudAccountRepository cloudAccountRepository) {
        this.stsClient = stsClient;
        this.payerAccountRepository = payerAccountRepository;
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Connect a marketplace payer account and discover all member accounts under it.
     * Automatically categorises each account as MARKETPLACE_XAMOPS or MARKETPLACE_ONLY
     * by cross-referencing with the CloudAccount table.
     */
    @Transactional
    public MarketplacePayerAccount connectAndDiscover(ConnectMarketplaceRequest request) {
        log.info("Connecting marketplace payer account with roleArn: {}", request.getRoleArn());

        // Resolve externalId — use default if blank
        String externalId = (request.getExternalId() != null && !request.getExternalId().isBlank())
                ? request.getExternalId()
                : "xamops-marketplace-sync";

        // Step 1: Assume the payer account role
        StaticCredentialsProvider tempCreds = assumeRole(request.getRoleArn(), externalId);

        // Step 2: Extract payer account ID from the role ARN
        // ARN format: arn:aws:iam::{accountId}:role/{roleName}
        String payerAccountId = extractAccountIdFromArn(request.getRoleArn());

        // Step 3: Save or update payer account record
        MarketplacePayerAccount payerAccount = payerAccountRepository
                .findByPayerAwsAccountId(payerAccountId)
                .orElse(new MarketplacePayerAccount());

        payerAccount.setAccountName(request.getAccountName());
        payerAccount.setPayerAwsAccountId(payerAccountId);
        payerAccount.setRoleArn(request.getRoleArn());
        payerAccount.setExternalId(externalId);
        payerAccount.setStatus("CONNECTED");
        payerAccount.setLastSyncedAt(LocalDateTime.now());
        payerAccount = payerAccountRepository.save(payerAccount);

        // Step 4: List all member accounts via Organizations API
        List<Account> orgAccounts = listAllMemberAccounts(tempCreds);
        log.info("Discovered {} accounts under marketplace payer {}", orgAccounts.size(), payerAccountId);

        // Step 5: Find which accounts are already on XamOps
        List<String> orgAccountIds = orgAccounts.stream()
                .map(Account::id)
                .collect(Collectors.toList());

        Set<String> xamopsAccountIds = cloudAccountRepository
                .findByAwsAccountIdIn(orgAccountIds)
                .stream()
                .map(ca -> ca.getAwsAccountId())
                .collect(Collectors.toSet());

        // Step 6: Save all discovered accounts
        List<MarketplaceAccount> saved = new ArrayList<>();
        for (Account orgAccount : orgAccounts) {
            // Skip SUSPENDED accounts
            if (!"ACTIVE".equalsIgnoreCase(orgAccount.statusAsString())) {
                log.info("Skipping non-active account {}: {}", orgAccount.id(), orgAccount.statusAsString());
                continue;
            }

            MarketplaceAccount mp = marketplaceAccountRepository
                    .findByAwsAccountId(orgAccount.id())
                    .orElse(new MarketplaceAccount());

            mp.setPayerAccount(payerAccount);
            mp.setAwsAccountId(orgAccount.id());
            mp.setAccountName(orgAccount.name());
            mp.setContactEmail(orgAccount.email());
            mp.setStatus("ACTIVE");

            boolean onXamops = xamopsAccountIds.contains(orgAccount.id());
            mp.setCategory(onXamops ? "MARKETPLACE_XAMOPS" : "MARKETPLACE_ONLY");

            if (onXamops) {
                cloudAccountRepository.findByAwsAccountId(orgAccount.id())
                        .stream().findFirst()
                        .ifPresent(ca -> mp.setXamopsClientId(ca.getClient().getId()));
            }

            saved.add(marketplaceAccountRepository.save(mp));
        }

        log.info("Saved {} marketplace accounts. MARKETPLACE_XAMOPS={}, MARKETPLACE_ONLY={}",
                saved.size(),
                saved.stream().filter(a -> "MARKETPLACE_XAMOPS".equals(a.getCategory())).count(),
                saved.stream().filter(a -> "MARKETPLACE_ONLY".equals(a.getCategory())).count());

        return payerAccount;
    }

    /**
     * Re-sync accounts under an existing payer connection.
     * Picks up any newly added or removed accounts.
     */
    @Transactional
    public void resync(Long payerAccountId) {
        MarketplacePayerAccount payerAccount = payerAccountRepository.findById(payerAccountId)
                .orElseThrow(() -> new RuntimeException("Payer account not found: " + payerAccountId));

        log.info("Re-syncing marketplace accounts for payer {}", payerAccount.getPayerAwsAccountId());

        ConnectMarketplaceRequest req = new ConnectMarketplaceRequest();
        req.setAccountName(payerAccount.getAccountName());
        req.setRoleArn(payerAccount.getRoleArn());
        req.setExternalId(payerAccount.getExternalId());
        connectAndDiscover(req);
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private StaticCredentialsProvider assumeRole(String roleArn, String externalId) {
        log.info("Assuming role: {}", roleArn);
        AssumeRoleResponse response = stsClient.assumeRole(r -> r
                .roleArn(roleArn)
                .roleSessionName("xamops-marketplace-discovery")
                .externalId(externalId)
                .durationSeconds(3600)
        );
        Credentials c = response.credentials();
        return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(c.accessKeyId(), c.secretAccessKey(), c.sessionToken())
        );
    }

    private List<Account> listAllMemberAccounts(StaticCredentialsProvider creds) {
        try (OrganizationsClient orgClient = OrganizationsClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            ListAccountsIterable pages = orgClient.listAccountsPaginator();
            List<Account> all = new ArrayList<>();
            pages.forEach(page -> all.addAll(page.accounts()));
            log.info("Organizations API returned {} total accounts", all.size());
            return all;
        }
    }

    private String extractAccountIdFromArn(String roleArn) {
        // arn:aws:iam::{accountId}:role/{roleName}
        String[] parts = roleArn.split(":");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid role ARN format: " + roleArn);
        }
        return parts[4];
    }
}
