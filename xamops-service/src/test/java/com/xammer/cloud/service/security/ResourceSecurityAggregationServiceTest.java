package com.xammer.cloud.service.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.PerformanceInsightDto.InsightSeverity;
import com.xammer.cloud.dto.compliance.ComplianceReport;
import com.xammer.cloud.dto.security.ResourceSecurityFindings;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.ResourceSecurityGroup;
import com.xammer.cloud.domain.UpwindPostureFinding;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CloudSecurityFindingRepository;
import com.xammer.cloud.repository.UpwindPostureFindingRepository;
import com.xammer.cloud.repository.UpwindVulnerabilityFindingRepository;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.PerformanceInsightsService;
import com.xammer.cloud.service.ResourceIdResolver;
import com.xammer.cloud.service.ResourceIdResolver.CanonicalResource;
import com.xammer.cloud.service.compliance.ComplianceOpsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The aggregator's own duties: group by product, normalise severity, sort most-severe-first within
 * a group, isolate each product's failure into {@code errors[]}, and emit the exact matched
 * ComplianceOps uid as the deep-link param.
 */
@ExtendWith(MockitoExtension.class)
class ResourceSecurityAggregationServiceTest {

    private static final String AWS_ID = "123456789012";
    private static final long ROW_ID = 42L;
    private static final String ARN = "arn:aws:ec2:ap-south-1:123456789012:instance/i-0abc";
    private static final String SHORT_ID = "i-0abc";
    // A genuine short-id resource whose findings live in the DB under a full ARN.
    private static final String BUCKET_ID = "xrms-document-bucket";
    private static final String BUCKET_ARN = "arn:aws:s3:::xrms-document-bucket";

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private ResourceIdResolver idResolver;
    @Mock private CloudSecurityFindingRepository securityFindingRepository;
    @Mock private ComplianceOpsService complianceOpsService;
    @Mock private PerformanceInsightsService performanceInsightsService;
    @Mock private UpwindPostureFindingRepository upwindPostureRepository;
    @Mock private UpwindVulnerabilityFindingRepository upwindVulnerabilityRepository;
    @Mock private DashboardDataService dashboardDataService;
    @Mock private SecurityFindingProjectionService liveFindings;
    @Mock private SecurityFindingResolutionService resolutions;

    private ResourceSecurityAggregationService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        service = new ResourceSecurityAggregationService(cloudAccountRepository, idResolver,
                securityFindingRepository, complianceOpsService, performanceInsightsService,
                upwindPostureRepository, upwindVulnerabilityRepository,
                dashboardDataService, liveFindings, resolutions);
        account = new CloudAccount();
        account.setId(ROW_ID);
        account.setAwsAccountId(AWS_ID);
        lenient().when(cloudAccountRepository.findByAwsAccountId(AWS_ID))
                .thenReturn(List.of(account));
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(
                Map.of(ARN, new CanonicalResource(SHORT_ID, "EC2 Instance", "prod-web-01",
                        "ap-south-1", ARN)));
        // Default: XamSecure contributes nothing, so the existing group-count assertions stay
        // meaningful. The XamSecure-specific tests override these stubs.
        lenient().when(upwindPostureRepository.findFailingByResource(anyList(), anyString()))
                .thenReturn(List.of());
        lenient().when(upwindVulnerabilityRepository.countOpenBySeverityForResource(anyList(), anyString()))
                .thenReturn(List.of());
        // Default: no cached security snapshot, so the merged Security Center source contributes
        // nothing and the existing findByResource-based assertions keep their exact expected
        // counts. The merged-source tests override this stub.
        lenient().when(dashboardDataService.getSecuritySnapshot(anyString(), any(), anyInt()))
                .thenReturn(null);
    }

    @Test
    @DisplayName("Groups every product, normalises severity, and sorts most-severe-first in a group")
    void groupsNormalisesAndSorts() {
        CloudSecurityFinding warn = finding("Config drift", "WARNING");
        CloudSecurityFinding crit = finding("Public bucket", "CRITICAL");
        when(securityFindingRepository.findByResource(ROW_ID, ARN))
                .thenReturn(List.of(warn, crit));
        when(complianceOpsService.getReport(eq(String.valueOf(ROW_ID)), anyInt()))
                .thenReturn(reportWith(control("Encrypt EBS volumes", "HIGH", ARN)));
        when(performanceInsightsService.getInsights(eq(AWS_ID), any(), eq(false)))
                .thenReturn(List.of(insight("Underutilised instance", InsightSeverity.WEAK_WARNING, SHORT_ID)));

        ResourceSecurityFindings body = aggregate();

        assertTrue(body.isMatched());
        assertEquals(3, body.getGroups().size());
        assertTrue(body.getErrors().isEmpty());

        ResourceSecurityGroup security = group(body, "SECURITY_CENTER");
        // WARNING normalises to MEDIUM; CRITICAL stays CRITICAL and sorts first.
        assertEquals("CRITICAL", security.getFindings().get(0).getSeverity());
        assertEquals("MEDIUM", security.getFindings().get(1).getSeverity());
        assertEquals("/security", security.getFindings().get(0).getDeepLink().getPath());
        assertEquals(ARN, security.getFindings().get(0).getDeepLink().getQuery().get("resource"));

        // Performance WEAK_WARNING -> LOW via the enum's name(), and short-id deep link.
        ResourceSecurityGroup perf = group(body, "PERFORMANCE");
        assertEquals("LOW", perf.getFindings().get(0).getSeverity());
        assertEquals(SHORT_ID, perf.getFindings().get(0).getDeepLink().getQuery().get("resource"));
    }

    @Test
    @DisplayName("ComplianceOps deep-links on the exact matched uid, not a derived candidate")
    void complianceOpsEmitsTheMatchedUid() {
        when(complianceOpsService.getReport(eq(String.valueOf(ROW_ID)), anyInt()))
                .thenReturn(reportWith(control("Encrypt EBS volumes", "HIGH", ARN)));

        ResourceSecurityGroup compliance = group(aggregate(), "COMPLIANCEOPS");

        assertNotNull(compliance);
        assertEquals(ARN, compliance.getFindings().get(0).getDeepLink().getQuery().get("resource"));
    }

    @Test
    @DisplayName("One product throwing becomes an errors[] entry, the others still render")
    void oneFailingProductIsIsolated() {
        when(securityFindingRepository.findByResource(ROW_ID, ARN))
                .thenReturn(List.of(finding("Public bucket", "CRITICAL")));
        when(complianceOpsService.getReport(anyString(), anyInt()))
                .thenThrow(new RuntimeException("compliance store down"));
        when(performanceInsightsService.getInsights(eq(AWS_ID), any(), eq(false)))
                .thenReturn(List.of());

        ResourceSecurityFindings body = aggregate();

        assertNotNull(group(body, "SECURITY_CENTER"));
        assertEquals(1, body.getErrors().size());
        assertEquals("COMPLIANCEOPS", body.getErrors().get(0).getProduct());
    }

    @Test
    @DisplayName("A short (non-ARN) id skips the ARN-only product reads and resolves matched=false")
    void aShortIdSkipsArnOnlyReads() {
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(Map.of());
        when(performanceInsightsService.getInsights(eq(AWS_ID), any(), eq(false)))
                .thenReturn(List.of(insight("Underutilised instance", InsightSeverity.CRITICAL, SHORT_ID)));

        ResponseEntity<Object> response = service.aggregate(AWS_ID, SHORT_ID, null);
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();

        assertNotNull(body);
        assertFalse(body.isMatched());
        // Only Performance can match a short id; Security Center and ComplianceOps are ARN-keyed.
        assertEquals(1, body.getGroups().size());
        assertEquals("PERFORMANCE", body.getGroups().get(0).getProduct());
    }

    @Test
    @DisplayName("A short-id resource derives its inventory ARN and matches ARN-keyed findings")
    void aShortIdDerivesItsArnAndMatchesSecurityCenter() {
        // The drawer holds a bare bucket name; the inventory row carries the full ARN.
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(
                Map.of(BUCKET_ID, new CanonicalResource(BUCKET_ID, "S3 Bucket",
                        "xrms-document-bucket", "ap-south-1", BUCKET_ARN)));
        // The finding exists in the DB keyed by the full ARN — not the short id.
        CloudSecurityFinding row = finding("Public bucket", "CRITICAL", BUCKET_ARN);
        when(securityFindingRepository.findByResource(ROW_ID, BUCKET_ARN))
                .thenReturn(List.of(row));

        ResponseEntity<Object> response = service.aggregate(AWS_ID, BUCKET_ID, null);
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();

        assertNotNull(body);
        assertTrue(body.isMatched());
        // (b) the derived ARN is what reached the repository.
        verify(securityFindingRepository).findByResource(ROW_ID, BUCKET_ARN);
        // (c) the finding stored under that ARN comes back in a non-empty group.
        ResourceSecurityGroup security = group(body, "SECURITY_CENTER");
        assertNotNull(security);
        assertFalse(security.getFindings().isEmpty());
        assertEquals("Public bucket", security.getFindings().get(0).getTitle());
        // The deep link carries the derived ARN the rows were read under.
        assertEquals(BUCKET_ARN, security.getFindings().get(0).getDeepLink().getQuery().get("resource"));
    }

    @Test
    @DisplayName("A resolved row with no ARN leaves ARN-keyed products empty without an NPE")
    void aResolvedRowWithoutArnDoesNotMatchArnKeyedProducts() {
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(
                Map.of(BUCKET_ID, new CanonicalResource(BUCKET_ID, "S3 Bucket",
                        "xrms-document-bucket", "ap-south-1", null)));
        when(performanceInsightsService.getInsights(eq(AWS_ID), any(), eq(false)))
                .thenReturn(List.of(insight("Underutilised instance", InsightSeverity.CRITICAL, BUCKET_ID)));

        ResponseEntity<Object> response = service.aggregate(AWS_ID, BUCKET_ID, null);
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();

        assertNotNull(body);
        assertTrue(body.isMatched());
        // The ARN-keyed products contribute nothing (no ARN to read under) and are dropped.
        assertEquals(1, body.getGroups().size());
        assertEquals("PERFORMANCE", body.getGroups().get(0).getProduct());
    }

    @Test
    @DisplayName("XamSecure: posture rows render individually and vulns collapse to one worst-severity summary")
    void xamSecureEmitsPostureRowsAndOneVulnerabilitySummary() {
        when(upwindPostureRepository.findFailingByResource(List.of(AWS_ID), ARN)).thenReturn(List.of(
                posture("Enable VPC flow logs", "HIGH", "AWS_EC2_INSTANCE", "up-1"),
                posture("Restrict public ingress", "CRITICAL", "AWS_EC2_INSTANCE", "up-2")));
        when(upwindVulnerabilityRepository.countOpenBySeverityForResource(List.of(AWS_ID), ARN))
                .thenReturn(List.of(new Object[]{"CRITICAL", 2L}, new Object[]{"HIGH", 5L}));

        ResourceSecurityGroup xamSecure = group(aggregate(), "XAMSECURE");

        assertNotNull(xamSecure);
        assertEquals("XamSecure", xamSecure.getLabel());
        // Two posture rows + exactly one vulnerability summary row.
        assertEquals(3, xamSecure.getFindings().size());
        // Most-severe-first: a CRITICAL leads (either the critical posture row or the summary).
        assertEquals("CRITICAL", xamSecure.getFindings().get(0).getSeverity());

        // The single vulnerability summary carries the merged counts at the worst severity.
        ResourceSecurityFindings.ResourceSecurityFinding summary = xamSecure.getFindings().stream()
                .filter(f -> "/xamsecure/vulnerabilities".equals(f.getDeepLink().getPath()))
                .reduce((a, b) -> { throw new AssertionError("more than one vulnerability summary row"); })
                .orElseThrow();
        assertEquals("CRITICAL", summary.getSeverity());
        assertTrue(summary.getTitle().contains("7 vulnerabilities"), summary.getTitle());
        assertTrue(summary.getTitle().contains("2 critical"), summary.getTitle());
        assertTrue(summary.getTitle().contains("5 high"), summary.getTitle());

        // Posture rows deep-link to the posture tab, no query param.
        long postureRows = xamSecure.getFindings().stream()
                .filter(f -> "/xamsecure/posture".equals(f.getDeepLink().getPath()))
                .count();
        assertEquals(2, postureRows);
        xamSecure.getFindings().forEach(f -> assertTrue(f.getDeepLink().getQuery().isEmpty()));
    }

    @Test
    @DisplayName("XamSecure: posture rows sharing an identity collapse to one finding at the worst severity")
    void xamSecurePostureDedupesByIdentityKeepingWorstSeverityAndMostRecentRepresentative() {
        // Three FAIL rows for the SAME check on the SAME resource+region — distinct upwind_ids
        // (the historical-scan rows) and differing severities. These are the rows the old
        // upwind_id de-dup failed to collapse. The identity key is
        // normalise(checkTitle)+resourceType+region, so sharing those collapses them to one.
        // syncedAt decides the representative; the most-recent row's resourceType is emitted.
        Instant t1 = Instant.parse("2024-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2024-02-01T00:00:00Z");
        Instant t3 = Instant.parse("2024-03-01T00:00:00Z");
        when(upwindPostureRepository.findFailingByResource(List.of(AWS_ID), ARN)).thenReturn(List.of(
                posture("Public bucket", "LOW", "AWS_S3_BUCKET", "up-1", "ap-south-1", t3, t3),
                posture("Public bucket", "CRITICAL", "AWS_S3_BUCKET", "up-2", "ap-south-1", t2, t2),
                posture("Public bucket", "HIGH", "AWS_S3_BUCKET", "up-3", "ap-south-1", t1, t1)));
        // No vulnerabilities, so only the posture finding is asserted.
        when(upwindVulnerabilityRepository.countOpenBySeverityForResource(List.of(AWS_ID), ARN))
                .thenReturn(List.of());

        ResourceSecurityGroup xamSecure = group(aggregate(), "XAMSECURE");

        assertNotNull(xamSecure);
        // Exactly ONE posture finding despite three historical-scan rows.
        assertEquals(1, xamSecure.getFindings().size());
        ResourceSecurityFindings.ResourceSecurityFinding finding = xamSecure.getFindings().get(0);
        // Severity is the WORST across the whole group, not the most-recent row's own LOW.
        assertEquals("CRITICAL", finding.getSeverity());
        // Title comes from the (shared) check title; emitted from the most-recent-by-syncedAt row.
        assertEquals("Public bucket", finding.getTitle());
        assertEquals("/xamsecure/posture", finding.getDeepLink().getPath());
    }

    @Test
    @DisplayName("XamSecure: posture rows of different checks stay as distinct findings")
    void xamSecurePostureKeepsDistinctChecksSeparate() {
        Instant t = Instant.parse("2024-01-01T00:00:00Z");
        when(upwindPostureRepository.findFailingByResource(List.of(AWS_ID), ARN)).thenReturn(List.of(
                posture("Enable VPC flow logs", "HIGH", "AWS_EC2_INSTANCE", "up-1", "ap-south-1", t, t),
                posture("Restrict public ingress", "CRITICAL", "AWS_EC2_INSTANCE", "up-2", "ap-south-1", t, t)));
        when(upwindVulnerabilityRepository.countOpenBySeverityForResource(List.of(AWS_ID), ARN))
                .thenReturn(List.of());

        ResourceSecurityGroup xamSecure = group(aggregate(), "XAMSECURE");

        assertNotNull(xamSecure);
        // Different check titles → two distinct posture findings.
        assertEquals(2, xamSecure.getFindings().size());
        // Most-severe-first ordering is preserved.
        assertEquals("CRITICAL", xamSecure.getFindings().get(0).getSeverity());
        assertEquals("HIGH", xamSecure.getFindings().get(1).getSeverity());
    }

    @Test
    @DisplayName("XamSecure contributes nothing when the resource has no ARN")
    void xamSecureEmptyWhenNoArn() {
        // A resolved row whose arn() is null and whose short id is a non-ARN bucket name.
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(
                Map.of(BUCKET_ID, new CanonicalResource(BUCKET_ID, "S3 Bucket",
                        "xrms-document-bucket", "ap-south-1", null)));
        when(performanceInsightsService.getInsights(eq(AWS_ID), any(), eq(false)))
                .thenReturn(List.of(insight("Underutilised instance", InsightSeverity.CRITICAL, BUCKET_ID)));

        ResponseEntity<Object> response = service.aggregate(AWS_ID, BUCKET_ID, null);
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();

        assertNotNull(body);
        // No ARN → ARN-primary XamSecure read is attempted only under the short-id secondary key,
        // which returns nothing here, so the empty XamSecure group is dropped.
        assertNull(group(body, "XAMSECURE"));
    }

    @Test
    @DisplayName("A failing XamSecure read becomes an errors[] entry without failing other groups")
    void xamSecureFailureIsIsolated() {
        when(securityFindingRepository.findByResource(ROW_ID, ARN))
                .thenReturn(List.of(finding("Public bucket", "CRITICAL")));
        when(upwindPostureRepository.findFailingByResource(List.of(AWS_ID), ARN))
                .thenThrow(new RuntimeException("upwind store down"));

        ResourceSecurityFindings body = aggregate();

        // Other groups still render: the Security Center read succeeded despite XamSecure throwing.
        assertNotNull(group(body, "SECURITY_CENTER"));
        assertNull(group(body, "XAMSECURE"));
        // The XamSecure failure is captured as its own errors[] entry.
        assertTrue(body.getErrors().stream().anyMatch(e -> "XAMSECURE".equals(e.getProduct())),
                "expected a XAMSECURE error entry");
    }

    @Test
    @DisplayName("Security Center reads the merged snapshot when the finding stores the full ARN")
    void securityCenterReadsMergedSnapshotWhenFindingStoresFullArn() {
        // The drawer holds a bare bucket name; the inventory row carries the full ARN.
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(
                Map.of(BUCKET_ID, new CanonicalResource(BUCKET_ID, "S3 Bucket",
                        "xrms-document-bucket", "ap-south-1", BUCKET_ARN)));
        // The merged snapshot holds the finding keyed by the FULL ARN.
        DashboardData.SecurityFinding f = securityFinding(BUCKET_ARN,
                "Public Access Block is not fully enabled", "HIGH", "S3");
        stubMergedSnapshot(List.of(f));
        when(securityFindingRepository.findByResource(ROW_ID, BUCKET_ARN)).thenReturn(List.of());

        ResponseEntity<Object> response = service.aggregate(AWS_ID, BUCKET_ID, null);
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();

        assertNotNull(body);
        ResourceSecurityGroup security = group(body, "SECURITY_CENTER");
        assertNotNull(security);
        assertEquals(1, security.getFindings().size());
        assertEquals("Public Access Block is not fully enabled",
                security.getFindings().get(0).getTitle());
        assertEquals("HIGH", security.getFindings().get(0).getSeverity());
        // Deep link carries the ARN the page's own filter re-matches on.
        assertEquals(BUCKET_ARN, security.getFindings().get(0).getDeepLink().getQuery().get("resource"));
    }

    @Test
    @DisplayName("Security Center reads the merged snapshot when the finding stores just the bucket name")
    void securityCenterReadsMergedSnapshotWhenFindingStoresBucketName() {
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(
                Map.of(BUCKET_ID, new CanonicalResource(BUCKET_ID, "S3 Bucket",
                        "xrms-document-bucket", "ap-south-1", BUCKET_ARN)));
        // Prowler-style row: the resource id is the SHORT bucket name, not the ARN.
        DashboardData.SecurityFinding f = securityFinding(BUCKET_ID,
                "Public Access Block is not fully enabled", "HIGH", "S3");
        stubMergedSnapshot(List.of(f));
        when(securityFindingRepository.findByResource(ROW_ID, BUCKET_ARN)).thenReturn(List.of());

        ResponseEntity<Object> response = service.aggregate(AWS_ID, BUCKET_ID, null);
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();

        assertNotNull(body);
        ResourceSecurityGroup security = group(body, "SECURITY_CENTER");
        assertNotNull(security);
        // ARN-tolerant match: BUCKET_ARN ends with ":::" + short name (the ":"-form branch).
        assertEquals(1, security.getFindings().size());
        assertEquals("Public Access Block is not fully enabled",
                security.getFindings().get(0).getTitle());
    }

    @Test
    @DisplayName("Security Center never triggers a live/comprehensive scan — cached read only")
    void securityCenterNeverTriggersLiveScan() {
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(
                Map.of(BUCKET_ID, new CanonicalResource(BUCKET_ID, "S3 Bucket",
                        "xrms-document-bucket", "ap-south-1", BUCKET_ARN)));
        DashboardData.SecurityFinding f = securityFinding(BUCKET_ARN,
                "Public Access Block is not fully enabled", "HIGH", "S3");
        stubMergedSnapshot(List.of(f));
        when(securityFindingRepository.findByResource(ROW_ID, BUCKET_ARN)).thenReturn(List.of());

        service.aggregate(AWS_ID, BUCKET_ID, null);

        // Only the cached snapshot + merge + resolution path is exercised. No SecurityService or
        // CloudListService is injected into this service, so a live scan is impossible by
        // construction — the "no heavy scan" guarantee is structural, asserted here by confirming
        // the cached-read path (with the exact cache key and 12h max age) is the only path taken.
        verify(dashboardDataService).getSecuritySnapshot(
                eq("security-" + AWS_ID), any(), eq(12));
        verify(liveFindings).mergeInto(anyList(), eq(ROW_ID));
        verify(resolutions).filterResolved(anyList(), eq(ROW_ID));
    }

    @Test
    @DisplayName("Security Center is a graceful empty group when the snapshot is null — no NPE, no scan")
    void securityCenterEmptyWhenSnapshotNull() {
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(
                Map.of(BUCKET_ID, new CanonicalResource(BUCKET_ID, "S3 Bucket",
                        "xrms-document-bucket", "ap-south-1", BUCKET_ARN)));
        // Snapshot is null (default stub) AND the legacy table has nothing.
        when(securityFindingRepository.findByResource(ROW_ID, BUCKET_ARN)).thenReturn(List.of());
        when(performanceInsightsService.getInsights(eq(AWS_ID), any(), eq(false)))
                .thenReturn(List.of(insight("Underutilised instance", InsightSeverity.CRITICAL, BUCKET_ID)));

        ResponseEntity<Object> response = service.aggregate(AWS_ID, BUCKET_ID, null);
        ResourceSecurityFindings body = (ResourceSecurityFindings) response.getBody();

        assertNotNull(body);
        // The empty Security Center group is dropped; other groups are unaffected.
        assertNull(group(body, "SECURITY_CENTER"));
        assertNotNull(group(body, "PERFORMANCE"));
        // A null snapshot must never reach the merge/resolution collaborators.
        verify(liveFindings, never()).mergeInto(anyList(), any());
        verify(resolutions, never()).filterResolved(anyList(), any());
    }

    @Test
    @DisplayName("Security Center unions the merged snapshot and the legacy table, deduped by title+resource")
    void securityCenterUnionDedupesSnapshotAndDbRows() {
        // Resolve to an ARN resource so both sources are read under the same resource.
        DashboardData.SecurityFinding f = securityFinding(ARN, "Public bucket", "CRITICAL", "S3");
        stubMergedSnapshot(List.of(f));
        // The legacy table carries the same finding title on the same resource.
        when(securityFindingRepository.findByResource(ROW_ID, ARN))
                .thenReturn(List.of(finding("Public bucket", "CRITICAL")));

        ResourceSecurityFindings body = aggregate();

        ResourceSecurityGroup security = group(body, "SECURITY_CENTER");
        assertNotNull(security);
        // Exactly ONE "Public bucket" finding — deduped across the two sources.
        assertEquals(1, security.getFindings().size());
        assertEquals("Public bucket", security.getFindings().get(0).getTitle());
    }

    @Test
    @DisplayName("An unknown account is a 404")
    void unknownAccountIsNotFound() {
        when(cloudAccountRepository.findByAwsAccountId("000000000000")).thenReturn(List.of());
        assertEquals(404, service.aggregate("000000000000", ARN, null).getStatusCode().value());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private ResourceSecurityFindings aggregate() {
        ResponseEntity<Object> response = service.aggregate(AWS_ID, ARN, null);
        assertEquals(200, response.getStatusCode().value());
        return (ResourceSecurityFindings) response.getBody();
    }

    private ResourceSecurityGroup group(ResourceSecurityFindings body, String product) {
        return body.getGroups().stream()
                .filter(g -> product.equals(g.getProduct()))
                .findFirst()
                .orElse(null);
    }

    private DashboardData.SecurityFinding securityFinding(String resourceId, String description,
                                                          String severity, String category) {
        DashboardData.SecurityFinding f = new DashboardData.SecurityFinding();
        f.setResourceId(resourceId);
        f.setDescription(description);
        f.setSeverity(severity);
        f.setCategory(category);
        return f;
    }

    /**
     * Stubs the cached-read path the way the Security Center page wires it: the snapshot getter
     * returns {@code findings}, and the merge/resolution collaborators pass their input through.
     */
    @SuppressWarnings("unchecked")
    private void stubMergedSnapshot(List<DashboardData.SecurityFinding> findings) {
        lenient().when(dashboardDataService.getSecuritySnapshot(
                        eq("security-" + AWS_ID), any(TypeReference.class), eq(12)))
                .thenReturn(findings);
        lenient().when(liveFindings.mergeInto(anyList(), eq(ROW_ID)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(resolutions.filterResolved(anyList(), eq(ROW_ID)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private CloudSecurityFinding finding(String title, String severity) {
        return finding(title, severity, ARN);
    }

    private CloudSecurityFinding finding(String title, String severity, String resourceArn) {
        CloudSecurityFinding f = new CloudSecurityFinding();
        f.setTitle(title);
        f.setSeverity(severity);
        f.setResourceArn(resourceArn);
        return f;
    }

    private UpwindPostureFinding posture(String checkTitle, String severity, String resourceType,
                                         String upwindId) {
        return posture(checkTitle, severity, resourceType, upwindId, null, null, null);
    }

    private UpwindPostureFinding posture(String checkTitle, String severity, String resourceType,
                                         String upwindId, String region, Instant syncedAt,
                                         Instant lastSeenTime) {
        UpwindPostureFinding f = new UpwindPostureFinding();
        f.setUpwindId(upwindId);
        f.setCheckTitle(checkTitle);
        f.setSeverity(severity);
        f.setResourceType(resourceType);
        f.setStatus("FAIL");
        f.setRegion(region);
        f.setSyncedAt(syncedAt);
        f.setLastSeenTime(lastSeenTime);
        return f;
    }

    private ComplianceReport reportWith(ComplianceReport.ControlView... controls) {
        ComplianceReport report = new ComplianceReport();
        report.setControls(List.of(controls));
        return report;
    }

    private ComplianceReport.ControlView control(String title, String severity, String uid) {
        ComplianceReport.ControlView control = new ComplianceReport.ControlView();
        control.setTitle(title);
        control.setSeverity(severity);
        control.setStatus("FAIL");
        ComplianceReport.ResourceRef ref = new ComplianceReport.ResourceRef();
        ref.setUid(uid);
        control.setResources(List.of(ref));
        return control;
    }

    private PerformanceInsightDto insight(String title, InsightSeverity severity, String resourceId) {
        PerformanceInsightDto dto = new PerformanceInsightDto();
        dto.setInsight(title);
        dto.setSeverity(severity);
        dto.setResourceId(resourceId);
        return dto;
    }
}
