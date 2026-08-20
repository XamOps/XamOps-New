package com.xammer.billops.service;

import com.xammer.cloud.domain.CreditRequest;
import com.xammer.cloud.domain.User;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.CreditRequestDto;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger; // ADDED
import org.slf4j.LoggerFactory; // ADDED
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut; // ADDED
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.xammer.billops.util.SecurityUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    @Autowired
    private FileStorageService fileStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> ALLOWED_PRIORITIES = Set.of("low", "medium", "high");
    private static final String DEFAULT_PRIORITY = "medium";

    /** Requests are submitted with a free-form id, e.g. "GCP: my-project (011DDB-9E3A99)". */
    private static final Pattern PROVIDER_PREFIX = Pattern.compile("^(aws|gcp|google|azure)\\s*[:\\-]\\s*",
            Pattern.CASE_INSENSITIVE);

    @Transactional
    @CacheEvict(value = "creditRequests", allEntries = true)
    public CreditRequestDto createCreditRequest(CreditRequestDto creditRequestDto) {
        return createCreditRequest(creditRequestDto, Collections.emptyList());
    }

    @Transactional
    @CacheEvict(value = "creditRequests", allEntries = true)
    public CreditRequestDto createCreditRequest(CreditRequestDto creditRequestDto, List<MultipartFile> files) {
        User user = userRepository.findById(creditRequestDto.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        CreditRequest creditRequest = new CreditRequest();
        creditRequest.setAwsAccountId(creditRequestDto.getAwsAccountId());
        creditRequest.setExpectedCredits(creditRequestDto.getExpectedCredits());
        creditRequest.setServices(creditRequestDto.getServices());
        creditRequest.setUseCase(creditRequestDto.getUseCase());
        creditRequest.setUser(user);
        creditRequest.setStatus("submitted");
        creditRequest.setPriority(normalizePriority(creditRequestDto.getPriority()));
        creditRequest.setSubmittedDate(new Date());
        creditRequest.setAttachmentsJson(uploadAttachments(files));

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
     * Uploads the supporting documents attached to a request and returns the JSON that is stored
     * on the row. Returns null when nothing was attached.
     */
    private String uploadAttachments(List<MultipartFile> files) {
        List<MultipartFile> nonEmpty = (files != null)
                ? files.stream().filter(f -> f != null && !f.isEmpty()).collect(Collectors.toList())
                : Collections.emptyList();
        if (nonEmpty.isEmpty()) {
            return null;
        }

        try {
            List<Map<String, Object>> attachments = new ArrayList<>();
            for (MultipartFile file : nonEmpty) {
                String key = fileStorageService.uploadFile(file, "credits");
                Map<String, Object> attachment = new LinkedHashMap<>();
                attachment.put("key", key);
                attachment.put("name", file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment");
                attachment.put("type",
                        file.getContentType() != null ? file.getContentType() : "application/octet-stream");
                attachment.put("size", file.getSize());
                attachments.add(attachment);
            }
            return objectMapper.writeValueAsString(attachments);
        } catch (Exception e) {
            logger.error("Failed to upload credit request attachments", e);
            throw new RuntimeException("Failed to upload attachments.", e);
        }
    }

    private List<Map<String, Object>> readAttachments(CreditRequest creditRequest) {
        String json = creditRequest.getAttachmentsJson();
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            logger.warn("Failed to parse attachments JSON for credit request {}: {}", creditRequest.getId(),
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Mints a short-lived link to one supporting document. Requesters can reach their own
     * documents; BillOps admins can reach any document their account scope allows.
     *
     * @param inline true to view the document in the browser, false to download it.
     */
    @Transactional(readOnly = true)
    public String getAttachmentUrl(Long requestId, int index, boolean inline) {
        CreditRequest creditRequest = creditRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Credit Request not found"));

        if (!canAccessAttachments(creditRequest)) {
            throw new AccessDeniedException("Not allowed to access this credit request");
        }

        List<Map<String, Object>> attachments = readAttachments(creditRequest);
        if (index < 0 || index >= attachments.size()) {
            throw new RuntimeException("Attachment not found");
        }

        Object key = attachments.get(index).get("key");
        if (key == null) {
            throw new RuntimeException("Attachment not found");
        }
        return fileStorageService.generatePresignedUrl(key.toString(), inline);
    }

    private boolean canAccessAttachments(CreditRequest creditRequest) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }

        // The requester can always reach the documents they submitted.
        try {
            Long currentUserId = SecurityUtils.getCurrentUserId();
            if (creditRequest.getUser() != null && creditRequest.getUser().getId().equals(currentUserId)) {
                return true;
            }
        } catch (RuntimeException e) {
            logger.debug("Could not resolve current user while checking attachment access: {}", e.getMessage());
        }

        boolean isBillopsAdmin = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_BILLOPS_ADMIN".equals(a) || "ROLE_BILLOPS_ADMIN_SUB".equals(a));
        if (!isBillopsAdmin) {
            return false;
        }

        // Sub-admins only reach requests for the accounts they are scoped to - the same rule
        // applyAccountFilter uses for the list itself.
        Set<String> allowedProviderIds = accountAccessHelper.getAllowedProviderAccountIds();
        if (allowedProviderIds == null) {
            return true; // Primary admin
        }
        return creditRequest.getAwsAccountId() != null
                && allowedProviderIds.contains(creditRequest.getAwsAccountId());
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

    /**
     * Credit requests carry one of three urgencies. Anything else the client sends - including
     * nothing at all - falls back to the same default the submission form starts on.
     */
    private static String normalizePriority(String rawPriority) {
        if (rawPriority == null) {
            return DEFAULT_PRIORITY;
        }
        String value = rawPriority.trim().toLowerCase();
        return ALLOWED_PRIORITIES.contains(value) ? value : DEFAULT_PRIORITY;
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
        dto.setPriority(creditRequest.getPriority());
        dto.setSubmittedDate(creditRequest.getSubmittedDate());
        if (creditRequest.getUser() != null) {
            dto.setUserId(creditRequest.getUser().getId());
        }

        List<Map<String, Object>> attachments = readAttachments(creditRequest);
        if (!attachments.isEmpty()) {
            List<CreditRequestDto.AttachmentInfo> infos = new ArrayList<>();
            for (int i = 0; i < attachments.size(); i++) {
                Map<String, Object> attachment = attachments.get(i);
                Object size = attachment.get("size");
                infos.add(new CreditRequestDto.AttachmentInfo(
                        i,
                        attachment.get("name") != null ? attachment.get("name").toString() : "attachment",
                        attachment.get("type") != null ? attachment.get("type").toString()
                                : "application/octet-stream",
                        (size instanceof Number) ? ((Number) size).longValue() : null));
            }
            dto.setAttachments(infos);
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