package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read side of XamSecure. Every public method takes the caller's clientId and
 * filters strictly to cloud accounts owned by that client — org-wide Upwind
 * data must never leave this layer unfiltered.
 */
@Service
@Slf4j
public class XamSecureService {

    private static final int SUMMARY_CACHE_MINUTES = 5;

    private final CloudAccountRepository cloudAccountRepository;
    private final UpwindThreatDetectionRepository detectionRepo;
    private final UpwindVulnerabilityFindingRepository vulnRepo;
    private final UpwindPostureFindingRepository postureRepo;
    private final UpwindSyncStateRepository syncStateRepo;
    private final UpwindAccountConnectionRepository connectionRepo;
    private final RedisCacheService redisCacheService;

    public XamSecureService(CloudAccountRepository cloudAccountRepository,
                            UpwindThreatDetectionRepository detectionRepo,
                            UpwindVulnerabilityFindingRepository vulnRepo,
                            UpwindPostureFindingRepository postureRepo,
                            UpwindSyncStateRepository syncStateRepo,
                            UpwindAccountConnectionRepository connectionRepo,
                            RedisCacheService redisCacheService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.detectionRepo = detectionRepo;
        this.vulnRepo = vulnRepo;
        this.postureRepo = postureRepo;
        this.syncStateRepo = syncStateRepo;
        this.connectionRepo = connectionRepo;
        this.redisCacheService = redisCacheService;
    }

    /**
     * Resolves the provider account ids (AWS account / GCP project / Azure
     * subscription) owned by the client. If requestedAccountId is given it
     * must be one of them, otherwise AccessDeniedException.
     */
    public List<String> resolveAccountIds(Long clientId, String requestedAccountId) {
        List<String> owned = cloudAccountRepository.findByClientId(clientId).stream()
                .map(CloudAccount::getProviderAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (requestedAccountId == null || requestedAccountId.isEmpty()) {
            return owned;
        }
        if (!owned.contains(requestedAccountId)) {
            throw new AccessDeniedException("Account not accessible: " + requestedAccountId);
        }
        return List.of(requestedAccountId);
    }

    public Map<String, Object> getSummary(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        String cacheKey = "xamsecure:summary:" + clientId + ":" + (accountId == null ? "all" : accountId);
        Optional<Map<String, Object>> cached =
                redisCacheService.get(cacheKey, new TypeReference<Map<String, Object>>() {});
        if (cached.isPresent()) return cached.get();

        Map<String, Object> summary = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            summary.put("threatsBySeverity", Map.of());
            summary.put("vulnerabilitiesBySeverity", Map.of());
            summary.put("postureBySeverity", Map.of());
            summary.put("syncState", List.of());
            return summary;
        }
        summary.put("threatsBySeverity", toSeverityMap(detectionRepo.countOpenBySeverity(ids)));
        summary.put("vulnerabilitiesBySeverity", toSeverityMap(vulnRepo.countOpenBySeverity(ids)));
        summary.put("postureBySeverity", toSeverityMap(postureRepo.countFailBySeverity(ids)));
        summary.put("syncState", syncStateRepo.findAll().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", s.getDomain());
            m.put("lastSuccessAt", s.getLastSuccessAt());
            m.put("lastError", s.getLastError());
            return m;
        }).collect(Collectors.toList()));
        redisCacheService.put(cacheKey, summary, SUMMARY_CACHE_MINUTES);
        return summary;
    }

    public Page<UpwindThreatDetection> getThreats(Long clientId, String accountId,
                                                  String severity, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (severity != null && !severity.isEmpty()) {
            return detectionRepo.findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(ids, severity, pageable);
        }
        return detectionRepo.findByCloudAccountIdInOrderByLastSeenTimeDesc(ids, pageable);
    }

    public Page<UpwindVulnerabilityFinding> getVulnerabilities(Long clientId, String accountId,
                                                               String severity, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (severity != null && !severity.isEmpty()) {
            return vulnRepo.findByCloudAccountIdInAndSeverityOrderByLastScanTimeDesc(ids, severity, pageable);
        }
        return vulnRepo.findByCloudAccountIdInOrderByLastScanTimeDesc(ids, pageable);
    }

    public Page<UpwindPostureFinding> getPosture(Long clientId, String accountId,
                                                 String severity, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (severity != null && !severity.isEmpty()) {
            return postureRepo.findByCloudAccountIdInAndSeverityOrderByLastSeenTimeDesc(ids, severity, pageable);
        }
        return postureRepo.findByCloudAccountIdInOrderByLastSeenTimeDesc(ids, pageable);
    }

    public List<Map<String, Object>> getCompliance(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return List.of();
        Map<String, Map<String, Long>> byFramework = new LinkedHashMap<>();
        for (Object[] row : postureRepo.countByFrameworkAndStatus(ids)) {
            String framework = (String) row[0];
            String status = (String) row[1];
            Long count = (Long) row[2];
            byFramework.computeIfAbsent(framework, k -> new HashMap<>()).put(status, count);
        }
        return byFramework.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("framework", e.getKey());
            m.put("failCount", e.getValue().getOrDefault("FAIL", 0L));
            m.put("passCount", e.getValue().getOrDefault("PASS", 0L));
            return m;
        }).collect(Collectors.toList());
    }

    public List<Map<String, Object>> getOnboardingStatus(Long clientId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);
        List<String> ids = accounts.stream()
                .map(CloudAccount::getProviderAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<String, UpwindAccountConnection> connections = connectionRepo
                .findByCloudAccountIdIn(ids).stream()
                .collect(Collectors.toMap(UpwindAccountConnection::getCloudAccountId, c -> c));
        return accounts.stream()
                .filter(a -> a.getProviderAccountId() != null)
                .map(a -> {
                    UpwindAccountConnection c = connections.get(a.getProviderAccountId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accountName", a.getAccountName());
                    m.put("cloudAccountId", a.getProviderAccountId());
                    m.put("provider", a.getProvider());
                    m.put("status", c == null ? "NOT_CONNECTED" : c.getStatus());
                    m.put("sensorInstallCommand", c == null ? null : c.getSensorInstallCommand());
                    return m;
                }).collect(Collectors.toList());
    }

    private static Map<String, Long> toSeverityMap(List<Object[]> rows) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) m.put((String) row[0], (Long) row[1]);
        }
        return m;
    }
}
