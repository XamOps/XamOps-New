package com.xammer.cloud.service.security;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.PerformanceInsightDto.InsightSeverity;
import com.xammer.cloud.dto.compliance.ComplianceReport;
import com.xammer.cloud.dto.security.ResourceSecurityFindings;
import com.xammer.cloud.dto.security.ResourceSecurityFindings.ResourceSecurityGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CloudSecurityFindingRepository;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private ResourceIdResolver idResolver;
    @Mock private CloudSecurityFindingRepository securityFindingRepository;
    @Mock private ComplianceOpsService complianceOpsService;
    @Mock private PerformanceInsightsService performanceInsightsService;

    private ResourceSecurityAggregationService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        service = new ResourceSecurityAggregationService(cloudAccountRepository, idResolver,
                securityFindingRepository, complianceOpsService, performanceInsightsService);
        account = new CloudAccount();
        account.setId(ROW_ID);
        account.setAwsAccountId(AWS_ID);
        lenient().when(cloudAccountRepository.findByAwsAccountId(AWS_ID))
                .thenReturn(List.of(account));
        lenient().when(idResolver.resolve(eq(ROW_ID), any())).thenReturn(
                Map.of(ARN, new CanonicalResource(SHORT_ID, "EC2 Instance", "prod-web-01", "ap-south-1")));
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

    private CloudSecurityFinding finding(String title, String severity) {
        CloudSecurityFinding f = new CloudSecurityFinding();
        f.setTitle(title);
        f.setSeverity(severity);
        f.setResourceArn(ARN);
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
