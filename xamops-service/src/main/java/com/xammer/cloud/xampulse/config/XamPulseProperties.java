package com.xammer.cloud.xampulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Settings for XamPulse (Observability 1.0). Every default is the safe/off value; passwords and
 * keys come from the environment only (see {@code application.properties}).
 */
@ConfigurationProperties(prefix = "xampulse")
public class XamPulseProperties {

    private boolean enabled = false;
    private final ClickHouse clickhouse = new ClickHouse();
    private final Gateway gateway = new Gateway();
    private final Ws ws = new Ws();
    private final Demo demo = new Demo();
    private final Limits limits = new Limits();
    private final Jwt jwt = new Jwt();
    private final Setup setup = new Setup();
    private final Archive archive = new Archive();
    private final Baseline baseline = new Baseline();
    private final Usage usage = new Usage();
    private final Quota quota = new Quota();
    private String internalSecret = "";
    private String hotRegion = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public ClickHouse getClickhouse() { return clickhouse; }
    public Gateway getGateway() { return gateway; }
    public Ws getWs() { return ws; }
    public Demo getDemo() { return demo; }
    public Limits getLimits() { return limits; }
    public Jwt getJwt() { return jwt; }
    public Setup getSetup() { return setup; }
    public Archive getArchive() { return archive; }
    public Baseline getBaseline() { return baseline; }
    public Usage getUsage() { return usage; }
    public Quota getQuota() { return quota; }
    public String getInternalSecret() { return internalSecret; }
    public void setInternalSecret(String internalSecret) { this.internalSecret = internalSecret; }
    public String getHotRegion() { return hotRegion; }
    public void setHotRegion(String hotRegion) { this.hotRegion = hotRegion; }

    public static class ClickHouse {
        private String url = "";
        private String database = "xampulse";
        private String readerUser = "";
        private String readerPassword = "";
        private String writerUser = "";
        private String writerPassword = "";
        private boolean replicated = false;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String getReaderUser() { return readerUser; }
        public void setReaderUser(String readerUser) { this.readerUser = readerUser; }
        public String getReaderPassword() { return readerPassword; }
        public void setReaderPassword(String readerPassword) { this.readerPassword = readerPassword; }
        public String getWriterUser() { return writerUser; }
        public void setWriterUser(String writerUser) { this.writerUser = writerUser; }
        public String getWriterPassword() { return writerPassword; }
        public void setWriterPassword(String writerPassword) { this.writerPassword = writerPassword; }
        public boolean isReplicated() { return replicated; }
        public void setReplicated(boolean replicated) { this.replicated = replicated; }
    }

    public static class Gateway {
        private String publicUrl = "";
        public String getPublicUrl() { return publicUrl; }
        public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl; }
    }

    public static class Ws {
        private String tailUrl = "/xampulse-ws/tail";
        public String getTailUrl() { return tailUrl; }
        public void setTailUrl(String tailUrl) { this.tailUrl = tailUrl; }
    }

    public static class Demo {
        private boolean enabled = false;
        private List<String> allowedProfiles = List.of("local", "dev");
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getAllowedProfiles() { return allowedProfiles; }
        public void setAllowedProfiles(List<String> allowedProfiles) { this.allowedProfiles = allowedProfiles; }
    }

    public static class Limits {
        private int maxRangeDays = 30;
        private int maxRows = 1000;
        private int queryTimeoutSec = 15;
        private int concurrentQueriesPerTenant = 3;
        public int getMaxRangeDays() { return maxRangeDays; }
        public void setMaxRangeDays(int v) { this.maxRangeDays = v; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int v) { this.maxRows = v; }
        public int getQueryTimeoutSec() { return queryTimeoutSec; }
        public void setQueryTimeoutSec(int v) { this.queryTimeoutSec = v; }
        public int getConcurrentQueriesPerTenant() { return concurrentQueriesPerTenant; }
        public void setConcurrentQueriesPerTenant(int v) { this.concurrentQueriesPerTenant = v; }
    }

    public static class Jwt {
        private String privateKeyPem = "";
        private String publicKeyPem = "";
        private String issuer = "xamops-xampulse";
        private String keyId = "k1";
        public String getPrivateKeyPem() { return privateKeyPem; }
        public void setPrivateKeyPem(String v) { this.privateKeyPem = v; }
        public String getPublicKeyPem() { return publicKeyPem; }
        public void setPublicKeyPem(String v) { this.publicKeyPem = v; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String v) { this.issuer = v; }
        public String getKeyId() { return keyId; }
        public void setKeyId(String v) { this.keyId = v; }
    }

    /** Onboarding (slice B). Nothing here is secret. */
    public static class Setup {
        private String templateBaseUrl = "";
        private boolean uat = false;
        private String xamopsAccountId = "";
        private String principalRoleName = "XamOps-App-EC2-Role";
        private String apiBaseUrl = "";
        private String releaseBaseUrl = "";
        private long watcherIntervalMs = 30000;
        private String latestAgentVersion = "";
        private String releasePublicKeyPem = "";
        public String getTemplateBaseUrl() { return templateBaseUrl; }
        public void setTemplateBaseUrl(String v) { this.templateBaseUrl = v; }
        public boolean isUat() { return uat; }
        public void setUat(boolean v) { this.uat = v; }
        public String getXamopsAccountId() { return xamopsAccountId; }
        public void setXamopsAccountId(String v) { this.xamopsAccountId = v; }
        public String getPrincipalRoleName() { return principalRoleName; }
        public void setPrincipalRoleName(String v) { this.principalRoleName = v; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String v) { this.apiBaseUrl = v; }
        public String getReleaseBaseUrl() { return releaseBaseUrl; }
        public void setReleaseBaseUrl(String v) { this.releaseBaseUrl = v; }
        public String getLatestAgentVersion() { return latestAgentVersion; }
        public void setLatestAgentVersion(String v) { this.latestAgentVersion = v; }
        /** Pinned public key (PEM, ECDSA P-256, cosign format) agents must verify upgrades against. Blank = upgrades are manual. */
        public String getReleasePublicKeyPem() { return releasePublicKeyPem; }
        public void setReleasePublicKeyPem(String v) { this.releasePublicKeyPem = v == null ? "" : v; }
        public boolean autoUpgradeAllowed() { return !releasePublicKeyPem.isBlank(); }
        public long getWatcherIntervalMs() { return watcherIntervalMs; }
        public void setWatcherIntervalMs(long v) { this.watcherIntervalMs = v; }
    }

    /** Log archive writer (slice B-5). OFF by default; never run against a real ClickHouse/S3 in this repo's tests. */
    public static class Archive {
        private boolean enabled = false;
        /** ClickHouse HTTP interface base URL (for example http://clickhouse:8123); Parquet cannot be streamed over JDBC. */
        private String clickhouseHttpUrl = "";
        private String nodeId = "n1";
        private String tempDir = "";
        private int maxHoursPerRun = 6;
        private int graceMinutes = 10;
        private int failureThreshold = 3;
        private double standardUsdPerGbMonth = 0.023;
        private double iaUsdPerGbMonth = 0.0125;
        private double glacierIrUsdPerGbMonth = 0.004;
        private double getUsdPer1000 = 0.0004;
        private int maxSearchFiles = 2000;
        private int maxSearchRows = 1000;
        private int searchTimeoutSec = 120;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getClickhouseHttpUrl() { return clickhouseHttpUrl; }
        public void setClickhouseHttpUrl(String v) { this.clickhouseHttpUrl = v == null ? "" : v; }
        public String getNodeId() { return nodeId; }
        public void setNodeId(String v) { this.nodeId = v; }
        public String getTempDir() { return tempDir; }
        public void setTempDir(String v) { this.tempDir = v == null ? "" : v; }
        public int getMaxHoursPerRun() { return maxHoursPerRun; }
        public void setMaxHoursPerRun(int v) { this.maxHoursPerRun = v; }
        public int getGraceMinutes() { return graceMinutes; }
        public void setGraceMinutes(int v) { this.graceMinutes = v; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int v) { this.failureThreshold = v; }
        public double getStandardUsdPerGbMonth() { return standardUsdPerGbMonth; }
        public void setStandardUsdPerGbMonth(double v) { this.standardUsdPerGbMonth = v; }
        public double getIaUsdPerGbMonth() { return iaUsdPerGbMonth; }
        public void setIaUsdPerGbMonth(double v) { this.iaUsdPerGbMonth = v; }
        public double getGlacierIrUsdPerGbMonth() { return glacierIrUsdPerGbMonth; }
        public void setGlacierIrUsdPerGbMonth(double v) { this.glacierIrUsdPerGbMonth = v; }
        public double getGetUsdPer1000() { return getUsdPer1000; }
        public void setGetUsdPer1000(double v) { this.getUsdPer1000 = v; }
        public int getMaxSearchFiles() { return maxSearchFiles; }
        public void setMaxSearchFiles(int v) { this.maxSearchFiles = v; }
        public int getMaxSearchRows() { return maxSearchRows; }
        public void setMaxSearchRows(int v) { this.maxSearchRows = v; }
        public int getSearchTimeoutSec() { return searchTimeoutSec; }
        public void setSearchTimeoutSec(int v) { this.searchTimeoutSec = v; }
    }

    /**
     * Baseline writer (slice E). Recomputes per hour-of-week median/MAD bands from the hourly rollup. Safe to leave
     * on: it only reads tenant-scoped rollups and writes the ReplacingMergeTree xp_baseline table.
     */
    public static class Baseline {
        private boolean enabled = true;
        private int windowDays = 14;
        private String cron = "0 20 * * * *";
        private List<String> metrics = List.of("system.cpu.utilization", "system.memory.utilization",
                "system.filesystem.utilization", "system.cpu.load_average.1m");
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getWindowDays() { return windowDays; }
        public void setWindowDays(int v) { this.windowDays = v; }
        public String getCron() { return cron; }
        public void setCron(String v) { this.cron = v; }
        public List<String> getMetrics() { return metrics; }
        public void setMetrics(List<String> v) { this.metrics = v; }
    }

    /**
     * Usage metering writer (slice E). Upserts xp_usage_daily per (day, source) from the minute log-volume rollup so
     * the Storage &amp; Retention view reports real bytes_in / per-source breakdown. {@code bytesStored} is a rollup
     * byte-sum approximation, not true ClickHouse compressed size. Safe to leave on: reads tenant-scoped rollups only.
     */
    public static class Usage {
        private boolean enabled = true;
        /** Recompute this many trailing days each run so late-arriving rollup rows correct recent days. */
        private int lookbackDays = 3;
        private String cron = "0 35 * * * *";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getLookbackDays() { return lookbackDays; }
        public void setLookbackDays(int v) { this.lookbackDays = v; }
        public String getCron() { return cron; }
        public void setCron(String v) { this.cron = v; }
    }
    /**
     * Ingest quota enforcement at the auth path: when today's (UTC) ingested bytes reach the account's
     * {@code ingestQuotaGbDay}, ingest is answered with 429 + Retry-After. Fails open when ClickHouse errors.
     */
    public static class Quota {
        private boolean enabled = true;
        private int cacheSeconds = 60;
        private int maxRetryAfterSeconds = 300;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getCacheSeconds() { return cacheSeconds; }
        public void setCacheSeconds(int v) { this.cacheSeconds = v; }
        public int getMaxRetryAfterSeconds() { return maxRetryAfterSeconds; }
        public void setMaxRetryAfterSeconds(int v) { this.maxRetryAfterSeconds = v; }
    }
}
