package com.xammer.cloud.autospotting.obs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-OBS-04: a runner-style step logged inside an {@link AutoSpottingMdc} scope emits one event that
 * carries all six MDC keys ({@code operationId}, {@code groupId}, {@code provider},
 * {@code cloudAccountId}, {@code requestId}, {@code job}); the scope is restored on close so the keys
 * do not leak to the next task, and a logger outside the AutoSpotting package carries none of them
 * (design-ux-ops.md §8.1, plan-phase-10.md task 10.4).
 *
 * <p>The JSON rendering itself is produced by the {@code logback-spring.xml} ECS encoder at runtime;
 * this test pins the data contract (which keys reach a log event's MDC) with a {@link ListAppender},
 * which is deterministic and needs no running Spring context.
 */
class AutoSpottingMdcTest {

    private final Logger autospottingLogger =
            (Logger) LoggerFactory.getLogger("com.xammer.cloud.service.autospotting.core.ops.OperationRunner");
    private final Logger otherLogger =
            (Logger) LoggerFactory.getLogger("com.xammer.cloud.service.SomeOtherService");

    private ListAppender<ILoggingEvent> autospottingAppender;
    private ListAppender<ILoggingEvent> otherAppender;

    @AfterEach
    void tearDown() {
        if (autospottingAppender != null) {
            autospottingLogger.detachAppender(autospottingAppender);
        }
        if (otherAppender != null) {
            otherLogger.detachAppender(otherAppender);
        }
        MDC.clear();
    }

    @Test
    void aStepLoggedInsideTheScopeCarriesAllSixMdcKeys() {
        autospottingAppender = attach(autospottingLogger);

        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder()
                .operationId("a91e")
                .groupId("6f1c")
                .provider("aws")
                .cloudAccountId(42L)
                .requestId("7f3c")
                .job("dispatcher")
                .open()) {
            autospottingLogger.info("state transition QUEUED -> RUNNING");
        }

        assertThat(autospottingAppender.list).hasSize(1);
        Map<String, String> mdc = autospottingAppender.list.get(0).getMDCPropertyMap();
        assertThat(mdc)
                .containsEntry(AutoSpottingMdc.OPERATION_ID, "a91e")
                .containsEntry(AutoSpottingMdc.GROUP_ID, "6f1c")
                .containsEntry(AutoSpottingMdc.PROVIDER, "aws")
                .containsEntry(AutoSpottingMdc.CLOUD_ACCOUNT_ID, "42")
                .containsEntry(AutoSpottingMdc.REQUEST_ID, "7f3c")
                .containsEntry(AutoSpottingMdc.JOB, "dispatcher");
    }

    @Test
    void theScopeIsRestoredOnCloseSoKeysDoNotLeak() {
        autospottingAppender = attach(autospottingLogger);

        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().operationId("op-1").open()) {
            assertThat(MDC.get(AutoSpottingMdc.OPERATION_ID)).isEqualTo("op-1");
        }
        // After the scope closes the key is gone (no leak into the next pooled task).
        assertThat(MDC.get(AutoSpottingMdc.OPERATION_ID)).isNull();

        autospottingLogger.info("after scope");
        assertThat(autospottingAppender.list).hasSize(1);
        assertThat(autospottingAppender.list.get(0).getMDCPropertyMap())
                .doesNotContainKey(AutoSpottingMdc.OPERATION_ID);
    }

    @Test
    void nonAutoSpottingLoggerDoesNotCarryTheAutoSpottingKeys() {
        otherAppender = attach(otherLogger);

        // No AutoSpotting scope is open, so a line from another service has none of the keys.
        otherLogger.info("unrelated platform log line");

        assertThat(otherAppender.list).hasSize(1);
        Map<String, String> mdc = otherAppender.list.get(0).getMDCPropertyMap();
        for (String key : AutoSpottingMdc.KEYS) {
            assertThat(mdc).doesNotContainKey(key);
        }
    }

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
