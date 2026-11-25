package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        GcpSecurityService service = new GcpSecurityService(clients, redis, repo, new ObjectMapper(), prowler,
                mock(JdbcTemplate.class));

        service.getSecurityFindings("p", false).join();
        List<GcpSecurityFinding> second = service.getSecurityFindings("p", false).join();

        assertTrue(second.isEmpty());
        verify(clients, times(1)).getSecurityCenterV2Client("p");
    }
    private static final String LATEST = "GCP_SECURITY::p::LATEST";
    private GcpSecurityService serviceWithStoredLatest(CachedDataRepository repo, JdbcTemplate jdbc,
                                                       GcpClientProvider clients) {
        RedisCacheService redis = mock(RedisCacheService.class);
        ProwlerService prowler = mock(ProwlerService.class);
        when(redis.get(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.empty());
        when(clients.getSecurityCenterV2Client("p")).thenReturn(Optional.empty());
        when(prowler.getCachedGcpFindings("p")).thenReturn(List.of());
        return new GcpSecurityService(clients, redis, repo, new ObjectMapper(), prowler, jdbc);
    }
    @Test
    void oversizedStoredSnapshotIsNeverLoadedAndTheRequestScansLive() {
        CachedDataRepository repo = mock(CachedDataRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GcpClientProvider clients = mock(GcpClientProvider.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(LATEST))).thenReturn(List.of(30_000_000L));
        serviceWithStoredLatest(repo, jdbc, clients).getSecurityFindings("p", false).join();
        verify(repo, never()).findById(LATEST);
        verify(clients, times(1)).getSecurityCenterV2Client("p");
    }
    @Test
    void unmeasurableStoredSnapshotIsTreatedAsTooLargeToLoad() {
        CachedDataRepository repo = mock(CachedDataRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(LATEST)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("size", "SELECT",
                        new java.sql.SQLException("permission denied for table pg_largeobject")));
        serviceWithStoredLatest(repo, jdbc, mock(GcpClientProvider.class)).getSecurityFindings("p", false).join();
        verify(repo, never()).findById(LATEST);
        verify(repo, never()).deleteOversizedByCacheKey(LATEST);
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
