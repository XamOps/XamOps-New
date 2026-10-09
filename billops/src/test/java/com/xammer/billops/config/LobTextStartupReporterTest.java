package com.xammer.billops.config;

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
 * AC 18 and design §3.6 for billops: exactly one INFO line with the write mode and its source, and a
 * WARN when the Spring property and the Hibernate setting disagree. billops has no startup-job flags,
 * so nothing else is logged.
 */
class LobTextStartupReporterTest {

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
    void byDefaultItLogsLegacyFromTheDefaultOnceAndNothingElse() {
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "legacy");      // the base-file bridge default

        report();

        assertThat(messages(Level.INFO)).containsExactly("LOB-text write mode: legacy (default)");
        assertThat(log.list).hasSize(1);
    }

    @Test
    void anAbsentHibernateSettingIsLegacyToo() {
        report();

        assertThat(messages(Level.INFO)).containsExactly("LOB-text write mode: legacy (default)");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void aConfiguredInlineModeIsReportedAsConfigured() {
        environment.setProperty(LobTextWriteMode.PROPERTY, "inline");
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "inline");

        report();

        assertThat(messages(Level.INFO)).containsExactly("LOB-text write mode: inline (configured)");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void aConfiguredLegacyModeIsReportedAsConfigured() {
        environment.setProperty(LobTextWriteMode.PROPERTY, " Legacy ");
        hibernateSettings.put(LobTextWriteMode.PROPERTY, " Legacy ");

        report();

        assertThat(messages(Level.INFO)).containsExactly("LOB-text write mode: legacy (configured)");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void aHibernateSettingThatDisagreesWithTheSpringPropertyWarnsAndTheHibernateValueGoverns() {
        environment.setProperty(LobTextWriteMode.PROPERTY, "legacy");
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "inline");

        report();

        assertThat(messages(Level.INFO)).containsExactly("LOB-text write mode: inline (configured)");
        assertThat(messages(Level.WARN)).singleElement().asString()
                .startsWith("LOB-text write mode mismatch: xammer.lob-text.write-mode gives legacy")
                .contains("spring.jpa.properties.xammer.lob-text.write-mode gives inline")
                .endsWith("the Hibernate setting governs");
    }

    @Test
    void aHibernateSettingWithoutTheSpringPropertyWarns() {
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "inline");     // set through spring.jpa.properties directly

        report();

        assertThat(messages(Level.INFO)).containsExactly("LOB-text write mode: inline (default)");
        assertThat(messages(Level.WARN)).singleElement().asString()
                .startsWith("LOB-text write mode mismatch: xammer.lob-text.write-mode gives legacy");
    }

    @Test
    void anInvalidSpringValueBehindAValidHibernateSettingWarns() {
        environment.setProperty(LobTextWriteMode.PROPERTY, "sideways");
        hibernateSettings.put(LobTextWriteMode.PROPERTY, "legacy");

        report();

        assertThat(messages(Level.INFO)).containsExactly("LOB-text write mode: legacy (configured)");
        assertThat(messages(Level.WARN)).singleElement().asString()
                .startsWith("LOB-text write mode mismatch: xammer.lob-text.write-mode gives invalid");
    }

    // ------------------------------------------------------------------ helpers

    private void report() {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        when(emf.getProperties()).thenReturn(hibernateSettings);
        new LobTextStartupReporter(emf, environment).report();
    }

    private List<String> messages(Level level) {
        return log.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }
}
