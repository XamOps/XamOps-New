package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GcpSecurityBackoffTest {

    @Test
    void failingProjectIsNotRetriedInsideTheBackoffWindow() {
        GcpClientProvider clients = mock(GcpClientProvider.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        CachedDataRepository repo = mock(CachedDataRepository.class);
        ProwlerService prowler = mock(ProwlerService.class);
        when(redis.get(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.empty());
        when(clients.getSecurityCenterV2Client("p")).thenReturn(Optional.empty());
        when(prowler.getCachedGcpFindings("p")).thenReturn(List.of());

        GcpSecurityService service = new GcpSecurityService(clients, redis, repo, new ObjectMapper(), prowler);

        service.getSecurityFindings("p", false).join();
        List<GcpSecurityFinding> second = service.getSecurityFindings("p", false).join();

        assertTrue(second.isEmpty());
        verify(clients, times(1)).getSecurityCenterV2Client("p");
    }

    @Test
    void collectorKeepsExactTotalsButBoundsRetainedFindings() {
        GcpSecurityFindingCollector collector = new GcpSecurityFindingCollector(10);
        for (int i = 0; i < 100; i++) {
            collector.add(new GcpSecurityFinding("C", "d", i % 2 == 0 ? "LOW" : "CRITICAL", "r" + i, null));
        }
        List<GcpSecurityFinding> kept = collector.retained();
        assertEquals(10, kept.size());
        assertTrue(kept.stream().allMatch(f -> "CRITICAL".equals(f.getSeverity())));
        GcpSecurityDigest digest = collector.digest(kept.size());
        assertEquals(100, digest.total());
        assertEquals(50L, digest.severityTotals().get("CRITICAL"));
        assertTrue(digest.truncated());
    }
}
