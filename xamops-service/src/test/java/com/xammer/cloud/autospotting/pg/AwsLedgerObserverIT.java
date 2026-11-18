package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.aws.AwsGroupStatusReader;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.observe.AwsLedgerObserver;
import com.xammer.cloud.service.autospotting.ledger.observe.ObservationStore;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesResponse;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code ledger-observe:aws} against recorded-style fixtures (plan-phase-17.md 17.2): an instance launched and
 * replaced yields one closed interval with {@code end_cause=REPLACED}; a missed sweep leaves
 * {@code first_absent_at} as the upper bound and never invents an end. No real AWS call: the clients are mocks.
 */
@Tag("containers")
class AwsLedgerObserverIT {

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static Instance ec2(String id, Instant launch) {
        return Instance.builder().instanceId(id).instanceType("m5.xlarge").instanceLifecycle("spot")
                .placement(p -> p.availabilityZone("us-east-1a")).platformDetails("Linux/UNIX").launchTime(launch).build();
    }

    private static AutoScalingGroup asg(int desired, String... ids) {
        var instances = java.util.Arrays.stream(ids).map(i -> software.amazon.awssdk.services.autoscaling.model.Instance
                .builder().instanceId(i).lifecycleState("InService").build()).toList();
        return AutoScalingGroup.builder().autoScalingGroupName("asg1").desiredCapacity(desired).minSize(1).maxSize(9)
                .instances(instances).build();
    }

    @Test
    void launchedAndReplacedYieldsOneClosedIntervalReplaced_andAMissedSweepKeepsTheUpperBound() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            var ds = Ledger17Support.dataSource(db);
            String groupId = jdbc.queryForObject("INSERT INTO spot_group (id, provider, cloud_account_id, client_id,"
                    + " group_key, state, state_changed_at, created_by, created_at, updated_at) VALUES (gen_random_uuid(),"
                    + " 'AWS', 7, 9, 'us-east-1/asg1', 'ACTIVE', now(), 'test', now(), now()) RETURNING id::text", String.class);

            AutoScalingClient asgClient = mock(AutoScalingClient.class);
            Ec2Client ec2Client = mock(Ec2Client.class);
            AwsReadOnlyView view = new AwsReadOnlyView("us-east-1", asgClient, ec2Client, null, null, null, null, null,
                    null, null);
            CloudAccountRepository accounts = mock(CloudAccountRepository.class);
            CloudAccount account = new CloudAccount();
            account.setId(7L);
            when(accounts.findById(7L)).thenReturn(Optional.of(account));
            MutableClock clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
            AwsLedgerObserver observer = new AwsLedgerObserver(mock(JobRunner.class), mock(LedgerGate.class),
                    new ObservationStore(ds), accounts, (a, r) -> view, new AwsGroupStatusReader(),
                    new AutoSpottingMetrics(new SimpleMeterRegistry()), clock);

            // Sweep 1: i-1 is running, launched 10 minutes ago.
            Instant launch1 = clock.now.minusSeconds(600);
            stub(asgClient, ec2Client, asg(2, "i-1"), List.of(), Map.of("i-1", ec2("i-1", launch1)));
            assertThat(observer.observeAll()).isEqualTo(1);
            assertThat(rows(jdbc, "SELECT count(*) FROM spot_usage_interval WHERE closed = false")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT start_at FROM spot_usage_interval WHERE instance_id = 'i-1'", java.sql.Timestamp.class).toInstant())
                    .isEqualTo(launch1);
            assertThat(jdbc.queryForObject("SELECT desired_units::text FROM spot_group_capacity_sample", String.class)).isEqualTo("2.000000");

            // Sweep 2, five minutes later: i-1 is gone, replaced by i-2 after a rebalance recommendation.
            clock.now = clock.now.plusSeconds(300);
            Instant ended = clock.now.minusSeconds(60);
            Activity terminate = Activity.builder().description("Terminating EC2 instance: i-1").statusCode("Successful")
                    .startTime(ended).cause("At " + ended + " an instance was taken out of service in response to an EC2 instance rebalance recommendation.").build();
            stub(asgClient, ec2Client, asg(2, "i-2"), List.of(terminate),
                    Map.of("i-1", ec2("i-1", launch1), "i-2", ec2("i-2", clock.now.minusSeconds(90))));
            observer.observeAll();
            Map<String, Object> closed = jdbc.queryForMap("SELECT end_at, end_source, end_cause, end_cause_source, closed,"
                    + " provider_interrupted FROM spot_usage_interval WHERE instance_id = 'i-1'");
            assertThat(((java.sql.Timestamp) closed.get("end_at")).toInstant()).isEqualTo(ended);
            assertThat(closed.get("end_source")).isEqualTo("ASG_ACTIVITY");
            assertThat(closed.get("end_cause")).isEqualTo("REPLACED");
            assertThat(closed.get("end_cause_source")).isEqualTo("ASG_ACTIVITY_CAUSE");
            assertThat(closed.get("closed")).isEqualTo(true);
            assertThat(closed.get("provider_interrupted")).isEqualTo(false);
            assertThat(rows(jdbc, "SELECT count(*) FROM spot_usage_interval WHERE instance_id = 'i-1'")).isEqualTo(1);
            assertThat(rows(jdbc, "SELECT count(*) FROM spot_usage_interval WHERE instance_id = 'i-2' AND closed = false")).isEqualTo(1);
            // Capacity did not change: no new sample.
            assertThat(rows(jdbc, "SELECT count(*) FROM spot_group_capacity_sample")).isEqualTo(1);

            // Sweeps 3 and 4: i-2 disappears with no activity (a missed sweep). first_absent_at is the first absence.
            clock.now = clock.now.plusSeconds(300);
            Instant firstAbsent = clock.now;
            stub(asgClient, ec2Client, asg(2), List.of(), Map.of("i-2", ec2("i-2", launch1.plusSeconds(500))));
            observer.observeAll();
            clock.now = clock.now.plusSeconds(300);
            observer.observeAll();
            Map<String, Object> absent = jdbc.queryForMap("SELECT end_at, first_absent_at, last_seen_at, closed"
                    + " FROM spot_usage_interval WHERE instance_id = 'i-2'");
            assertThat(absent.get("end_at")).isNull();
            assertThat(absent.get("closed")).isEqualTo(false);
            assertThat(((java.sql.Timestamp) absent.get("first_absent_at")).toInstant()).isEqualTo(firstAbsent);
            assertThat(((java.sql.Timestamp) absent.get("last_seen_at")).toInstant()).isBefore(firstAbsent);

            // The observer writes no money and nothing else.
            assertThat(rows(jdbc, "SELECT count(*) FROM savings_ledger_entry")).isZero();
            assertThat(rows(jdbc, "SELECT count(*) FROM savings_ledger_run")).isZero();
            assertThat(groupId).isNotNull();
        }
    }

    private static int rows(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @SuppressWarnings("unchecked")
    private static void stub(AutoScalingClient asgClient, Ec2Client ec2Client, AutoScalingGroup group,
                             List<Activity> activities, Map<String, Instance> instances) {
        when(asgClient.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class)))
                .thenReturn(DescribeAutoScalingGroupsResponse.builder().autoScalingGroups(group).build());
        when(asgClient.describeScalingActivities(any(Consumer.class)))
                .thenReturn(DescribeScalingActivitiesResponse.builder().activities(activities).build());
        when(ec2Client.describeInstances(any(DescribeInstancesRequest.class))).thenAnswer(inv -> {
            DescribeInstancesRequest req = inv.getArgument(0);
            List<String> wanted = req.filters().stream().flatMap(f -> f.values().stream()).toList();
            List<Instance> found = instances.entrySet().stream().filter(e -> wanted.contains(e.getKey()))
                    .map(Map.Entry::getValue).toList();
            return DescribeInstancesResponse.builder().reservations(Reservation.builder().instances(found).build()).build();
        });
    }
}
