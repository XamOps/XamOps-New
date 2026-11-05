package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpArchiveEstimate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeSet;

/**
 * ESTIMATE ONLY: sums manifest bytes for the range by storage class and prices them with the configured table.
 * Running a search over the archive is not built. The estimate is a retrieval cost: one GET per object plus, for
 * Standard-IA and Glacier IR, the per-GB retrieval is not modelled (the table carries storage prices only), so the
 * dollar figure is a lower bound and says so through {@code state}.
 */
@Service("xpArchiveSearchService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ArchiveSearchService {

    static final int MAX_DAYS = 31;

    private final ArchiveManifestService manifests;
    private final XamPulseProperties props;

    public ArchiveSearchService(ArchiveManifestService manifests, XamPulseProperties props) {
        this.manifests = manifests;
        this.props = props;
    }

    public XpArchiveEstimate estimate(XpAccountConfig cfg, String awsAccountId, Instant from, Instant to, Instant now) {
        if (!to.isAfter(from) || Duration.between(from, to).toDays() > MAX_DAYS) {
            throw new IllegalArgumentException("range must be between 1 second and " + MAX_DAYS + " days");
        }
        long bytes = 0;
        long requests = 0;
        double usd = 0;
        TreeSet<String> classes = new TreeSet<>();
        for (LocalDate d = from.atZone(ZoneOffset.UTC).toLocalDate(); !d.isAfter(to.atZone(ZoneOffset.UTC).toLocalDate()); d = d.plusDays(1)) {
            var m = manifests.read(cfg, awsAccountId, d);
            if (m.isEmpty()) {
                continue;
            }
            for (ArchiveManifest.Entry e : m.get().files()) {
                if (e.maxTsMs() < from.toEpochMilli() || e.minTsMs() >= to.toEpochMilli()) {
                    continue;
                }
                String cls = storageClassAt(cfg, Instant.ofEpochMilli(e.minTsMs()), now);
                classes.add(cls);
                bytes += e.bytes();
                requests++;
                usd += props.getArchive().getGetUsdPer1000() / 1000.0;
            }
        }
        return new XpArchiveEstimate(bytes, requests, round(usd), List.copyOf(classes), requests == 0 ? "EMPTY" : "ESTIMATE");
    }

    /**
     * The manifest entries that overlap the range, oldest day first. The only source of keys an archive search may
     * read: they come from this tenant's own manifests, never from a request.
     */
    public List<ArchiveManifest.Entry> files(XpAccountConfig cfg, String awsAccountId, Instant from, Instant to) {
        if (!to.isAfter(from) || Duration.between(from, to).toDays() > MAX_DAYS) {
            throw new IllegalArgumentException("range must be between 1 second and " + MAX_DAYS + " days");
        }
        List<ArchiveManifest.Entry> out = new java.util.ArrayList<>();
        for (LocalDate d = from.atZone(ZoneOffset.UTC).toLocalDate(); !d.isAfter(to.atZone(ZoneOffset.UTC).toLocalDate()); d = d.plusDays(1)) {
            var m = manifests.read(cfg, awsAccountId, d);
            if (m.isEmpty()) {
                continue;
            }
            for (ArchiveManifest.Entry e : m.get().files()) {
                if (e.maxTsMs() >= from.toEpochMilli() && e.minTsMs() < to.toEpochMilli()) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** Where an object of this age sits under the customer's lifecycle rules. */
    static String storageClassAt(XpAccountConfig cfg, Instant objectTime, Instant now) {
        long ageDays = Duration.between(objectTime, now).toDays();
        if (cfg.getGlacierIrAfterDays() > 0 && ageDays >= cfg.getGlacierIrAfterDays()) {
            return "GLACIER_IR";
        }
        if (cfg.getIaAfterDays() > 0 && ageDays >= cfg.getIaAfterDays()) {
            return "STANDARD_IA";
        }
        return "STANDARD";
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
