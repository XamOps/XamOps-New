package com.xammer.cloud.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 5.11 on a real logback {@link LoggerContext}: which framework lines are dropped, the end-to-end
 * leak check, and the {@code logback-spring.xml} wiring.
 */
class DspmQueryStringTurboFilterTest {

    private static final String KEY = DspmQueryStringTurboFilter.MDC_SCOPE_KEY;

    private static final String DISPATCHER = "org.springframework.web.servlet.DispatcherServlet";
    private static final String REST_TEMPLATE = "org.springframework.web.client.RestTemplate";
    private static final String BODY_PROCESSOR =
            "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor";
    private static final String OWN_LOGGER = "com.xammer.cloud.service.matters.DatastoreListService";

    private static final String XAMLENS_REQUEST_LINE = "GET \"/api/dspm/datastores?q=needle\", parameters={masked}";
    private static final String MATTERS_CALL_LINE = "HTTP GET https://app.matters.ai/api/v1/assets?q=needle";
    private static final String WRITING_LINE = "Writing [DatastoreFilePage[rows=[FileRow[path=needle/report.csv";
    private static final String OTHER_HOST_LINE = "HTTP GET https://vendor.example.test/api/v1/assets?q=needle";
    private static final String ERROR_DISPATCH_LINE = "\"ERROR\" dispatch for GET \"/error?q=needle\", parameters={masked}";

    private static final String FINOPS_LINE = "GET \"/api/finops/costs?x=1\", parameters={masked}";
    private static final String INFO_LINE = "Completed initialization of /api/dspm/ handlers";
    private static final String OWN_DEBUG_LINE = "listing /api/dspm/datastores for client 10";

    private LoggerContext context;
    private DspmQueryStringTurboFilter filter;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        filter = new DspmQueryStringTurboFilter();
        filter.setContext(context);
        filter.start();
    }

    @AfterEach
    void tearDown() {
        MDC.remove(KEY);
        context.stop();
    }

    private FilterReply decide(String loggerName, Level level, String format) {
        return filter.decide(null, context.getLogger(loggerName), level, format, null, null);
    }

    // ── decisions outside an XamLens request ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a DispatcherServlet DEBUG or TRACE line naming /api/dspm/ is denied")
    void deniesXamLensUrls() {
        assertThat(decide(DISPATCHER, Level.DEBUG, XAMLENS_REQUEST_LINE)).isEqualTo(FilterReply.DENY);
        assertThat(decide(DISPATCHER, Level.TRACE, XAMLENS_REQUEST_LINE)).isEqualTo(FilterReply.DENY);
    }

    @Test
    @DisplayName("the same logger is untouched for another module's URL")
    void leavesOtherUrlsAlone() {
        assertThat(decide(DISPATCHER, Level.DEBUG, FINOPS_LINE)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("INFO and WARN are untouched even for an /api/dspm/ line")
    void leavesInfoAndWarnAlone() {
        assertThat(decide(DISPATCHER, Level.INFO, XAMLENS_REQUEST_LINE)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide(DISPATCHER, Level.WARN, XAMLENS_REQUEST_LINE)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("a RestTemplate DEBUG line to matters.ai is denied")
    void deniesMattersCalls() {
        assertThat(decide(REST_TEMPLATE, Level.DEBUG, MATTERS_CALL_LINE)).isEqualTo(FilterReply.DENY);
    }

    @Test
    @DisplayName("our own DEBUG lines are untouched, even when they name /api/dspm/")
    void leavesOwnLoggersAlone() {
        assertThat(decide(OWN_LOGGER, Level.DEBUG, OWN_DEBUG_LINE)).isEqualTo(FilterReply.NEUTRAL);
    }

    // ── decisions inside an XamLens request ──────────────────────────────────────────────────────

    @Test
    @DisplayName("inside the scope every framework DEBUG line is denied, the isDebugEnabled() probe included")
    void deniesEveryFrameworkLineInsideTheScope() {
        MDC.put(KEY, "1");

        assertThat(decide(BODY_PROCESSOR, Level.DEBUG, WRITING_LINE)).isEqualTo(FilterReply.DENY);
        assertThat(decide(REST_TEMPLATE, Level.DEBUG, OTHER_HOST_LINE)).isEqualTo(FilterReply.DENY);
        assertThat(decide(DISPATCHER, Level.DEBUG, null)).isEqualTo(FilterReply.DENY);
        assertThat(decide(OWN_LOGGER, Level.DEBUG, OWN_DEBUG_LINE)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(decide(BODY_PROCESSOR, Level.WARN, WRITING_LINE)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("inside the scope isDebugEnabled() answers false for framework loggers only")
    void probeAnswersFalseInsideTheScope() {
        context.addTurboFilter(filter);
        Logger dispatcher = context.getLogger(DISPATCHER);
        Logger own = context.getLogger(OWN_LOGGER);

        assertThat(dispatcher.isDebugEnabled()).isTrue();
        MDC.put(KEY, "1");
        assertThat(dispatcher.isDebugEnabled()).isFalse();
        assertThat(own.isDebugEnabled()).isTrue();
    }

    // ── end to end ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("with the filter no needle line reaches the appender; without it every one does")
    void endToEndLeakCheck() {
        LoggerContext plain = new LoggerContext();
        List<ILoggingEvent> unfiltered = logEveryLine(plain);
        plain.stop();
        assertThat(unfiltered).filteredOn(event -> event.getFormattedMessage().contains("needle")).hasSize(5);

        context.addTurboFilter(filter);
        List<ILoggingEvent> filtered = logEveryLine(context);
        assertThat(filtered).noneMatch(event -> event.getFormattedMessage().contains("needle"));

        // The lines the filter must leave alone pass in both runs.
        for (List<ILoggingEvent> events : List.of(unfiltered, filtered)) {
            assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                    .contains(FINOPS_LINE, INFO_LINE, OWN_DEBUG_LINE);
        }
    }

    /** Logs the five needle lines (two outside, three inside the scope) and three control lines. */
    private static List<ILoggingEvent> logEveryLine(LoggerContext target) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(target);
        appender.start();
        Logger root = target.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.DEBUG);
        root.addAppender(appender);
        try {
            target.getLogger(DISPATCHER).debug(XAMLENS_REQUEST_LINE);
            target.getLogger(REST_TEMPLATE).debug(MATTERS_CALL_LINE);
            target.getLogger(DISPATCHER).debug(FINOPS_LINE);
            target.getLogger(DISPATCHER).info(INFO_LINE);
            target.getLogger(OWN_LOGGER).debug(OWN_DEBUG_LINE);

            MDC.put(KEY, "1");
            target.getLogger(BODY_PROCESSOR).debug(WRITING_LINE);
            target.getLogger(REST_TEMPLATE).debug(OTHER_HOST_LINE);
            target.getLogger(DISPATCHER).debug(ERROR_DISPATCH_LINE);
        } finally {
            MDC.remove(KEY);
            root.detachAppender(appender);
        }
        return List.copyOf(appender.list);
    }

    // ── wiring ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("logback-spring.xml registers the filter, and logback can instantiate it")
    void isWiredIntoLogback() throws Exception {
        String line = "<turboFilter class=\"com.xammer.cloud.config.logging.DspmQueryStringTurboFilter\"/>";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("logback-spring.xml")) {
            assertThat(in).isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).contains(line);
        }

        assertThat(Modifier.isPublic(DspmQueryStringTurboFilter.class.getConstructor().getModifiers())).isTrue();

        LoggerContext configured = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(configured);
        configurator.doConfigure(new ByteArrayInputStream(
                ("<configuration>" + line + "</configuration>").getBytes(StandardCharsets.UTF_8)));
        assertThat(configured.getTurboFilterList()).hasSize(1)
                .first().isInstanceOf(DspmQueryStringTurboFilter.class);
        configured.stop();
    }
}
