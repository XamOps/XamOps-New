package com.xammer.cloud.autospotting.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.autospotting.fake.FakeSpotProvider;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.autospotting.LeaseRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupLifecycleRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationStepRepository;
import com.xammer.cloud.service.autospotting.core.SpotOpsLifecycle;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.ops.OperationDispatcher;
import com.xammer.cloud.service.autospotting.core.ops.OperationRunner;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.ops.StepExecutor;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;

/**
 * A JPA-enabled Spring context over the {@link PgHarness} DataSource for the Phase 07 operation-runner
 * ITs ({@code OperationRunnerIT}, {@code CrashResumeIT}, {@code PreemptionIT}). It wires the real
 * runner, dispatcher, step executor and preemption service exactly as production does, over the real
 * fenced transactions and guarded native writes, and registers the test-scope {@link FakeSpotProvider}
 * in the {@link ProviderRegistry} so the core can drive an operation end to end with no cloud call.
 *
 * <p>The {@code spotOpsExecutor} is a small real {@link ThreadPoolTaskExecutor} ({@code pool} size,
 * SynchronousQueue, AbortPolicy) so the ITs exercise the dispatcher's free-slot claim and the
 * thread-releasing WAITING behavior (design-core.md §4.1). No {@code @Scheduled} fires: the ITs call
 * {@link OperationDispatcher#poll()} explicitly to control timing.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class})
@EntityScan(basePackages = "com.xammer.cloud.domain.autospotting")
@EnableJpaRepositories(basePackages = "com.xammer.cloud.repository.autospotting")
class AutoSpottingOpsBeans {

    /** The ops-executor pool size the Phase 07 ITs use (1 proves the thread-releasing WAITING path). */
    static final int OPS_POOL_SIZE = 1;

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
    FencedTx fencedTx(LeaseRepository repo, com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics metrics) {
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

    @Bean
    StepExecutor stepExecutor(OperationStore store, SpotOperationStepRepository steps) {
        return new StepExecutor(store, steps);
    }

    /** The AWS {@link FakeSpotProvider}, registered in the {@link ProviderRegistry}. */
    @Bean
    SpotProvider fakeAwsProvider() {
        return new FakeSpotProvider(Provider.AWS);
    }

    @Bean
    ProviderRegistry providerRegistry(List<SpotProvider> providers) {
        return new ProviderRegistry(providers);
    }

    @Bean
    SwitchService switchService(com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository switches,
                                com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository allowlist) {
        return new SwitchService(switches, allowlist, 15);
    }

    @Bean
    SpotOpsLifecycle spotOpsLifecycle(LeaseRegistry registry, LeaseService leaseService) {
        SpotOpsLifecycle lifecycle = new SpotOpsLifecycle(registry, leaseService,
                java.time.Duration.ofSeconds(20));
        lifecycle.start();
        return lifecycle;
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor spotOpsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(OPS_POOL_SIZE);
        executor.setMaxPoolSize(OPS_POOL_SIZE);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("spot-ops-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    OperationRunner operationRunner(SpotOperationRepository ops, SpotGroupRepository groups, OperationStore store,
                                    StepExecutor stepExecutor, GroupLifecycleService lifecycle, FencedTx fencedTx,
                                    AutoSpottingAuditWriter auditWriter, SwitchService switchService,
                                    SpotOpsLifecycle opsLifecycle, ProviderRegistry providers,
                                    com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics metrics) {
        return new OperationRunner(ops, groups, store, stepExecutor, lifecycle, fencedTx, auditWriter,
                switchService, opsLifecycle, providers, metrics);
    }

    @Bean
    OperationDispatcher operationDispatcher(SpotOperationRepository ops, OperationStore store, OperationRunner runner,
                                            LeaseService leaseService, LeaseRegistry registry,
                                            ThreadPoolTaskExecutor spotOpsExecutor, SpotOpsLifecycle opsLifecycle,
                                            com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics metrics) {
        return new OperationDispatcher(ops, store, runner, leaseService, registry, spotOpsExecutor,
                opsLifecycle, metrics);
    }

    @Bean
    com.xammer.cloud.service.autospotting.core.FallbackProperties fallbackProperties() {
        return com.xammer.cloud.service.autospotting.core.FallbackProperties.defaults();
    }
    @Bean
    com.xammer.cloud.service.autospotting.core.reconcile.FallbackEnqueuer fallbackEnqueuer(
            SpotOperationRepository ops, PreemptionService preemption, OperationStore store,
            com.xammer.cloud.service.autospotting.core.FallbackProperties props, AutoSpottingAuditWriter audit,
            com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics metrics, SwitchService switches) {
        return new com.xammer.cloud.service.autospotting.core.reconcile.FallbackEnqueuer(ops, preemption, store, props,
                audit, metrics, switches);
    }
    @Bean
    PreemptionService preemptionService(SpotOperationRepository ops, SpotGroupLifecycleRepository lifecycle) {
        return new PreemptionService(ops, lifecycle);
    }

    @Bean(destroyMethod = "shutdown")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler spotTaskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("spot-sched-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    com.xammer.cloud.service.autospotting.core.ops.StartupRecovery startupRecovery(
            SpotOperationRepository ops, SpotGroupRepository groups, OperationStore store, OperationRunner runner,
            LeaseService leaseService, LeaseProperties leaseProperties, GroupLifecycleService lifecycle,
            FencedTx fencedTx, AutoSpottingAuditWriter auditWriter, ProviderRegistry providers,
            org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler spotTaskScheduler) {
        return new com.xammer.cloud.service.autospotting.core.ops.StartupRecovery(ops, groups, store, runner,
                leaseService, leaseProperties, lifecycle, fencedTx, auditWriter, providers, spotTaskScheduler,
                java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(30));
    }

    @Bean(name = "beanJdbc")
    JdbcTemplate jdbc(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
