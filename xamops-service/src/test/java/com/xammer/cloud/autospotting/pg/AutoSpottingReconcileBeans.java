package com.xammer.cloud.autospotting.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingAllowlistEntryRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingAuditRepository;
import com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository;
import com.xammer.cloud.repository.autospotting.DiscoveryStore;
import com.xammer.cloud.repository.autospotting.LeaseRepository;
import com.xammer.cloud.repository.autospotting.ReconcilerStore;
import com.xammer.cloud.repository.autospotting.SpotGroupLifecycleRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupSnapshotRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.discovery.DiscoveryJob;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.reconcile.DriftClassifier;
import com.xammer.cloud.service.autospotting.core.reconcile.GoneDetector;
import com.xammer.cloud.service.autospotting.core.reconcile.HealthPass;
import com.xammer.cloud.service.autospotting.core.reconcile.SpotReconciler;
import com.xammer.cloud.service.autospotting.core.reconcile.SweepOrder;
import com.xammer.cloud.service.autospotting.core.reconcile.SwitchPauseApplier;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.spi.DiscoveryRegistry;
import com.xammer.cloud.service.autospotting.spi.GroupDiscovery;
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
import java.time.Duration;
import java.util.List;

/**
 * A JPA-enabled Spring context over a {@link PgHarness} DataSource for the Phase 08 reconciler and
 * discovery ITs ({@code ReconcilerIT}, {@code DiscoveryJobIT}). It wires the real
 * {@link SpotReconciler}, {@link DiscoveryJob} and their collaborators over real fenced transactions
 * and guarded native writes.
 *
 * <p>The IT supplies the test {@link SpotProvider} and {@link GroupDiscovery} by setting the static
 * holders {@link #provider} and {@link #discovery} before {@code refresh()}; the registries pick them
 * up. When a holder is null the registry is empty, which is production's dark state.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class})
@EntityScan(basePackages = "com.xammer.cloud.domain.autospotting")
@EnableJpaRepositories(basePackages = "com.xammer.cloud.repository.autospotting")
class AutoSpottingReconcileBeans {

    /** Set by the IT before refresh; null leaves the registry empty (dark). */
    static SpotProvider provider;
    static GroupDiscovery discovery;

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
    DiscoveryStore discoveryStore(DataSource dataSource) {
        return new DiscoveryStore(dataSource);
    }

    @Bean
    ReconcilerStore reconcilerStore(DataSource dataSource) {
        return new ReconcilerStore(dataSource);
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
    AutoSpottingAuditWriter auditWriter(AutoSpottingAuditRepository repo, AuditRedactor redactor) {
        return new AutoSpottingAuditWriter(repo, redactor);
    }

    @Bean
    GroupLifecycleService groupLifecycleService(FencedTx fencedTx, SpotGroupLifecycleRepository lifecycle,
                                                AutoSpottingAuditWriter auditWriter) {
        return new GroupLifecycleService(fencedTx, lifecycle, auditWriter);
    }

    @Bean
    OperationStore operationStore(SpotOperationRepository ops,
                                  com.xammer.cloud.repository.autospotting.SpotOperationStepRepository steps,
                                  FencedTx fencedTx, AuditRedactor redactor) {
        return new OperationStore(ops, steps, fencedTx, redactor);
    }

    @Bean
    PreemptionService preemptionService(SpotOperationRepository ops, SpotGroupLifecycleRepository lifecycle) {
        return new PreemptionService(ops, lifecycle);
    }

    @Bean
    SwitchService switchService(AutoSpottingSwitchRepository switches,
                                AutoSpottingAllowlistEntryRepository allowlist) {
        return new SwitchService(switches, allowlist, 0); // 0s cache: always fresh in the IT
    }

    @Bean
    AutoSpottingSwitchProperties switchProperties() {
        return new AutoSpottingSwitchProperties(true, true, true, true, false, false, false, false, false, false);
    }

    @Bean
    SwitchPauseApplier switchPauseApplier(AutoSpottingSwitchRepository switches, GroupLifecycleService lifecycle) {
        return new SwitchPauseApplier(switches, lifecycle);
    }

    @Bean
    DriftClassifier driftClassifier() {
        return new DriftClassifier();
    }

    @Bean
    GoneDetector goneDetector() {
        return new GoneDetector();
    }

    @Bean
    SweepOrder sweepOrder(ReconcilerStore store) {
        return new SweepOrder(store, 200);
    }

    @Bean
    HealthPass healthPass(SpotOperationRepository ops, PreemptionService preemption, ReconcilerStore store,
                          com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics metrics) {
        return new HealthPass(ops, preemption, store, metrics);
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor spotJobsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("spot-jobs-");
        executor.initialize();
        return executor;
    }

    @Bean
    JobRunner jobRunner(ThreadPoolTaskExecutor spotJobsExecutor, LeaseService leaseService,
                        LeaseProperties leaseProperties, com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics metrics) {
        return new JobRunner(spotJobsExecutor, leaseService, leaseProperties, metrics, true);
    }

    @Bean
    ProviderRegistry providerRegistry() {
        return new ProviderRegistry(provider == null ? List.of() : List.of(provider));
    }

    @Bean
    DiscoveryRegistry discoveryRegistry() {
        return new DiscoveryRegistry(discovery == null ? List.of() : List.of(discovery));
    }

    @Bean
    SpotReconciler spotReconciler(ProviderRegistry providers, SpotGroupRepository groups,
                                  SpotOperationRepository operations, SweepOrder sweepOrder,
                                  ReconcilerStore reconcilerStore, SwitchService switchService,
                                  SwitchPauseApplier switchPauseApplier, DriftClassifier driftClassifier,
                                  GoneDetector goneDetector, HealthPass healthPass, GroupLifecycleService lifecycle,
                                  LeaseService leaseService, LeaseRegistry registry, JobRunner jobRunner,
                                  LeaseProperties leaseProperties, com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics metrics) {
        return new SpotReconciler(providers, groups, operations, sweepOrder, reconcilerStore, switchService,
                switchPauseApplier, driftClassifier, goneDetector, healthPass, lifecycle, leaseService, registry,
                jobRunner, leaseProperties, metrics, Duration.ofHours(2));
    }

    /** Set by the IT to control which accounts a discovery run sees; defaults to a Mockito mock. */
    static CloudAccountRepository cloudAccountRepository = org.mockito.Mockito.mock(CloudAccountRepository.class);

    @Bean
    CloudAccountRepository cloudAccountRepository() {
        return cloudAccountRepository;
    }

    @Bean
    DiscoveryJob discoveryJob(DiscoveryRegistry discoveries, CloudAccountRepository cloudAccounts,
                              DiscoveryStore discoveryStore, SpotGroupSnapshotRepository snapshots,
                              AutoSpottingAuditWriter auditWriter, JobRunner jobRunner,
                              PlatformTransactionManager transactionManager) {
        return new DiscoveryJob(discoveries, cloudAccounts, discoveryStore, snapshots, auditWriter, jobRunner,
                transactionManager);
    }

    @Bean(name = "beanJdbc")
    JdbcTemplate jdbc(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
