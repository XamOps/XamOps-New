package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.repository.autospotting.LeaseRepository;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lease.LeaseUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lease and fencing behaviour on real PostgreSQL (plan-phase-04.md task 4.1, design-core.md §3.3,
 * §3.4, design-ux-ops.md §9.4). Runs against {@link PgHarness#emptySchema()} (Testcontainers, random
 * port), which applies only the AutoSpotting migrations, so the spine tables and the
 * {@code clock_timestamp()} comparisons are the real ones. {@code @Tag("containers")} keeps it out of
 * the default {@code mvn test}.
 *
 * <p>Each test builds a tiny Spring context ({@link LeaseBeans}) over the harness DataSource so the
 * {@code @Transactional} semantics of {@link LeaseRepository} and {@link FencedTx} are real: the
 * single-statement {@code REQUIRES_NEW} acquires/renews/releases, and the {@code FOR UPDATE} fence as
 * the first statement of the caller's transaction.
 */
@Tag("containers")
class LeaseConcurrencyIT {

    @EnableTransactionManagement
    @Configuration
    static class LeaseBeans {
        @Bean
        PlatformTransactionManager txManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        LeaseRepository leaseRepository(DataSource dataSource) {
            return new LeaseRepository(dataSource);
        }

        @Bean
        LeaseRegistry leaseRegistry() {
            return new LeaseRegistry();
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LeaseProperties leaseProperties() {
            return new LeaseProperties(120, 30, 600, 60, 1800, 60, 300, 10);
        }

        @Bean
        LeaseService leaseService(LeaseRepository repo, LeaseRegistry registry, LeaseProperties props,
                                  SimpleMeterRegistry meters) {
            return new LeaseService(repo, registry, props, meters);
        }

        @Bean
        FencedTx fencedTx(LeaseRepository repo, SimpleMeterRegistry meters) {
            return new FencedTx(repo, meters);
        }

        @Bean(name = "beanJdbc")
        JdbcTemplate jdbc(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    private AnnotationConfigApplicationContext contextFor(PgHarness.Database db) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        DataSource dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        ctx.getBeanFactory().registerSingleton("dataSource", dataSource);
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("lease-it", Map.of()));
        ctx.register(LeaseBeans.class);
        ctx.refresh();
        return ctx;
    }

    private static void insertGroup(JdbcTemplate jdbc, UUID id) {
        Instant now = Instant.now();
        PgHarness.insertRow(jdbc, "spot_group", Map.of(
                "id", id,
                "provider", "AWS",
                "cloud_account_id", 1L,
                "client_id", 1L,
                "group_key", "us-east-1/asg-" + id.toString().substring(0, 8),
                "state", "ACTIVE",
                "state_changed_at", java.sql.Timestamp.from(now),
                "created_by", "test",
                "created_at", java.sql.Timestamp.from(now),
                "updated_at", java.sql.Timestamp.from(now)));
    }

    // ── Lease race: 8 threads, one winner, generation increments once (ux §9.4) ─

    @Test
    void eightThreadsRaceForOneGroupLease_exactlyOneWins_generationIncrementsOnce() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            LeaseService service = ctx.getBean(LeaseService.class);
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            UUID group = UUID.randomUUID();
            insertGroup(jdbc, group);

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Optional<LeaseHandle>> results = new CopyOnWriteArrayList<>();
            try {
                List<Future<?>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit((Callable<Void>) () -> {
                        ready.countDown();
                        go.await(5, TimeUnit.SECONDS);
                        results.add(service.acquireGroup(group, LeaseService.Component.DISPATCHER));
                        return null;
                    }));
                }
                ready.await(5, TimeUnit.SECONDS);
                go.countDown();
                for (Future<?> f : futures) {
                    f.get(10, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            long winners = results.stream().filter(Optional::isPresent).count();
            assertThat(winners).isEqualTo(1);
            // generation started at 0, exactly one acquire bumped it to 1.
            Long generation = jdbc.queryForObject("SELECT lease_generation FROM spot_group WHERE id = ?",
                    Long.class, group);
            assertThat(generation).isEqualTo(1L);
        }
    }

    // ── Same-instance contention: dispatcher + reconciler tokens, one winner ────

    @Test
    void dispatcherAndReconcilerOfOneInstanceContend_oneWins_generationIncrementsOnce() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            LeaseService service = ctx.getBean(LeaseService.class);
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            UUID group = UUID.randomUUID();
            insertGroup(jdbc, group);

            Optional<LeaseHandle> dispatcher = service.acquireGroup(group, LeaseService.Component.DISPATCHER);
            Optional<LeaseHandle> reconciler = service.acquireGroup(group, LeaseService.Component.RECONCILER);

            // Acquire is not re-entrant: the second same-instance component loses, as another instance would.
            assertThat(dispatcher).isPresent();
            assertThat(reconciler).isEmpty();
            Long generation = jdbc.queryForObject("SELECT lease_generation FROM spot_group WHERE id = ?",
                    Long.class, group);
            assertThat(generation).isEqualTo(1L);
        }
    }

    // ── Fencing: A acquires, forced expiry, B acquires, A's fenced write throws (AC-SPN-03) ─

    @Test
    void forcedExpiryThenSecondHolder_firstHoldersFencedWriteThrowsLost_nothingCommits() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            LeaseService service = ctx.getBean(LeaseService.class);
            FencedTx fencedTx = ctx.getBean(FencedTx.class);
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            UUID group = UUID.randomUUID();
            insertGroup(jdbc, group);

            LeaseHandle a = service.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();
            // Force A's lease to be expired so B can take it.
            jdbc.update("UPDATE spot_group SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                    group);
            LeaseHandle b = service.acquireGroup(group, LeaseService.Component.RECONCILER).orElseThrow();
            assertThat(b.generation()).isGreaterThan(a.generation());

            // A's fenced write: the fence misses (B holds now), so the transaction throws and rolls back.
            assertThatThrownBy(() -> fencedTx.run(a, () ->
                    jdbc.update("UPDATE spot_group SET last_error_code = 'A_WROTE' WHERE id = ?", group)))
                    .isInstanceOf(LeaseLostException.class);

            String errorCode = jdbc.queryForObject("SELECT last_error_code FROM spot_group WHERE id = ?",
                    String.class, group);
            assertThat(errorCode).isNull(); // nothing committed
            assertThat(a.isLost()).isTrue();

            // B's fenced write commits.
            fencedTx.run(b, () ->
                    jdbc.update("UPDATE spot_group SET last_error_code = 'B_WROTE' WHERE id = ?", group));
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM spot_group WHERE id = ?", String.class, group))
                    .isEqualTo("B_WROTE");
        }
    }

    // ── Lease store down mid-test → LeaseUnavailableException, meter increments (AC-SPN-02) ─

    @Test
    void stoppingTheStoreMidTest_acquireThrowsUnavailable_andMeterIncrements() throws Exception {
        PgHarness.Database db = PgHarness.emptySchema();
        AnnotationConfigApplicationContext ctx = contextFor(db);
        try {
            LeaseService service = ctx.getBean(LeaseService.class);
            SimpleMeterRegistry meters = ctx.getBean(SimpleMeterRegistry.class);
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            UUID group = UUID.randomUUID();
            insertGroup(jdbc, group);
            // Prove it works before the store goes away.
            assertThat(service.acquireGroup(group, LeaseService.Component.DISPATCHER)).isPresent();

            // "Stop the container": drop the database out from under the pool.
            db.close();

            assertThatThrownBy(() -> service.acquireGroup(UUID.randomUUID(), LeaseService.Component.API))
                    .isInstanceOf(LeaseUnavailableException.class);
            assertThat(meters.find("autospotting.lease.failures").tag("reason", "store_error").counter().count())
                    .isGreaterThanOrEqualTo(1.0);
        } finally {
            ctx.close();
            db.close(); // idempotent DROP ... IF EXISTS
        }
    }

    // ── Job-lease minimum hold prevents a second run within 5 min (§3.5) ────────

    @Test
    void jobLeaseMinimumHoldPreventsASecondRunWithinTheHold() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            LeaseService service = ctx.getBean(LeaseService.class);
            LeaseRepository repo = ctx.getBean(LeaseRepository.class);

            // Instance A runs the daily job and releases with a 5-minute minimum hold and a run key.
            LeaseHandle a = service.acquireJob("discovery:aws",
                    Duration.ofMinutes(30), Duration.ofSeconds(60)).orElseThrow();
            service.releaseJob(a, Duration.ofMinutes(5), "2026-10-06");

            // Instance B triggers immediately: the lease is still held by the minimum hold, so no acquire.
            Optional<LeaseHandle> b = service.acquireJob("discovery:aws",
                    Duration.ofMinutes(30), Duration.ofSeconds(60));
            assertThat(b).isEmpty();
            assertThat(repo.lastRunKey("discovery:aws")).isEqualTo("2026-10-06");

            // After the hold expires (force it), another holder can acquire again.
            ((JdbcTemplate) ctx.getBean("beanJdbc")).update(
                    "UPDATE autospotting_job_lease SET expires_at = clock_timestamp() - INTERVAL '1 second'"
                            + " WHERE name = ?", "discovery:aws");
            assertThat(service.acquireJob("discovery:aws", Duration.ofMinutes(30), Duration.ofSeconds(60)))
                    .isPresent();
        }
    }
}
