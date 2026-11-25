package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpArchiveEstimate;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.platform.ClickHouseMutations;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ArchiveEstimateAndRetentionTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:30:00Z");

    static class Rec implements ClickHouseMutations {
        final List<String> sql = new ArrayList<>();
        final List<List<Object>> params = new ArrayList<>();
        boolean fail;
        public void execute(String s, List<Object> p) throws SQLException {
            if (fail) throw new SQLException("boom");
            sql.add(s);
            params.add(p);
        }
    }

    @Test
    void estimateSumsManifestBytesByStorageClassAndPricesRequests() {
        ArchiveWriterJobTest.FakeStore store = new ArchiveWriterJobTest.FakeStore();
        ArchiveManifestService manifests = new ArchiveManifestService(store);
        XpAccountConfig cfg = new XpAccountConfig();
        cfg.setIaAfterDays(30);
        cfg.setGlacierIrAfterDays(90);
        long day = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli();
        manifests.addEntry(cfg, "123456789012", LocalDate.of(2026, 11, 2),
                new ArchiveManifest.Entry("xamops-xampulse/v1/a", "ap-south-1", 5, 1000, day, day + 1000, "x", "STANDARD"));
        manifests.addEntry(cfg, "123456789012", LocalDate.of(2026, 11, 2),
                new ArchiveManifest.Entry("xamops-xampulse/v1/b", "ap-south-1", 5, 3000, day + 5000, day + 6000, "y", "STANDARD"));
        ArchiveSearchService svc = new ArchiveSearchService(manifests, new XamPulseProperties());
        XpArchiveEstimate e = svc.estimate(cfg, "123456789012", Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z"), NOW);
        assertEquals(4000, e.bytes());
        assertEquals(2, e.requests());
        assertEquals(List.of("STANDARD"), e.storageClasses());
        assertEquals("ESTIMATE", e.state());
        assertEquals(0.0, e.usd() - 2 * 0.0004 / 1000.0, 1e-4);
    }

    @Test
    void estimateOfAnEmptyRangeSaysSoAndRefusesHugeRanges() {
        ArchiveSearchService svc = new ArchiveSearchService(new ArchiveManifestService(new ArchiveWriterJobTest.FakeStore()), new XamPulseProperties());
        XpAccountConfig cfg = new XpAccountConfig();
        assertEquals("EMPTY", svc.estimate(cfg, "123456789012", NOW.minusSeconds(3600), NOW, NOW).state());
        assertThrows(IllegalArgumentException.class, () -> svc.estimate(cfg, "123456789012", NOW.minusSeconds(86400L * 40), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> svc.estimate(cfg, "123456789012", NOW, NOW, NOW));
    }

    @Test
    void storageClassFollowsTheCustomersLifecycle() {
        XpAccountConfig cfg = new XpAccountConfig();
        cfg.setIaAfterDays(30);
        cfg.setGlacierIrAfterDays(90);
        assertEquals("STANDARD", ArchiveSearchService.storageClassAt(cfg, NOW.minusSeconds(86400L * 29), NOW));
        assertEquals("STANDARD_IA", ArchiveSearchService.storageClassAt(cfg, NOW.minusSeconds(86400L * 30), NOW));
        assertEquals("GLACIER_IR", ArchiveSearchService.storageClassAt(cfg, NOW.minusSeconds(86400L * 90), NOW));
    }

    private TenantPurgeService purge(Rec rec) {
        return new TenantPurgeService(rec, mock(XpAccountConfigRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void purgeBindsTheTenantAsAParameterOnEveryTableOnly() {
        Rec rec = new Rec();
        assertEquals(TenantPurgeService.TABLES.size(), purge(rec).purge(42L));
        assertEquals(TenantPurgeService.TABLES.size(), rec.sql.size());
        for (int i = 0; i < rec.sql.size(); i++) {
            assertEquals("ALTER TABLE " + TenantPurgeService.TABLES.get(i) + " DELETE WHERE tenant_id = ?", rec.sql.get(i));
            assertEquals(List.of(42L), rec.params.get(i));
            assertFalse(rec.sql.get(i).contains("42"));
        }
    }

    @Test
    void purgeFailsClosedInsteadOfPretending() {
        Rec rec = new Rec();
        rec.fail = true;
        assertThrows(XamPulseException.class, () -> purge(rec).purge(42L));
    }

    @Test
    void extendHoldAddsTwoDaysOnceADayUpToSevenInTotal() {
        Rec rec = new Rec();
        XpAccountConfig cfg = new XpAccountConfig();
        cfg.setCloudAccountId(7L);
        cfg.setHotDays(3);
        TenantPurgeService svc = purge(rec);
        assertTrue(svc.extendHold(cfg));
        assertEquals(2, cfg.getHoldExtendedDays());
        assertTrue(rec.sql.get(0).contains("WHERE tenant_id = ?"));
        assertEquals(7L, rec.params.get(0).get(0));
        assertFalse(svc.extendHold(cfg), "once per 24 h");
        for (int i = 0; i < 2; i++) {
            cfg.setHoldExtendedAt(NOW.minusSeconds(86400 + 1));
            assertTrue(svc.extendHold(cfg));
        }
        assertEquals(6, cfg.getHoldExtendedDays());
        cfg.setHoldExtendedAt(NOW.minusSeconds(86400 + 1));
        assertFalse(svc.extendHold(cfg), "6 + 2 would exceed the 7 day cap");
        assertEquals(3, rec.sql.size());
    }

    @Test
    void exporterBindsTenantRegionAndHourAsParametersAndCarriesNoS3Credentials() {
        String url = ClickHouseHttpLogHourExporter.url("http://ch:8123", ClickHouseHttpLogHourExporter.EXPORT_SQL, 7L, "ap-south-1", 1000L, 2000L, true);
        assertTrue(url.contains("param_tenant=7"));
        assertTrue(url.contains("param_region=ap-south-1"));
        assertTrue(url.contains("SQL_xp_tenant=7"));
        assertTrue(url.contains("output_format_parquet_compression_method=zstd"));
        assertTrue(ClickHouseHttpLogHourExporter.EXPORT_SQL.contains("tenant_id IN ({tenant:UInt64})"));
        assertFalse(ClickHouseHttpLogHourExporter.EXPORT_SQL.toLowerCase().contains("s3("), "ClickHouse never writes to S3 itself");
        assertFalse(ClickHouseHttpLogHourExporter.COLUMNS.contains("*"));
    }
}
