package com.xammer.billops.service;

import com.xammer.cloud.domain.CreditRequest;
import com.xammer.cloud.domain.User;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.CreditRequestDto;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.UserRepository;
import org.slf4j.Logger; // ADDED
import org.slf4j.LoggerFactory; // ADDED
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut; // ADDED
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional; // ADDED
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CreditRequestService {

    // ADDED
    private static final Logger logger = LoggerFactory.getLogger(CreditRequestService.class);

    @Autowired
    private CreditRequestRepository creditRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private AccountAccessHelper accountAccessHelper;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    /** Requests are submitted with a free-form id, e.g. "GCP: my-project (011DDB-9E3A99)". */
    private static final Pattern PROVIDER_PREFIX = Pattern.compile("^(aws|gcp|google|azure)\\s*[:\\-]\\s*",
            Pattern.CASE_INSENSITIVE);

    @Transactional
    @CacheEvict(value = "creditRequests", allEntries = true)
    public CreditRequestDto createCreditRequest(CreditRequestDto creditRequestDto) {
        User user = userRepository.findById(creditRequestDto.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        CreditRequest creditRequest = new CreditRequest();
        creditRequest.setAwsAccountId(creditRequestDto.getAwsAccountId());
        creditRequest.setExpectedCredits(creditRequestDto.getExpectedCredits());
        creditRequest.setServices(creditRequestDto.getServices());
        creditRequest.setUseCase(creditRequestDto.getUseCase());
        creditRequest.setUser(user);
        creditRequest.setStatus("submitted");
        creditRequest.setSubmittedDate(new Date());

        CreditRequest savedRequest = creditRequestRepository.save(creditRequest);

        // Omitted for brevity...

        return convertToDto(savedRequest, resolveAccounts(savedRequest));
    }

    // --- NEW: Admin List (Cache-Only) ---
    @Transactional(readOnly = true)
    @Cacheable(value = "creditRequests", key = "'adminList'")
    public Optional<List<CreditRequestDto>> getCachedAllCreditRequests() {
        logger.debug("Attempting to retrieve CACHED 'adminList' credit requests");
        return Optional.empty(); // Spring AOP replaces this
    }

    // --- MODIFIED: Admin List (Fetch & Cache-Write) ---
    @Transactional(readOnly = true)
    @CachePut(value = "creditRequests", key = "'adminList'")
    public List<CreditRequestDto> getAllCreditRequestsAndCache() {
        logger.debug("Fetching FRESH 'adminList' credit requests and updating cache");
        return toDtos(creditRequestRepository.findAllOrderBySubmittedDateDesc());
    }

    // --- NEW: User List (Cache-Only) ---
    @Transactional(readOnly = true)
    @Cacheable(value = "creditRequests", key = "#userId")
    public Optional<List<CreditRequestDto>> getCachedCreditRequestsByUserId(Long userId) {
        logger.debug("Attempting to retrieve CACHED credit requests for user: {}", userId);
        return Optional.empty(); // Spring AOP replaces this
    }

    // --- MODIFIED: User List (Fetch & Cache-Write) ---
    @Transactional(readOnly = true)
    @CachePut(value = "creditRequests", key = "#userId")
    public List<CreditRequestDto> getCreditRequestsByUserIdAndCache(Long userId) {
        logger.debug("Fetching FRESH credit requests for user: {} and updating cache", userId);
        return toDtos(creditRequestRepository.findByUserId(userId));
    }

    @Transactional
    @CacheEvict(value = "creditRequests", allEntries = true)
    public CreditRequestDto updateRequestStatus(Long id, String status) {
        CreditRequest creditRequest = creditRequestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Credit Request not found"));
        creditRequest.setStatus(status);
        CreditRequest updatedRequest = creditRequestRepository.save(creditRequest);
        return convertToDto(updatedRequest, resolveAccounts(updatedRequest));
    }

    /**
     * Filters credit requests to only those belonging to the current sub-user's allowed accounts.
     * Primary admins see all requests unfiltered (returns null → no restriction).
     * The awsAccountId field stores the provider-specific account identifier regardless of cloud.
     */
    public List<CreditRequestDto> applyAccountFilter(List<CreditRequestDto> requests) {
        Set<String> allowedProviderIds = accountAccessHelper.getAllowedProviderAccountIds();
        if (allowedProviderIds == null) return requests; // Primary admin
        if (allowedProviderIds.isEmpty()) return Collections.emptyList();
        return requests.stream()
                .filter(dto -> dto.getAwsAccountId() != null && allowedProviderIds.contains(dto.getAwsAccountId()))
                .collect(Collectors.toList());
    }

    private List<CreditRequestDto> toDtos(List<CreditRequest> requests) {
        Map<String, CloudAccount> accountsByKey = resolveAccounts(requests);
        return requests.stream()
                .map(request -> convertToDto(request, accountsByKey))
                .collect(Collectors.toList());
    }

    /**
     * Looks up the cloud accounts behind a batch of credit requests in a single query, keyed by
     * every provider identifier they expose so a request can be matched whichever cloud it targets.
     */
    private Map<String, CloudAccount> resolveAccounts(Collection<CreditRequest> requests) {
        Set<String> keys = requests.stream()
                .map(request -> normalizeAccountKey(request.getAwsAccountId()))
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toSet());
        if (keys.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, CloudAccount> accountsByKey = new HashMap<>();
        try {
            for (CloudAccount account : cloudAccountRepository.findByAnyCloudIdIn(keys)) {
                indexAccount(accountsByKey, account.getAwsAccountId(), account);
                indexAccount(accountsByKey, account.getGcpProjectId(), account);
                indexAccount(accountsByKey, account.getAzureSubscriptionId(), account);
            }
        } catch (Exception e) {
            // Names are cosmetic - never fail the list because of them.
            logger.warn("Could not resolve cloud account names for credit requests: {}", e.getMessage());
        }
        return accountsByKey;
    }

    private Map<String, CloudAccount> resolveAccounts(CreditRequest request) {
        return resolveAccounts(Collections.singletonList(request));
    }

    private static void indexAccount(Map<String, CloudAccount> accountsByKey, String identifier,
            CloudAccount account) {
        if (identifier != null && !identifier.trim().isEmpty()) {
            accountsByKey.putIfAbsent(identifier.trim().toLowerCase(), account);
        }
    }

    /**
     * Credit requests store whatever the user typed, e.g. "GCP: my-project (011DDB-9E3A99-DE0B85)".
     * Strip the provider prefix and any trailing parenthetical so the value can be matched against
     * cloud_account rows.
     */
    private static String normalizeAccountKey(String rawAccountId) {
        if (rawAccountId == null) {
            return "";
        }
        String value = PROVIDER_PREFIX.matcher(rawAccountId.trim()).replaceFirst("");
        int parenthesis = value.indexOf('(');
        if (parenthesis > 0) {
            value = value.substring(0, parenthesis);
        }
        return value.trim();
    }

    private static String providerHint(String rawAccountId) {
        if (rawAccountId == null) {
            return null;
        }
        Matcher matcher = PROVIDER_PREFIX.matcher(rawAccountId.trim());
        if (!matcher.find()) {
            return null;
        }
        String hint = matcher.group(1).toUpperCase();
        return "GOOGLE".equals(hint) ? "GCP" : hint;
    }

    private CreditRequestDto convertToDto(CreditRequest creditRequest, Map<String, CloudAccount> accountsByKey) {
        CreditRequestDto dto = new CreditRequestDto();
        dto.setId(creditRequest.getId());
        dto.setAwsAccountId(creditRequest.getAwsAccountId());
        dto.setExpectedCredits(creditRequest.getExpectedCredits());
        dto.setServices(creditRequest.getServices());
        dto.setUseCase(creditRequest.getUseCase());
        dto.setStatus(creditRequest.getStatus());
        dto.setSubmittedDate(creditRequest.getSubmittedDate());
        if (creditRequest.getUser() != null) {
            dto.setUserId(creditRequest.getUser().getId());
        }

        CloudAccount account = accountsByKey.get(normalizeAccountKey(creditRequest.getAwsAccountId()).toLowerCase());
        if (account != null) {
            dto.setAccountName(account.getAccountName());
            dto.setProvider(account.getProvider());
        } else {
            dto.setProvider(providerHint(creditRequest.getAwsAccountId()));
        }
        return dto;
    }
}