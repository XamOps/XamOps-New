package com.xammer.cloud.autospotting.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.AutoSpottingAudit;
import com.xammer.cloud.repository.autospotting.AutoSpottingAuditRepository;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdcTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AutoSpottingAuditWriter} unit tests (plan-phase-05.md task 5.3, design-core.md §8,
 * AC-SPN-10, FR-SPN-11). The writer is wired as a transaction-proxied bean in a tiny Spring context
 * with a no-op {@link PlatformTransactionManager}, so {@code @Transactional(MANDATORY)} is enforced:
 * a call with no active transaction throws, and a call inside a {@link TransactionTemplate} writes a
 * row. An impersonated actor records both identities; the request id comes from MDC.
 */
class AutoSpottingAuditWriterTest {

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean
        AutoSpottingAuditRepository repository() {
            return mock(AutoSpottingAuditRepository.class);
        }

        @Bean
        AuditRedactor redactor() {
            return new AuditRedactor(new ObjectMapper());
        }

        @Bean
        AutoSpottingAuditWriter writer(AutoSpottingAuditRepository r, AuditRedactor red) {
            return new AutoSpottingAuditWriter(r, red);
        }

        @Bean
        javax.sql.DataSource dataSource() {
            // A throwaway in-memory H2 DataSource: it makes the transaction genuinely active so
            // @Transactional(MANDATORY) passes inside a TransactionTemplate and throws outside one.
            // The repository is mocked, so nothing is actually read or written to H2.
            return new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:audit-writer-test;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(javax.sql.DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static AutoSpottingAuditWriter writer;
    private static AutoSpottingAuditRepository repository;
    private static TransactionTemplate tx;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigApplicationContext(Ctx.class);
        writer = context.getBean(AutoSpottingAuditWriter.class);
        repository = context.getBean(AutoSpottingAuditRepository.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @org.junit.jupiter.api.BeforeEach
    void resetRepository() {
        org.mockito.Mockito.reset(repository);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void writeWithoutATransactionThrows() {
        AuditRecord record = AuditRecord
                .builder("STATE_TRANSITION", Actor.system(), "ENABLE", "OK")
                .build();
        assertThatThrownBy(() -> writer.write(record))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void writeInATransactionPersistsTheRow() {
        when(repository.save(any(AutoSpottingAudit.class))).thenAnswer(i -> i.getArgument(0));
        AuditRecord record = AuditRecord
                .builder("STATE_TRANSITION", Actor.user(42L, "ROLE_CLIENT_OWNER"), "ENABLE", "OK")
                .cloudAccountId(7L)
                .build();

        tx.executeWithoutResult(s -> writer.write(record));

        ArgumentCaptor<AutoSpottingAudit> captor = ArgumentCaptor.forClass(AutoSpottingAudit.class);
        verify(repository).save(captor.capture());
        AutoSpottingAudit row = captor.getValue();
        assertThat(row.getEventType()).isEqualTo("STATE_TRANSITION");
        assertThat(row.getActorType()).isEqualTo("USER");
        assertThat(row.getActorUserId()).isEqualTo(42L);
        assertThat(row.getImpersonatorUserId()).isNull();
        assertThat(row.getOccurredAt()).isNotNull();
    }

    @Test
    void impersonatedActorRecordsBothIdentities() {
        when(repository.save(any(AutoSpottingAudit.class))).thenAnswer(i -> i.getArgument(0));
        AuditRecord record = AuditRecord
                .builder("STATE_TRANSITION",
                        Actor.impersonated(99L, "ROLE_CLIENT_OWNER", /* operator */ 5L),
                        "REVERT", "OK")
                .build();

        tx.executeWithoutResult(s -> writer.write(record));

        ArgumentCaptor<AutoSpottingAudit> captor = ArgumentCaptor.forClass(AutoSpottingAudit.class);
        verify(repository).save(captor.capture());
        AutoSpottingAudit row = captor.getValue();
        assertThat(row.getActorUserId()).as("impersonated identity").isEqualTo(99L);
        assertThat(row.getImpersonatorUserId()).as("the operator behind the session").isEqualTo(5L);
    }

    @Test
    void requestIdComesFromMdc() {
        when(repository.save(any(AutoSpottingAudit.class))).thenAnswer(i -> i.getArgument(0));
        MDC.put(AutoSpottingMdcTaskDecorator.REQUEST_ID, "req-abc-123");
        AuditRecord record = AuditRecord
                .builder("CLOUD_CALL", Actor.system(), "ENABLE", "OK")
                .build();

        tx.executeWithoutResult(s -> writer.write(record));

        ArgumentCaptor<AutoSpottingAudit> captor = ArgumentCaptor.forClass(AutoSpottingAudit.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRequestId()).isEqualTo("req-abc-123");
    }

    @Test
    void beforeAndAfterAreRedacted() {
        when(repository.save(any(AutoSpottingAudit.class))).thenAnswer(i -> i.getArgument(0));
        AuditRecord record = AuditRecord
                .builder("CLOUD_CALL", Actor.system(), "ENABLE", "OK")
                .beforeState("{\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\"}")
                .afterState("{\"note\":\"ok\"}")
                .build();

        tx.executeWithoutResult(s -> writer.write(record));

        ArgumentCaptor<AutoSpottingAudit> captor = ArgumentCaptor.forClass(AutoSpottingAudit.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getBeforeState()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(captor.getValue().getBeforeState()).contains(AuditRedactor.MASK);
    }
}
