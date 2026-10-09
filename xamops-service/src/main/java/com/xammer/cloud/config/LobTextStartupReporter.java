package com.xammer.cloud.config;

import com.xammer.cloud.domain.type.LobTextWriteMode;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Logs, once at startup, how this instance treats the legacy large-object TEXT columns
 * (design §3.6, NFR-6, FR-2.5, FR-2.7).
 *
 * <ul>
 *   <li>One INFO line with the write mode and where it came from. The mode is
 *       {@link LobTextWriteMode#effective(EntityManagerFactory)}, the Hibernate setting the mapped
 *       type uses. A WARN follows when the Spring property disagrees with it; the Hibernate value
 *       governs.</li>
 *   <li>One INFO line with the four startup-job and convert-on-read flags, and a WARN for each
 *       combination that does not do what an operator would expect.</li>
 * </ul>
 * Only flag names, booleans and modes are logged.
 */
@Component
public class LobTextStartupReporter {

    static final String GCP_FLUSH = "gcp.billing.net-cost-cache-migration.enabled";
    static final String GCP_REFILL = "gcp.billing.history-snapshot-on-startup.enabled";
    static final String AWS_REFILL = "aws.billing.history-snapshot-on-startup.enabled";
    static final String CONVERT_ON_READ = "xammer.lob-text.convert-on-read.enabled";

    private static final Logger log = LoggerFactory.getLogger(LobTextStartupReporter.class);

    private final EntityManagerFactory entityManagerFactory;
    private final Environment environment;

    public LobTextStartupReporter(EntityManagerFactory entityManagerFactory, Environment environment) {
        this.entityManagerFactory = entityManagerFactory;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        LobTextWriteMode mode = LobTextWriteMode.effective(entityManagerFactory);
        boolean configured = environment.containsProperty(LobTextWriteMode.PROPERTY);
        log.info("LOB-text write mode: {} ({})", label(mode), configured ? "configured" : "default");

        String springMode = springMode();
        if (!label(mode).equals(springMode)) {
            log.warn("LOB-text write mode mismatch: {} gives {} but the Hibernate setting"
                            + " spring.jpa.properties.{} gives {}; the Hibernate setting governs",
                    LobTextWriteMode.PROPERTY, springMode, LobTextWriteMode.PROPERTY, label(mode));
        }

        boolean gcpFlush = flag(GCP_FLUSH);
        boolean gcpRefill = flag(GCP_REFILL);
        boolean awsRefill = flag(AWS_REFILL);
        boolean convertOnRead = flag(CONVERT_ON_READ);
        log.info("LOB-text startup flags: {}={} {}={} {}={} {}={}",
                GCP_FLUSH, gcpFlush, GCP_REFILL, gcpRefill, AWS_REFILL, awsRefill, CONVERT_ON_READ, convertOnRead);
        if (gcpFlush && !gcpRefill) {
            log.warn("{}=true but {}=false: the flushed GCP months stay empty until the refill runs."
                    + " Enable both for the same boot", GCP_FLUSH, GCP_REFILL);
        }
        if (convertOnRead && mode == LobTextWriteMode.LEGACY) {
            log.warn("{}=true is ignored in legacy write mode; it takes effect only with {}=inline",
                    CONVERT_ON_READ, LobTextWriteMode.PROPERTY);
        }
    }

    /** The mode the Spring property asks for, or {@code invalid}. */
    private String springMode() {
        try {
            return label(LobTextWriteMode.parse(environment.getProperty(LobTextWriteMode.PROPERTY)));
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
    }

    private boolean flag(String name) {
        return environment.getProperty(name, Boolean.class, false);
    }

    private static String label(LobTextWriteMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
