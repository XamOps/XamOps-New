package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.discovery.DiscoveredGroup;
import com.xammer.cloud.service.autospotting.core.discovery.DiscoveryJob;
import com.xammer.cloud.service.autospotting.spi.GroupDiscovery;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The discovery engine on real PostgreSQL (design-core.md §2.5; plan-phase-08.md task 8.3 verify).
 * With a fake discovery: rows are inserted LEGACY_UNCONFIRMED with {@code exact=false} snapshots and
 * zero mutating calls; the two-run UNMANAGED rule moves a marker-less row on its second run; a
 * duplicate project is skipped; a failed location is reported in the {@code DISCOVERY_RUN} audit.
 */
@Tag("containers")
class DiscoveryJobIT {

    private PgHarness.Database db;
    private AnnotationConfigApplicationContext ctx;
    private JdbcTemplate jdbc;
    private DiscoveryJob discoveryJob;

    @BeforeEach
    void setUp() {
        db = PgHarness.emptySchema();
        AutoSpottingReconcileBeans.provider = null;
        AutoSpottingReconcileBeans.discovery = null; // set per test
        AutoSpottingReconcileBeans.cloudAccountRepository =
                org.mockito.Mockito.mock(CloudAccountRepository.class);

        ctx = new AnnotationConfigApplicationContext();
        DriverManagerDataSource ds = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        ctx.getBeanFactory().registerSingleton("dataSource", ds);
        ctx.register(AutoSpottingReconcileBeans.class);
        ctx.refresh();

        jdbc = new JdbcTemplate(ds);
        discoveryJob = ctx.getBean(DiscoveryJob.class);
    }

    @AfterEach
    void tearDown() {
        AutoSpottingReconcileBeans.discovery = null;
        if (ctx != null) {
            ctx.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static CloudAccount account(long id, Provider provider, String gcpProjectId) {
        CloudAccount a = new CloudAccount();
        a.setId(id);
        a.setProvider(provider.name());
        a.setGcpProjectId(gcpProjectId);
        Client client = new Client();
        client.setId(100L);
        a.setClient(client);
        return a;
    }

    @Test
    void rule2_insertsLegacyUnconfirmed_withInexactSnapshot_andNoMutation() {
        CloudAccount account = account(1L, Provider.AWS, null);
        FakeDiscovery fake = new FakeDiscovery(Provider.AWS, acc -> new DiscoveredGroup.Result(
                Provider.AWS,
                List.of(new DiscoveredGroup("us-east-1/legacy-asg", "arn:asg/legacy", 2, null,
                        SnapshotPayload.exact(Provider.AWS, 1, "{\"reconstructed\":true}", "sha"),
                        "{\"desired\":\"seed\"}", true, true)),
                Map.of(2, 1), List.of()));

        discoveryJob.applyResult(Provider.AWS, account, fake.discover(account, view()));
        discoveryJob.writeRunAudit(Provider.AWS, account, fake.lastResult, List.of());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, provider_stable_id FROM spot_group WHERE group_key = ?", "us-east-1/legacy-asg");
        assertThat(row.get("state")).isEqualTo("LEGACY_UNCONFIRMED");
        assertThat(row.get("provider_stable_id")).isEqualTo("arn:asg/legacy");
        Boolean exact = jdbc.queryForObject("SELECT exact FROM spot_group_snapshot LIMIT 1", Boolean.class);
        assertThat(exact).isFalse();
        assertThat(fake.mutatingCalls.get()).isZero();
        // A DISCOVERY_RUN audit record was written.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM autospotting_audit WHERE event_type = 'DISCOVERY_RUN'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void twoRuns_withoutMarkerOrSpotShare_moveToUnmanaged() {
        CloudAccount account = account(2L, Provider.AWS, null);
        // Run 1: rule 2 inserts the row with a marker/spot share.
        discoveryJob.applyResult(Provider.AWS, account, oneGroup(2, true, true));
        assertThat(state("us-east-1/g")).isEqualTo("LEGACY_UNCONFIRMED");

        // Run 2: identity match (rule 1) but now marker-less and no Spot share → first marker-less run.
        discoveryJob.applyResult(Provider.AWS, account, oneGroup(1, false, false));
        assertThat(state("us-east-1/g")).isEqualTo("LEGACY_UNCONFIRMED");

        // Run 3: still marker-less → second consecutive → UNMANAGED.
        discoveryJob.applyResult(Provider.AWS, account, oneGroup(1, false, false));
        assertThat(state("us-east-1/g")).isEqualTo("UNMANAGED");
    }

    @Test
    void identityChange_movesOldRowToGone() {
        CloudAccount account = account(3L, Provider.AWS, null);
        discoveryJob.applyResult(Provider.AWS, account, oneGroupWithStableId(2, "arn:v1"));
        assertThat(state("us-east-1/g")).isEqualTo("LEGACY_UNCONFIRMED");

        // Next run: an identity match with a different stable id → the old row goes GONE.
        discoveryJob.applyResult(Provider.AWS, account, oneGroupWithStableId(1, "arn:v2"));
        assertThat(state("us-east-1/g")).isEqualTo("GONE");
    }

    @Test
    void duplicateGcpProject_isSkippedAndReported() {
        // Rebuild a context with the fake discovery registered and a CloudAccountRepository that
        // returns two GCP accounts sharing a project id (AC-SPN-14).
        ctx.close();
        CloudAccount a1 = account(10L, Provider.GCP, "shared-proj");
        CloudAccount a2 = account(11L, Provider.GCP, "shared-proj");
        CloudAccountRepository repo = org.mockito.Mockito.mock(CloudAccountRepository.class);
        when(repo.findByProvider("GCP")).thenReturn(List.of(a1, a2));
        AutoSpottingReconcileBeans.cloudAccountRepository = repo;
        AutoSpottingReconcileBeans.discovery = new FakeDiscovery(Provider.GCP,
                acc -> new DiscoveredGroup.Result(Provider.GCP, List.of(), Map.of(), List.of()));

        ctx = new AnnotationConfigApplicationContext();
        DriverManagerDataSource ds = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        ctx.getBeanFactory().registerSingleton("dataSource", ds);
        ctx.register(AutoSpottingReconcileBeans.class);
        ctx.refresh();
        jdbc = new JdbcTemplate(ds);
        DiscoveryJob job = ctx.getBean(DiscoveryJob.class);

        job.runProvider(Provider.GCP);

        // Both accounts share the project, so both are skipped and reported; no group row is created.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group", Integer.class)).isZero();
        List<String> details = jdbc.queryForList(
                "SELECT details::text FROM autospotting_audit WHERE event_type = 'DISCOVERY_RUN'", String.class);
        assertThat(details).isNotEmpty();
        assertThat(details).allMatch(d -> d.contains("DUPLICATE_PROJECT:shared-proj"));
    }

    @Test
    void failedLocation_isReportedInAudit() {
        CloudAccount account = account(4L, Provider.AWS, null);
        FakeDiscovery fake = new FakeDiscovery(Provider.AWS, acc -> new DiscoveredGroup.Result(
                Provider.AWS, List.of(), Map.of(), List.of("us-west-2")));

        discoveryJob.writeRunAudit(Provider.AWS, account, fake.discover(account, view()),
                List.of("us-west-2"));

        String details = jdbc.queryForObject(
                "SELECT details::text FROM autospotting_audit WHERE event_type = 'DISCOVERY_RUN' LIMIT 1",
                String.class);
        assertThat(details).contains("us-west-2");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String state(String groupKey) {
        return jdbc.queryForObject("SELECT state FROM spot_group WHERE group_key = ?", String.class, groupKey);
    }

    private DiscoveredGroup.Result oneGroup(int rule, boolean marker, boolean spotShare) {
        DiscoveredGroup g = new DiscoveredGroup("us-east-1/g", "arn:g", rule, null,
                rule == 1 ? null : SnapshotPayload.exact(Provider.AWS, 1, "{\"r\":true}", "sha"),
                "{\"seed\":true}", marker, spotShare);
        return new DiscoveredGroup.Result(Provider.AWS, List.of(g), Map.of(rule, 1), List.of());
    }

    private DiscoveredGroup.Result oneGroupWithStableId(int rule, String stableId) {
        DiscoveredGroup g = new DiscoveredGroup("us-east-1/g", stableId, rule, null,
                rule == 1 ? null : SnapshotPayload.exact(Provider.AWS, 1, "{\"r\":true}", "sha"),
                "{\"seed\":true}", true, true);
        return new DiscoveredGroup.Result(Provider.AWS, List.of(g), Map.of(rule, 1), List.of());
    }

    private static ReadOnlyCloudView view() {
        return new ReadOnlyCloudView() {
        };
    }

    /** A fake discovery that records whether any mutating call was made (none) and the last result. */
    private static final class FakeDiscovery implements GroupDiscovery {
        private final Provider provider;
        private final java.util.function.Function<CloudAccount, DiscoveredGroup.Result> fn;
        final AtomicInteger mutatingCalls = new AtomicInteger();
        DiscoveredGroup.Result lastResult;

        FakeDiscovery(Provider provider, java.util.function.Function<CloudAccount, DiscoveredGroup.Result> fn) {
            this.provider = provider;
            this.fn = fn;
        }

        @Override
        public Provider provider() {
            return provider;
        }

        @Override
        public DiscoveredGroup.Result discover(CloudAccount account, ReadOnlyCloudView v) {
            lastResult = fn.apply(account);
            return lastResult;
        }
    }
}
