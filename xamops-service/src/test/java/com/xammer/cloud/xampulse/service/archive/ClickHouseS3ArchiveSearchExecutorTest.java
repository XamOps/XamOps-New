package com.xammer.cloud.xampulse.service.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.query.FilterDslParser;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** SQL and URL level only: nothing here talks to ClickHouse or S3. */
class ClickHouseS3ArchiveSearchExecutorTest {

    private static final String AWS = "123456789012";
    private final ClickHouseS3ArchiveSearchExecutor exec =
            new ClickHouseS3ArchiveSearchExecutor(new XamPulseProperties(), mock(ArchiveRoleCredentialProvider.class), new ObjectMapper());
    private final ArchiveRoleCredentialProvider.RoleCredentials creds =
            new ArchiveRoleCredentialProvider.RoleCredentials("AKIATESTKEY", "test-secret-value", "test-session-token");

    private XpAccountConfig cfg() {
        XpAccountConfig c = new XpAccountConfig();
        c.setBucketName("my-logs");
        c.setBucketRegion("ap-south-1");
        return c;
    }

    private ArchiveManifest.Entry entry(String key) {
        return new ArchiveManifest.Entry(key, "ap-south-1", 1, 1, 0, 1, "a".repeat(64), "STANDARD");
    }

    private QueryScope scope() {
        return QueryScope.of(List.of(7L), Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z"));
    }

    private static final String KEY = "xamops-xampulse/v1/logs/account=123456789012/region=ap-south-1/year=2026/month=11/day=01/hour=01/part-n1-0.parquet";

    @Test
    void theTenantIsAServerSideParameterAndIsAlsoComparedInTheWhereClause() {
        var st = exec.statement(cfg(), AWS, scope(), entry(KEY), FilterDslParser.parse("service:nginx"), 1000, creds);
        assertEquals("7", st.params().get("xp_tenant"));
        assertTrue(st.sql().contains("toUInt64({xp_tenant:UInt64}) AS tenant_id"));
        assertTrue(st.sql().contains("tenant_id IN ({xp_p0:Int64})"), st.sql());
        assertEquals("7", st.params().get("xp_p0"));
        assertTrue(st.sql().endsWith("FORMAT JSONEachRow"));
        assertTrue(st.sql().contains("ORDER BY ts DESC"));
    }

    @Test
    void noValueIsEverInterpolatedIntoTheSqlText() {
        String hostile = "x' OR 1=1 --";
        var st = exec.statement(cfg(), AWS, scope(), entry(KEY), FilterDslParser.parse("\"" + hostile + "\""), 1000, creds);
        assertFalse(st.sql().contains("OR 1=1"));
        assertFalse(st.sql().contains("AKIATESTKEY"));
        assertFalse(st.sql().contains("test-secret-value"));
        assertFalse(st.sql().contains("test-session-token"));
        assertFalse(st.sql().contains("?"), "all placeholders are native parameters");
        assertTrue(st.params().containsValue(hostile));
        assertEquals("AKIATESTKEY", st.params().get("xp_ak"));
    }

    @Test
    void hostileQueryTextIsRejectedByTheDslParserBeforeItReachesSql() {
        assertThrows(XamPulseException.class, () -> FilterDslParser.parse("severity:("));
        assertThrows(XamPulseException.class, () -> FilterDslParser.parse("nosuchfield:abc"));
    }

    @Test
    void onlyKeysUnderTheTenantsOwnPrefixBecomeUrls() {
        assertEquals("https://my-logs.s3.ap-south-1.amazonaws.com/" + KEY, ClickHouseS3ArchiveSearchExecutor.objectUrl(cfg(), AWS, KEY));
        for (String bad : new String[] {
                "xamops-xampulse/v1/logs/account=999999999999/part.parquet",
                "other/prefix.parquet",
                "xamops-xampulse/v1/logs/account=123456789012/../../secrets",
                "xamops-xampulse/v1/logs/account=123456789012/a b.parquet",
                "xamops-xampulse/v1/logs/account=123456789012/x'.parquet"}) {
            assertThrows(IllegalArgumentException.class, () -> ClickHouseS3ArchiveSearchExecutor.objectUrl(cfg(), AWS, bad), bad);
        }
        XpAccountConfig badBucket = cfg();
        badBucket.setBucketName("evil.com/../x");
        assertThrows(IllegalArgumentException.class, () -> ClickHouseS3ArchiveSearchExecutor.objectUrl(badBucket, AWS, KEY));
    }

    @Test
    void requestUrlCarriesParametersAndTheTenantSettingEncoded() {
        var st = exec.statement(cfg(), AWS, scope(), entry(KEY), FilterDslParser.parse(""), 10, creds);
        String url = ClickHouseS3ArchiveSearchExecutor.requestUrl("http://ch:8123", st, 7L, 120);
        assertTrue(url.startsWith("http://ch:8123/?query="));
        assertTrue(url.contains("&param_xp_tenant=7"));
        assertTrue(url.contains("&SQL_xp_tenant=7"));
        assertTrue(url.contains("&max_execution_time=120"));
        assertFalse(url.contains(" "), "everything is URL encoded");
    }

    @Test
    void errorsMapToStableCodes() {
        assertEquals("ARCHIVE_ACCESS_DENIED", ClickHouseS3ArchiveSearchExecutor.classify(500, "Code: 499. AccessDenied"));
        assertEquals("ARCHIVE_ACCESS_DENIED", ClickHouseS3ArchiveSearchExecutor.classify(403, ""));
        assertEquals("ARCHIVE_OBJECT_MISSING", ClickHouseS3ArchiveSearchExecutor.classify(500, "NoSuchKey: The specified key does not exist"));
        assertEquals("ARCHIVE_TIMEOUT", ClickHouseS3ArchiveSearchExecutor.classify(500, "Code: 159. Timeout exceeded"));
        assertEquals("ARCHIVE_INTERNAL", ClickHouseS3ArchiveSearchExecutor.classify(500, "something else"));
    }

    @Test
    void severityComparisonsKeepWorkingBecauseTheSubselectCastsTheEnum() {
        var st = exec.statement(cfg(), AWS, scope(), entry(KEY), FilterDslParser.parse("severity:>=warn"), 10, creds);
        assertTrue(st.sql().contains("CAST(severity, 'Enum8("), st.sql());
        assertTrue(st.sql().contains("toUInt8(severity) >= {xp_p"));
    }
}
