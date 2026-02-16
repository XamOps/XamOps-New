package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudiDRCredentials;
import com.xammer.cloud.domain.LinkedAccount;
import com.xammer.cloud.domain.UserCloudAccountAccess;
import com.xammer.cloud.dto.flexcompute.*;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CloudiDRCredentialsRepository;
import com.xammer.cloud.repository.LinkedAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FlexComputeService {

    private static final Logger logger = LoggerFactory.getLogger(FlexComputeService.class);

    private final CloudiDRApiClient apiClient;
    private final CloudiDRCredentialsRepository credentialsRepository;
    private final LinkedAccountRepository linkedAccountRepository;
    private final UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final EncryptionService encryptionService;

    @Value("${cloudidr.auth.email:}")
    private String cloudidrEmail;

    @Value("${cloudidr.auth.password:}")
    private String cloudidrPassword;

    public FlexComputeService(CloudiDRApiClient apiClient,
                              CloudiDRCredentialsRepository credentialsRepository,
                              LinkedAccountRepository linkedAccountRepository,
                              UserCloudAccountAccessRepository userCloudAccountAccessRepository,
                              CloudAccountRepository cloudAccountRepository,
                              EncryptionService encryptionService) {
        this.apiClient = apiClient;
        this.credentialsRepository = credentialsRepository;
        this.linkedAccountRepository = linkedAccountRepository;
        this.userCloudAccountAccessRepository = userCloudAccountAccessRepository;
        this.cloudAccountRepository = cloudAccountRepository;
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
     * Check if user has valid CloudiDR credentials.
     * Auto-authenticates using properties credentials if not yet authenticated.
     */
    public boolean isAuthenticated(Long userId) {
        boolean valid = credentialsRepository.findByUserId(userId)
                .map(creds -> creds.getTokenExpiresAt() == null || creds.getTokenExpiresAt().isAfter(Instant.now()))
                .orElse(false);

        if (!valid && cloudidrEmail != null && !cloudidrEmail.isBlank() && cloudidrPassword != null && !cloudidrPassword.isBlank()) {
            try {
                logger.info("Auto-authenticating user {} with CloudiDR on status check", userId);
                authenticateUser(userId, cloudidrEmail, cloudidrPassword);
                return true;
            } catch (Exception e) {
                logger.error("CloudiDR auto-authentication failed: {}", e.getMessage());
                return false;
            }
        }

        return valid;
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
     * Retrieve and decrypt a valid token for the user.
     * Auto-authenticates using properties credentials if token is missing or expired.
     */
    private String getValidToken(Long userId) {
        var credsOpt = credentialsRepository.findByUserId(userId);

        // Check if we have a valid (non-expired) token
        if (credsOpt.isPresent()) {
            CloudiDRCredentials creds = credsOpt.get();
            boolean expired = creds.getTokenExpiresAt() != null && creds.getTokenExpiresAt().isBefore(Instant.now());
            if (!expired) {
                String token = encryptionService.decrypt(creds.getCloudidrTokenEncrypted());
                if (token != null) {
                    return token;
                }
            }
        }

        // Token missing or expired — auto-authenticate using properties credentials
        if (cloudidrEmail != null && !cloudidrEmail.isBlank() && cloudidrPassword != null && !cloudidrPassword.isBlank()) {
            logger.info("Auto-authenticating user {} with CloudiDR using configured credentials", userId);
            authenticateUser(userId, cloudidrEmail, cloudidrPassword);
            // Retrieve the freshly stored token
            CloudiDRCredentials freshCreds = credentialsRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("CloudiDR auto-auth failed — no credentials stored"));
            return encryptionService.decrypt(freshCreds.getCloudidrTokenEncrypted());
        }

        throw new RuntimeException("CloudiDR not configured. Please set cloudidr.auth.email and cloudidr.auth.password in properties.");
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
     * Creates a temporary 15-minute reservation with cidrId and pricing
     */
    public ReserveCapacityResponse reserveCapacity(Long userId, ReserveCapacityRequest request) {
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
     * Cancel/Delete capacity
     */
    public void cancelCapacity(Long userId, String cidrId) {
        logger.info("Cancelling capacity {} for user {}", cidrId, userId);
        String token = getValidToken(userId);
        apiClient.cancelCapacity(token, cidrId);
    }

    /**
     * Extend capacity end date
     */
    public void extendCapacity(Long userId, ExtendCapacityRequest request) {
        logger.info("Extending capacity {} for user {} to new end date {}", request.getCidrId(), userId, request.getEndDate());
        String token = getValidToken(userId);
        apiClient.extendCapacity(token, request);
    }

    /**
     * Update capacity auto-renewal settings
     */
    public void renewCapacity(Long userId, RenewCapacityRequest request) {
        logger.info("Updating auto-renewal for capacity {} for user {}: enabled={}, frequency={}",
                request.getCidrId(), userId, request.getAutoRenewalEnabled(), request.getFrequency());
        String token = getValidToken(userId);
        apiClient.renewCapacity(token, request);
    }

    /**
     * List capacities for the user with pagination and filters
     */
    public CapacityListResponse describeCapacity(Long userId, Integer page, Integer pageSize, String sort, java.util.List<String> statuses) {
        logger.info("Describing capacity for user {} (page={}, pageSize={}, sort={}, statuses={})", userId, page, pageSize, sort, statuses);
        String token = getValidToken(userId);
        return apiClient.describeCapacity(token, page, pageSize, sort, statuses);
    }

    /**
     * Get linked cloud accounts for the user (from CloudiDR API with accountRegions)
     * SECURITY: Only returns accounts that are ACTIVE in CloudiDR AND user has access to in XamOps
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

        // SECURITY FILTER: Only return accounts that meet both criteria:
        // 1. Status is ACTIVE in CloudiDR
        // 2. User has access to this account in XamOps (exists in their sidebar accounts)
        List<LinkedAccountResponse> filteredAccounts = filterUserAccessibleAccounts(userId, apiAccounts);

        logger.info("Filtered {} CloudiDR accounts to {} accessible accounts for user {}",
                apiAccounts.size(), filteredAccounts.size(), userId);

        return filteredAccounts;
    }

    /**
     * Filter linked accounts to only show:
     * 1. Accounts with ACTIVE status from CloudiDR
     * 2. Accounts that the user has access to in XamOps (if XamOps access is configured)
     *
     * This prevents users from seeing other customers' account IDs
     *
     * FALLBACK: If user has no XamOps access configured, only filter by ACTIVE status
     */
    private List<LinkedAccountResponse> filterUserAccessibleAccounts(Long userId, List<LinkedAccountResponse> apiAccounts) {
        // Get all CloudAccount IDs that this user has access to
        List<UserCloudAccountAccess> userAccess = userCloudAccountAccessRepository.findByUserId(userId);

        // Extract the AWS account IDs from the user's accessible accounts
        Set<String> accessibleAwsAccountIds = new HashSet<>();
        for (UserCloudAccountAccess access : userAccess) {
            CloudAccount cloudAccount = access.getCloudAccount();
            if (cloudAccount != null && cloudAccount.getAwsAccountId() != null) {
                accessibleAwsAccountIds.add(cloudAccount.getAwsAccountId());
            }
        }

        logger.info("User {} has access to {} AWS accounts in XamOps", userId, accessibleAwsAccountIds.size());

        // FALLBACK: If no XamOps access is configured, only filter by ACTIVE status
        boolean applyAccessFilter = !accessibleAwsAccountIds.isEmpty();

        if (!applyAccessFilter) {
            logger.warn("User {} has no XamOps account access configured - only filtering by ACTIVE status", userId);
        }

        // Filter the CloudiDR accounts
        return apiAccounts.stream()
                .filter(account -> {
                    // Filter 1: Must be ACTIVE or LINKED status (always applied)
                    // ACTIVE = actively linked, LINKED = successfully linked
                    // Filter out: FAILED, PENDING, DISCONNECTED, etc.
                    boolean isValidStatus = "ACTIVE".equalsIgnoreCase(account.getStatus()) ||
                                           "LINKED".equalsIgnoreCase(account.getStatus());

                    // Filter 2: Must exist in user's XamOps accessible accounts (only if access is configured)
                    boolean hasAccess = !applyAccessFilter || accessibleAwsAccountIds.contains(account.getAccountId());

                    if (!isValidStatus) {
                        logger.info("Filtering out CloudiDR account {} - status is {} (not ACTIVE/LINKED)",
                                account.getAccountId(), account.getStatus());
                    }
                    if (applyAccessFilter && !hasAccess) {
                        logger.info("Filtering out CloudiDR account {} - user {} has no XamOps access",
                                account.getAccountId(), userId);
                    }

                    return isValidStatus && hasAccess;
                })
                .collect(Collectors.toList());
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
