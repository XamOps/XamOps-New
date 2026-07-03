package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

/**
 * Pulls Upwind findings into XamOps tables.
 *
 * Detections: polled frequently (no server-side time filter confirmed yet, so
 * we fetch recent pages and upsert by upwind_id — volumes are small).
 * Vulnerabilities/posture: full sync; anything not seen in the latest full
 * sync is marked RESOLVED.
 */
@Service
@Slf4j
public class UpwindSyncService {

    static final int DETECTION_POLL_MAX_PAGES = 5;    // ~500 newest detections per poll
    static final int FULL_SYNC_MAX_PAGES = 200;       // ~20k items cap per domain

    private final UpwindApiClient apiClient;
    private final UpwindThreatDetectionRepository detectionRepo;
    private final UpwindVulnerabilityFindingRepository vulnRepo;
    private final UpwindPostureFindingRepository postureRepo;
    private final UpwindSyncStateRepository syncStateRepo;

    public UpwindSyncService(UpwindApiClient apiClient,
                             UpwindThreatDetectionRepository detectionRepo,
                             UpwindVulnerabilityFindingRepository vulnRepo,
                             UpwindPostureFindingRepository postureRepo,
                             UpwindSyncStateRepository syncStateRepo) {
        this.apiClient = apiClient;
        this.detectionRepo = detectionRepo;
        this.vulnRepo = vulnRepo;
        this.postureRepo = postureRepo;
        this.syncStateRepo = syncStateRepo;
    }

    public void syncDetections() {
        if (!apiClient.isConfigured()) return;
        try {
            List<JsonNode> items = apiClient.fetchThreatDetections(DETECTION_POLL_MAX_PAGES);
            Instant now = Instant.now();
            for (JsonNode item : items) {
                upsertDetection(item, now);
            }
            recordSuccess("DETECTIONS");
        } catch (Exception e) {
            log.error("[Upwind] Detection sync failed: {}", e.getMessage());
            recordError("DETECTIONS", e);
        }
    }

    public void syncAll() {
        if (!apiClient.isConfigured()) return;
        syncDetections();
        Instant runStart = Instant.now();
        try {
            List<JsonNode> items = apiClient.fetchVulnerabilityFindings(FULL_SYNC_MAX_PAGES);
            for (JsonNode item : items) {
                upsertVulnerability(item, Instant.now());
            }
            if (!items.isEmpty()) {
                vulnRepo.markResolvedNotSyncedSince(runStart);
            }
            recordSuccess("VULNERABILITIES");
        } catch (Exception e) {
            log.error("[Upwind] Vulnerability sync failed: {}", e.getMessage());
            recordError("VULNERABILITIES", e);
        }
        runStart = Instant.now();
        try {
            List<JsonNode> items = apiClient.fetchConfigurationFindings(FULL_SYNC_MAX_PAGES);
            for (JsonNode item : items) {
                upsertPosture(item, Instant.now());
            }
            if (!items.isEmpty()) {
                postureRepo.markResolvedNotSyncedSince(runStart);
            }
            recordSuccess("POSTURE");
        } catch (Exception e) {
            log.error("[Upwind] Posture sync failed: {}", e.getMessage());
            recordError("POSTURE", e);
        }
    }

    private void upsertDetection(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindThreatDetection d = detectionRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindThreatDetection::new);
        d.setUpwindId(upwindId);
        d.setCategory(text(item, "category"));
        d.setSeverity(text(item, "severity"));
        d.setStatus(text(item, "status"));
        d.setTitle(text(item, "title"));
        JsonNode res = item.path("resource");
        d.setCloudProvider(text(res, "cloud_provider"));
        d.setCloudAccountId(text(res, "cloud_account_id"));
        d.setResourceId(text(res, "id"));
        d.setResourceName(text(res, "name"));
        d.setResourceType(text(res, "type"));
        StringJoiner tactics = new StringJoiner(",");
        for (JsonNode attack : item.path("mitre_attacks")) {
            if (attack.hasNonNull("tactic_name")) tactics.add(attack.get("tactic_name").asText());
        }
        d.setMitreTactics(tactics.length() > 0 ? tactics.toString() : null);
        d.setOccurrenceCount(item.hasNonNull("occurrence_count") ? item.get("occurrence_count").asInt() : null);
        d.setFirstSeenTime(instant(item, "first_seen_time"));
        d.setLastSeenTime(instant(item, "last_seen_time"));
        d.setRawPayload(item.toString());
        d.setSyncedAt(now);
        detectionRepo.save(d);
    }

    private void upsertVulnerability(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindVulnerabilityFinding v = vulnRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindVulnerabilityFinding::new);
        v.setUpwindId(upwindId);
        JsonNode vuln = item.path("vulnerability");
        v.setCveId(text(vuln, "nvd_cve_id"));
        v.setVulnerabilityName(text(vuln, "name"));
        v.setSeverity(text(vuln, "nvd_cvss_v3_severity"));
        v.setCvssScore(text(vuln, "nvd_cvss_v3_score"));
        v.setEpssScore(text(vuln, "epss_score"));
        v.setExploitable(vuln.hasNonNull("exploitable") ? vuln.get("exploitable").asBoolean() : null);
        v.setStatus("OPEN");
        JsonNode res = item.path("resource");
        v.setCloudProvider(text(res, "cloud_provider"));
        v.setCloudAccountId(text(res, "cloud_account_id"));
        v.setRegion(text(res, "region"));
        v.setResourceId(text(res, "id"));
        v.setResourceName(text(res, "name"));
        v.setResourceType(text(res, "type"));
        v.setFirstSeenTime(instant(item, "first_seen_time"));
        v.setLastScanTime(instant(item, "last_scan_time"));
        v.setRawPayload(item.toString());
        v.setSyncedAt(now);
        vulnRepo.save(v);
    }

    private void upsertPosture(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindPostureFinding p = postureRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindPostureFinding::new);
        p.setUpwindId(upwindId);
        p.setSeverity(text(item, "severity"));
        p.setStatus(text(item, "status"));
        p.setTitle(text(item, "title"));
        p.setFrameworkTitle(text(item.path("framework"), "title"));
        p.setCheckTitle(text(item.path("check"), "title"));
        JsonNode res = item.path("resource");
        p.setCloudProvider(text(res, "cloud_provider"));
        p.setCloudAccountId(text(res, "cloud_account_id"));
        p.setRegion(text(res, "region"));
        p.setResourceId(text(res, "id"));
        p.setResourceName(text(res, "name"));
        p.setResourceType(text(res, "type"));
        p.setFirstSeenTime(instant(item, "first_seen_time"));
        p.setLastSeenTime(instant(item, "last_seen_time"));
        p.setRawPayload(item.toString());
        p.setSyncedAt(now);
        postureRepo.save(p);
    }

    private void recordSuccess(String domain) {
        UpwindSyncState s = syncStateRepo.findByDomain(domain).orElseGet(() -> {
            UpwindSyncState n = new UpwindSyncState();
            n.setDomain(domain);
            return n;
        });
        s.setLastSuccessAt(Instant.now());
        s.setLastError(null);
        s.setLastErrorAt(null);
        syncStateRepo.save(s);
    }

    private void recordError(String domain, Exception e) {
        UpwindSyncState s = syncStateRepo.findByDomain(domain).orElseGet(() -> {
            UpwindSyncState n = new UpwindSyncState();
            n.setDomain(domain);
            return n;
        });
        s.setLastError(e.getMessage());
        s.setLastErrorAt(Instant.now());
        syncStateRepo.save(s);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String v = text(node, field);
        try {
            return v == null ? null : Instant.parse(v);
        } catch (Exception e) {
            return null;
        }
    }
}
