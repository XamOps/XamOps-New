package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.service.setup.SetupStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

/**
 * Hourly: for each connected account, writes every closed hour (per region) to the customer's bucket as Parquet,
 * then records it in the day manifest. The watermark moves only after every region of an hour is stored, keys are
 * deterministic so a retry overwrites, and three failed runs in a row mark the account DEGRADED and start the
 * bounded retention hold. OFF unless {@code xampulse.archive.enabled=true}; never run against a real ClickHouse/S3 here.
 */
@Component("xpArchiveWriterJob")
@ConditionalOnProperty(name = "xampulse.archive.enabled", havingValue = "true")
public class ArchiveWriterJob {

    private static final Logger log = LoggerFactory.getLogger(ArchiveWriterJob.class);

    private final XpAccountConfigRepository configs;
    private final XpAccountRegionRepository regions;
    private final CloudAccountRepository accounts;
    private final LogHourExporter exporter;
    private final ArchiveStore store;
    private final ArchiveManifestService manifests;
    private final TenantPurgeService purge;
    private final SchedulingEnvironmentConfig scheduling;
    private final XamPulseProperties props;
    private final Clock clock;
    private final ArchiveFailureAlerter alerter;

    @org.springframework.beans.factory.annotation.Autowired
    public ArchiveWriterJob(XpAccountConfigRepository configs, XpAccountRegionRepository regions, CloudAccountRepository accounts,
                            LogHourExporter exporter, ArchiveStore store, ArchiveManifestService manifests, TenantPurgeService purge,
                            SchedulingEnvironmentConfig scheduling, XamPulseProperties props, ArchiveFailureAlerter alerter) {
        this(configs, regions, accounts, exporter, store, manifests, purge, scheduling, props, Clock.systemUTC(), alerter);
    }

    ArchiveWriterJob(XpAccountConfigRepository configs, XpAccountRegionRepository regions, CloudAccountRepository accounts,
                     LogHourExporter exporter, ArchiveStore store, ArchiveManifestService manifests, TenantPurgeService purge,
                     SchedulingEnvironmentConfig scheduling, XamPulseProperties props, Clock clock) {
        this(configs, regions, accounts, exporter, store, manifests, purge, scheduling, props, clock, (c, n, e) -> { });
    }

    ArchiveWriterJob(XpAccountConfigRepository configs, XpAccountRegionRepository regions, CloudAccountRepository accounts,
                     LogHourExporter exporter, ArchiveStore store, ArchiveManifestService manifests, TenantPurgeService purge,
                     SchedulingEnvironmentConfig scheduling, XamPulseProperties props, Clock clock, ArchiveFailureAlerter alerter) {
        this.configs = configs;
        this.regions = regions;
        this.accounts = accounts;
        this.exporter = exporter;
        this.store = store;
        this.manifests = manifests;
        this.purge = purge;
        this.scheduling = scheduling;
        this.props = props;
        this.clock = clock;
        this.alerter = alerter;
    }

    @Scheduled(cron = "0 5 * * * *")
    public void runAll() {
        if (!scheduling.isSchedulingEnabled()) {
            return;
        }
        for (XpAccountConfig cfg : configs.findAll()) {
            XpAccountState state = SetupStateMachine.parse(cfg.getState());
            if (state != XpAccountState.CONNECTED && state != XpAccountState.DEGRADED) {
                continue;
            }
            try {
                accounts.findById(cfg.getCloudAccountId()).ifPresent(a -> runForAccount(cfg, a.getAwsAccountId()));
            } catch (RuntimeException e) {
                log.warn("XamPulse archive run failed for account {}: {}", cfg.getCloudAccountId(), e.getClass().getSimpleName());
            }
        }
    }

    /** Archives up to {@code maxHoursPerRun} closed hours; returns how many hours were fully stored. */
    public int runForAccount(XpAccountConfig cfg, String awsAccountId) {
        XamPulseProperties.Archive a = props.getArchive();
        Instant now = clock.instant();
        Instant limit = now.minus(Duration.ofMinutes(a.getGraceMinutes())).truncatedTo(ChronoUnit.HOURS);
        Instant hour = cfg.getArchiveWatermark() != null ? cfg.getArchiveWatermark()
                : now.minus(Duration.ofDays(Math.max(1, cfg.getHotDays()))).truncatedTo(ChronoUnit.HOURS);
        List<String> regionNames = regions.findByCloudAccountId(cfg.getCloudAccountId()).stream()
                .map(XpAccountRegion::getRegion).distinct().toList();
        int done = 0;
        try {
            while (done < a.getMaxHoursPerRun() && !hour.plus(Duration.ofHours(1)).isAfter(limit)) {
                for (String region : regionNames) {
                    archiveHour(cfg, awsAccountId, region, hour);
                }
                hour = hour.plus(Duration.ofHours(1));
                cfg.setArchiveWatermark(hour);
                cfg.setArchiveFailures(0);
                cfg.setArchiveLastError(null);
                configs.save(cfg);
                done++;
            }
        } catch (Exception e) {
            recordFailure(cfg, e, now);
        }
        return done;
    }

    void archiveHour(XpAccountConfig cfg, String awsAccountId, String region, Instant hour) throws IOException {
        Path dir = props.getArchive().getTempDir().isBlank() ? null : Path.of(props.getArchive().getTempDir());
        Path tmp = dir == null ? Files.createTempFile("xp-archive-", ".parquet") : Files.createTempFile(dir, "xp-archive-", ".parquet");
        try {
            LogHourExporter.Exported ex = exporter.export(cfg.getCloudAccountId(), region, hour, tmp);
            if (ex == null) {
                return; // nothing to store for this hour and region
            }
            String key = ArchiveKeyLayout.logKey(awsAccountId, region, hour, props.getArchive().getNodeId(), 0);
            long bytes = Files.size(tmp);
            String sha = sha256(tmp);
            store.putFile(cfg, key, tmp);
            manifests.addEntry(cfg, awsAccountId, hour.atZone(ZoneOffset.UTC).toLocalDate(),
                    new ArchiveManifest.Entry(key, region, ex.rows(), bytes, ex.minTsMs(), ex.maxTsMs(), sha, "STANDARD"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void recordFailure(XpAccountConfig cfg, Exception e, Instant now) {
        cfg.setArchiveFailures(cfg.getArchiveFailures() + 1);
        // Class name only: messages from the AWS SDK or HTTP client can echo request details.
        cfg.setArchiveLastError(e.getClass().getSimpleName());
        log.warn("XamPulse archive failure {} for account {}: {}", cfg.getArchiveFailures(), cfg.getCloudAccountId(), e.getClass().getSimpleName());
        if (cfg.getArchiveFailures() >= props.getArchive().getFailureThreshold()) {
            XpAccountState state = SetupStateMachine.parse(cfg.getState());
            if (state == XpAccountState.CONNECTED) {
                cfg.setState(SetupStateMachine.move(state, XpAccountState.DEGRADED).name());
                cfg.setUpdatedAt(now);
            }
            Instant at = cfg.getArchiveWatermark();
            long unarchivedHours = at == null ? 0 : Duration.between(at, now).toHours();
            // Within 24 h of the oldest unarchived hour expiring: hold the data a little longer.
            if (unarchivedHours >= (long) Math.max(1, cfg.getHotDays()) * 24 - 24) {
                purge.extendHold(cfg);
            }
        }
        configs.save(cfg);
        // Alert once per streak: exactly when the consecutive-failure count reaches the threshold.
        if (cfg.getArchiveFailures() == props.getArchive().getFailureThreshold()) {
            try {
                alerter.archiveFailing(cfg, cfg.getArchiveFailures(), e.getClass().getSimpleName());
            } catch (RuntimeException alertFailure) {
                log.warn("XamPulse archive alert failed for account {}: {}", cfg.getCloudAccountId(), alertFailure.getClass().getSimpleName());
            }
        }
    }

    static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
