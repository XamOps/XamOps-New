package com.xammer.cloud.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xammer.cloud.domain.type.LobTextWriteMode;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC 18 and design §3.6 for xamops-service: exactly one INFO line with the write mode and its source,
 * one INFO line with the four flags, and a WARN for each misleading combination.
 */
class LobTextStartupReporterTest {

    private static final String GCP_FLUSH = "gcp.billing.net-cost-cache-migration.enabled";
    private static final String GCP_REFILL = "gcp.billing.history-snapshot-on-startup.enabled";
    private static final String AWS_REFILL = "aws.billing.history-snapshot-on-startup.enabled";
    private static final String CONVERT_ON_READ = "xammer.lob-text.convert-on-read.enabled";

    private final Logger logger = (Logger) LoggerFactory.getLogger(LobTextStartupReporter.class);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Map<String, Object> hibernateSettings = new HashMap<>();
    private final MockEnvironment environment = new MockEnvironment();

    @BeforeEach
    void captureLog() {
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(log);
    }

    @Test
    void byDefaultItLogsLegacyFromTheDefaultAndEveryFlagOffWithoutWarnings() {
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "legacy");      // the base-file bridge default

        report();

        assertThat(modeLines()).containsExactly("LOB-text write mode: legacy (default)");
        assertThat(flagLines()).containsExactly("LOB-text startup flags: " + GCP_FLUSH + "=false " + GCP_REFILL
                + "=false " + AWS_REFILL + "=false " + CONVERT_ON_READ + "=false");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void anAbsentHibernateSettingIsLegacyToo() {
        report();

        assertThat(modeLines()).containsExactly("LOB-text write mode: legacy (default)");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void aConfiguredInlineModeIsReportedAsConfigured() {
        environment.setProperty(LobTextWriteMode.PROPERTY, "inline");
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "inline");

        report();

        assertThat(modeLines()).containsExactly("LOB-text write mode: inline (configured)");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void aHibernateSettingThatDisagreesWithTheSpringPropertyWarnsAndTheHibernateValueGoverns() {
        environment.setProperty(LobTextWriteMode.PROPERTY, "legacy");
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "inline");

        report();

        assertThat(modeLines()).containsExactly("LOB-text write mode: inline (configured)");
        assertThat(messages(Level.WARN)).singleElement().asString()
                .startsWith("LOB-text write mode mismatch: xammer.lob-text.write-mode gives legacy")
                .contains("spring.jpa.properties.xammer.lob-text.write-mode gives inline")
                .endsWith("the Hibernate setting governs");
    }

    @Test
    void anInvalidSpringValueBehindAValidHibernateSettingWarns() {
        environment.setProperty(LobTextWriteMode.PROPERTY, "sideways");
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "legacy");

        report();

        assertThat(modeLines()).containsExactly("LOB-text write mode: legacy (configured)");
        assertThat(messages(Level.WARN)).singleElement().asString()
                .startsWith("LOB-text write mode mismatch: xammer.lob-text.write-mode gives invalid");
    }

    @Test
    void theFlushWithoutTheRefillWarns() {
        environment.setProperty(GCP_FLUSH, "true");

        report();

        assertThat(flagLines()).singleElement().asString().contains(GCP_FLUSH + "=true").contains(GCP_REFILL + "=false");
        assertThat(messages(Level.WARN)).singleElement().asString()
                .contains(GCP_FLUSH + "=true").contains(GCP_REFILL + "=false").contains("stay empty");
    }

    @Test
    void theFlushTogetherWithTheRefillDoesNotWarn() {
        environment.setProperty(GCP_FLUSH, "true");
        environment.setProperty(GCP_REFILL, "true");
        environment.setProperty(AWS_REFILL, "true");

        report();

        assertThat(flagLines()).containsExactly("LOB-text startup flags: " + GCP_FLUSH + "=true " + GCP_REFILL
                + "=true " + AWS_REFILL + "=true " + CONVERT_ON_READ + "=false");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void convertOnReadInLegacyModeWarnsThatItIsIgnored() {
        environment.setProperty(CONVERT_ON_READ, "true");

        report();

        assertThat(messages(Level.WARN)).singleElement().asString()
                .contains(CONVERT_ON_READ + "=true is ignored in legacy write mode");
    }

    @Test
    void convertOnReadInInlineModeDoesNotWarn() {
        environment.setProperty(CONVERT_ON_READ, "true");
        environment.setProperty(LobTextWriteMode.PROPERTY, "inline");
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "inline");

        report();

        assertThat(flagLines()).singleElement().asString().contains(CONVERT_ON_READ + "=true");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private void report() {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        when(emf.getProperties()).thenReturn(hibernateSettings);
        new LobTextStartupReporter(emf, environment).report();
    }

    private List<String> modeLines() {
        return messages(Level.INFO).stream().filter(m -> m.startsWith("LOB-text write mode:")).toList();
    }

    private List<String> flagLines() {
        return messages(Level.INFO).stream().filter(m -> m.startsWith("LOB-text startup flags:")).toList();
    }

    private List<String> messages(Level level) {
        return log.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }
}
