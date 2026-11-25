package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.ScopedSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IngestQuotaServiceTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private ClickHouseExecutor executor;
    private XpAccountConfigRepository configs;
    private XamPulseProperties props;
    private MutableClock clock;
    private IngestQuotaService service;

    @BeforeEach
    void setUp() {
        executor = mock(ClickHouseExecutor.class);
        configs = mock(XpAccountConfigRepository.class);
        props = new XamPulseProperties();
        clock = new MutableClock(Instant.parse("2026-03-10T12:00:00Z"));
        service = new IngestQuotaService(executor, configs, props, clock);
        setQuota(BigDecimal.ONE);
    }

    private void setQuota(BigDecimal gb) {
        XpAccountConfig cfg = new XpAccountConfig();
        cfg.setIngestQuotaGbDay(gb);
        when(configs.findByCloudAccountId(77L)).thenReturn(Optional.of(cfg));
    }

    private void bytesToday(long... perDay) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (long b : perDay) {
            rows.add(Map.of("b", b));
        }
        when(executor.query(any(ScopedSql.class))).thenReturn(rows);
    }

    @Test
    void underQuotaIsAllowed() {
        bytesToday(GIB - 1);
        assertTrue(service.retryAfterIfExceeded(77L).isEmpty());
    }

    @Test
    void atQuotaIsRefusedWithRetryAfterCappedAtTheConfiguredMax() {
        bytesToday(GIB);
        // 12 hours to midnight, capped at 300 s.
        assertEquals(OptionalLong.of(300), service.retryAfterIfExceeded(77L));
    }

    @Test
    void retryAfterShrinksToMidnightAndTheCacheDoesNotCarryOverTheDayBoundary() {
        bytesToday(2 * GIB);
        clock.now = Instant.parse("2026-03-10T23:58:20Z");
        assertEquals(OptionalLong.of(100), service.retryAfterIfExceeded(77L));
        // New UTC day: the cached total is discarded and the (now empty) rollup is read again.
        clock.now = Instant.parse("2026-03-11T00:00:05Z");
        bytesToday();
        assertTrue(service.retryAfterIfExceeded(77L).isEmpty());
        verify(executor, times(2)).query(any(ScopedSql.class));
    }

    @Test
    void nullOrZeroQuotaIsUnlimitedAndNeverQueriesClickHouse() {
        setQuota(null);
        assertTrue(service.retryAfterIfExceeded(77L).isEmpty());
        setQuota(BigDecimal.ZERO);
        assertTrue(service.retryAfterIfExceeded(77L).isEmpty());
        when(configs.findByCloudAccountId(88L)).thenReturn(Optional.empty());
        assertTrue(service.retryAfterIfExceeded(88L).isEmpty());
        verifyNoInteractions(executor);
    }

    @Test
    void disabledFlagSkipsEverything() {
        props.getQuota().setEnabled(false);
        assertTrue(service.retryAfterIfExceeded(77L).isEmpty());
        verifyNoInteractions(executor, configs);
    }

    @Test
    void cacheHitDoesNotRequery() {
        bytesToday(GIB / 2);
        service.retryAfterIfExceeded(77L);
        clock.now = clock.now.plusSeconds(30);
        service.retryAfterIfExceeded(77L);
        verify(executor, times(1)).query(any(ScopedSql.class));
        clock.now = clock.now.plusSeconds(31);
        service.retryAfterIfExceeded(77L);
        verify(executor, times(2)).query(any(ScopedSql.class));
    }

    @Test
    void sumsAllRowsAndFailsOpenWhenClickHouseErrors() {
        bytesToday(GIB / 2, GIB / 2);
        assertTrue(service.retryAfterIfExceeded(77L).isPresent());

        when(executor.query(any(ScopedSql.class))).thenThrow(XamPulseException.timeout());
        clock.now = clock.now.plusSeconds(120);
        assertTrue(service.retryAfterIfExceeded(77L).isEmpty(), "fail open");
    }
}
