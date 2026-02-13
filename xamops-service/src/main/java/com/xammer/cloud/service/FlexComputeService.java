package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudiDRCredentials;
import com.xammer.cloud.domain.LinkedAccount;
import com.xammer.cloud.dto.flexcompute.*;
import com.xammer.cloud.repository.CloudiDRCredentialsRepository;
import com.xammer.cloud.repository.LinkedAccountRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FlexComputeService {

    private static final Logger logger = LoggerFactory.getLogger(FlexComputeService.class);

    private final CloudiDRApiClient apiClient;
    private final CloudiDRCredentialsRepository credentialsRepository;
    private final LinkedAccountRepository linkedAccountRepository;
    private final EncryptionService encryptionService;

    public FlexComputeService(CloudiDRApiClient apiClient,
                              CloudiDRCredentialsRepository credentialsRepository,
                              LinkedAccountRepository linkedAccountRepository,
                              EncryptionService encryptionService) {
        this.apiClient = apiClient;
        this.credentialsRepository = credentialsRepository;
        this.linkedAccountRepository = linkedAccountRepository;
        this.encryptionService = encryptionService;
    }

    /**
     * Authenticate user with CloudiDR and store encrypted token
     */
    @Transactional
    public CloudiDRSignInResponse authenticateUser(Long userId, String email, String password) {
        logger.info("Authenticating user {} with CloudiDR (email: {})", userId, email);

        CloudiDRSignInResponse response = apiClient.signIn(email, password);

        if (response == null || response.getAccessToken() == null) {
            throw new RuntimeException("CloudiDR authentication returned no token");
        }

        // Encrypt and store token
        String encryptedToken = encryptionService.encrypt(response.getAccessToken());

        CloudiDRCredentials creds = credentialsRepository.findByUserId(userId)
                .orElse(new CloudiDRCredentials());

        creds.setUserId(userId);
        creds.setCloudidrEmail(email);
        creds.setCloudidrTokenEncrypted(encryptedToken);

        // Calculate expiry from expires_in (seconds)
        if (response.getExpiresIn() != null && response.getExpiresIn() > 0) {
            creds.setTokenExpiresAt(Instant.now().plusSeconds(response.getExpiresIn()));
            logger.info("Token expires in {} seconds", response.getExpiresIn());
        } else {
            // Default to 1 hour if not provided
            creds.setTokenExpiresAt(Instant.now().plusSeconds(3600));
            logger.warn("No expires_in provided, setting 1h default");
        }

        credentialsRepository.save(creds);
        logger.info("CloudiDR credentials stored for user {}", userId);

        // Fetch and store linked accounts after successful authentication
        try {
            List<LinkedAccountResponse> linkedAccounts = apiClient.getLinkedAccounts(response.getAccessToken());
            logger.info("Fetched {} linked accounts for user {}", linkedAccounts.size(), userId);

            // Store linked accounts in database
            syncLinkedAccounts(userId, linkedAccounts);
        } catch (Exception e) {
            logger.warn("Failed to fetch linked accounts after sign-in: {}", e.getMessage());
            // Don't fail the authentication if linked accounts fetch fails
        }

        return response;
    }

    /**
     * Check if user has valid CloudiDR credentials
     */
    public boolean isAuthenticated(Long userId) {
        return credentialsRepository.findByUserId(userId)
                .map(creds -> creds.getTokenExpiresAt() == null || creds.getTokenExpiresAt().isAfter(Instant.now()))
                .orElse(false);
    }

    /**
     * Get the CloudiDR email for a user
     */
    public String getAuthEmail(Long userId) {
        return credentialsRepository.findByUserId(userId)
                .map(CloudiDRCredentials::getCloudidrEmail)
                .orElse(null);
    }

    /**
     * Retrieve and decrypt a valid token for the user
     */
    private String getValidToken(Long userId) {
        CloudiDRCredentials creds = credentialsRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("CloudiDR not configured. Please authenticate first."));

        if (creds.getTokenExpiresAt() != null && creds.getTokenExpiresAt().isBefore(Instant.now())) {
            throw new RuntimeException("CloudiDR token expired. Please re-authenticate.");
        }

        String token = encryptionService.decrypt(creds.getCloudidrTokenEncrypted());
        if (token == null) {
            throw new RuntimeException("Failed to decrypt CloudiDR token. Please re-authenticate.");
        }

        return token;
    }

    /**
     * Search capacity via CloudiDR API
     */
    public SearchCapacityResponse searchCapacity(Long userId, SearchCapacityRequest request) {
        logger.info("Searching capacity for user {} - instance type: {}", userId, request.getInstanceType());
        String token = getValidToken(userId);
        return apiClient.searchCapacity(token, request);
    }

    /**
     * Reserve capacity via CloudiDR API
     */
    public SearchCapacityResponse reserveCapacity(Long userId, ReserveCapacityRequest request) {
        logger.info("Reserving capacity for user {} - instance type: {}", userId, request.getInstanceType());
        String token = getValidToken(userId);
        return apiClient.reserveCapacity(token, request);
    }

    /**
     * Configure capacity via CloudiDR API
     */
    public SearchCapacityResponse configureCapacity(Long userId, ConfigureCapacityRequest request) {
        logger.info("Configuring capacity {} for user {}", request.getCidrId(), userId);
        String token = getValidToken(userId);
        return apiClient.configureCapacity(token, request);
    }

    /**
     * Purchase capacity via CloudiDR API
     */
    public SearchCapacityResponse purchaseCapacity(Long userId, PurchaseCapacityRequest request) {
        logger.info("Purchasing capacity {} for user {}", request.getCidrId(), userId);
        String token = getValidToken(userId);
        return apiClient.purchaseCapacity(token, request);
    }

    /**
     * List all capacities for the user
     */
    public CapacityListResponse describeCapacity(Long userId) {
        logger.info("Describing capacity for user {}", userId);
        String token = getValidToken(userId);
        // CloudiDR uses 1-based pagination (page starts at 1, not 0)
        return apiClient.describeCapacity(token, 1, 10, "desc", null);
    }

    /**
     * Get linked cloud accounts for the user (from CloudiDR API with accountRegions)
     */
    public List<LinkedAccountResponse> getLinkedAccounts(Long userId) {
        logger.info("Fetching linked accounts for user {} from CloudiDR API", userId);
        String token = getValidToken(userId);
        List<LinkedAccountResponse> apiAccounts = apiClient.getLinkedAccounts(token);

        // Sync to database for caching (but return API response with accountRegions)
        try {
            syncLinkedAccounts(userId, apiAccounts);
        } catch (Exception e) {
            logger.warn("Failed to sync linked accounts to database: {}", e.getMessage());
        }

        return apiAccounts;
    }

    /**
     * Sync linked accounts from CloudiDR API to database
     * Uses REQUIRES_NEW to run in separate transaction so failures don't affect parent transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void syncLinkedAccounts(Long userId, List<LinkedAccountResponse> apiAccounts) {
        logger.info("Syncing {} linked accounts for user {}", apiAccounts.size(), userId);

        // Get existing accounts
        List<LinkedAccount> existingAccounts = linkedAccountRepository.findByUserId(userId);

        // Convert API response to entities
        for (LinkedAccountResponse apiAccount : apiAccounts) {
            LinkedAccount account = existingAccounts.stream()
                    .filter(a -> a.getAccountId().equals(apiAccount.getAccountId()))
                    .findFirst()
                    .orElse(new LinkedAccount());

            account.setUserId(userId);
            account.setAccountId(apiAccount.getAccountId());
            account.setAccountName(apiAccount.getAccountName());
            account.setProvider(apiAccount.getProvider());
            account.setStatus(apiAccount.getStatus());

            // Parse ISO 8601 timestamp
            if (apiAccount.getLinkedAt() != null) {
                try {
                    account.setLinkedAt(Instant.parse(apiAccount.getLinkedAt()));
                } catch (Exception e) {
                    logger.warn("Failed to parse linkedAt timestamp: {}", apiAccount.getLinkedAt());
                }
            }

            account.setSyncedAt(Instant.now());
            linkedAccountRepository.save(account);
        }

        logger.info("Successfully synced {} linked accounts for user {}", apiAccounts.size(), userId);
    }

    /**
     * Refresh linked accounts from CloudiDR API
     */
    @Transactional
    public List<LinkedAccountResponse> refreshLinkedAccounts(Long userId) {
        logger.info("Refreshing linked accounts from CloudiDR API for user {}", userId);
        String token = getValidToken(userId);
        List<LinkedAccountResponse> apiAccounts = apiClient.getLinkedAccounts(token);

        syncLinkedAccounts(userId, apiAccounts);

        return getLinkedAccounts(userId);
    }

    /**
     * Disconnect / remove CloudiDR credentials and linked accounts
     */
    @Transactional
    public void disconnect(Long userId) {
        logger.info("Removing CloudiDR credentials and linked accounts for user {}", userId);
        credentialsRepository.deleteByUserId(userId);
        linkedAccountRepository.deleteByUserId(userId);
    }
}
