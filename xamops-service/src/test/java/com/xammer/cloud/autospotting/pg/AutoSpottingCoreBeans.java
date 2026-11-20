package com.xammer.cloud.autospotting.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

import com.xammer.cloud.repository.autospotting.LeaseRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupLifecycleRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationStepRepository;

/**
 * A JPA-enabled Spring context over the {@link PgHarness} DataSource for the Phase 06 core ITs
 * ({@code GroupLifecycleIT}, {@code OperationStoreIT}). It wires the real lifecycle and operation
 * services exactly as production does — fenced transactions, guarded native writes, the synchronous
 * audit writer — so the {@code @Transactional} semantics and the PostgreSQL triggers are the real
 * ones. Only the AutoSpotting spine repositories and domain are scanned; no application beans or
 * cloud clients are loaded.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class})
@EntityScan(basePackages = "com.xammer.cloud.domain.autospotting")
@EnableJpaRepositories(basePackages = "com.xammer.cloud.repository.autospotting")
class AutoSpottingCoreBeans {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    SimpleMeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics autoSpottingMetrics(SimpleMeterRegistry meters) {
        return new com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics(meters);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    AuditRedactor auditRedactor(ObjectMapper objectMapper) {
        return new AuditRedactor(objectMapper);
    }

    @Bean
    LeaseRepository leaseRepository(DataSource dataSource) {
        return new LeaseRepository(dataSource);
    }

    @Bean
    SpotGroupLifecycleRepository spotGroupLifecycleRepository(DataSource dataSource) {
        return new SpotGroupLifecycleRepository(dataSource);
    }

    @Bean
    LeaseRegistry leaseRegistry() {
        return new LeaseRegistry();
    }

    @Bean
    LeaseProperties leaseProperties() {
        return new LeaseProperties(120, 30, 600, 60, 1800, 60, 300, 10);
    }

    @Bean
    LeaseService leaseService(LeaseRepository repo, LeaseRegistry registry, LeaseProperties props,
                              com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics metrics) {
        return new LeaseService(repo, registry, props, metrics);
    }

    @Bean
    FencedTx fencedTx(LeaseRepository repo,
                      com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics metrics) {
        return new FencedTx(repo, metrics);
    }

    @Bean
    AutoSpottingAuditWriter auditWriter(com.xammer.cloud.repository.autospotting.AutoSpottingAuditRepository repo,
                                        AuditRedactor redactor) {
        return new AutoSpottingAuditWriter(repo, redactor);
    }

    @Bean
    com.xammer.cloud.repository.autospotting.NotificationStore notificationStore(DataSource dataSource) {
        return new com.xammer.cloud.repository.autospotting.NotificationStore(dataSource);
    }

    @Bean
    com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService notificationService(
            com.xammer.cloud.repository.autospotting.NotificationStore store, AuditRedactor redactor) {
        return new com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService(store, redactor);
    }

    @Bean
    GroupLifecycleService groupLifecycleService(FencedTx fencedTx, SpotGroupLifecycleRepository lifecycle,
                                                AutoSpottingAuditWriter auditWriter,
            com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService notifications) {
        return new GroupLifecycleService(fencedTx, lifecycle, auditWriter, notifications);
    }

    @Bean
    OperationStore operationStore(SpotOperationRepository ops, SpotOperationStepRepository steps,
                                  FencedTx fencedTx, AuditRedactor redactor) {
        return new OperationStore(ops, steps, fencedTx, redactor);
    }

    @Bean(name = "beanJdbc")
    JdbcTemplate jdbc(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
