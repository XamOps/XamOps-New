package com.xammer.cloud.autospotting.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.aws.events.AutoSpottingEventListener;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventDlqService;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventEnvelope;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventProcessor;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventProperties;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventStore;
import com.xammer.cloud.service.autospotting.aws.events.EventSenderVerifier;
import com.xammer.cloud.service.autospotting.aws.events.FakeSqsClient;
import com.xammer.cloud.service.autospotting.aws.events.InstanceGroupLookup;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The AWS event pipeline on real PostgreSQL with a stubbed SQS FIFO queue (no real AWS, no queue created).
 * Duplicates apply once, poison reaches the DLQ after five receives without delaying another account, a sender
 * mismatch is dropped and counted, unmanaged groups are acknowledged and counted, and a replay does not
 * double-apply (AC-AWS-07, AC-EVT-02, AC-HLT-06). Record-only mode sets no reconcile hint.
 */
@Tag("containers")
class AutoSpottingEventListenerIT {

    private static final String FWD = "AROAGOOD";
    private PgHarness.Database db;
    private JdbcTemplate jdbc;
    private FakeSqsClient sqs;
    private SimpleMeterRegistry meters;
    private AutoSpottingEventListener listener;
    private AwsEventDlqService dlq;
    private UUID group;

    private static CloudAccount account(long id, String awsId) {
        CloudAccount a = new CloudAccount();
        a.setId(id);
        a.setProvider("AWS");
        a.setStatus("CONNECTED");
        a.setAwsAccountId(awsId);
        a.setAsForwarderRoleId(FWD);
        return a;
    }

    @BeforeEach
    void setUp() {
        db = PgHarness.emptySchema();
        jdbc = db.jdbc();
        group = UUID.randomUUID();
        insertGroup(group, 1L, "ap-south-1/web-asg");
        sqs = new FakeSqsClient();
        meters = new SimpleMeterRegistry();
        build(true);
    }

    private void build(boolean recordOnly) {
        DriverManagerDataSource ds = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        AwsEventProperties props = new AwsEventProperties(false, true, FakeSqsClient.QUEUE_URL, recordOnly);
        AwsEventStore store = new AwsEventStore(jdbc);
        CloudAccountRepository repo = mock(CloudAccountRepository.class);
        when(repo.findByAwsAccountId("111111111111")).thenReturn(List.of(account(1L, "111111111111")));
        when(repo.findByAwsAccountId("222222222222")).thenReturn(List.of(account(2L, "222222222222")));
        InstanceGroupLookup lookup = (a, r, i) -> Optional.empty();
        EventSenderVerifier sender = new EventSenderVerifier((a, r, s) -> Optional.empty(), store, props);
        SwitchService switches = mock(SwitchService.class);
        when(switches.current()).thenReturn(new SwitchState(Set.of(), Set.of(), false, false));
        AwsEventProcessor processor = new AwsEventProcessor(new ObjectMapper(), repo, sender, lookup, store, props,
                switches, new AutoSpottingMetrics(meters), new DataSourceTransactionManager(ds));
        SchedulingEnvironmentConfig env = mock(SchedulingEnvironmentConfig.class);
        when(env.getCurrentTenantId()).thenReturn("customer1");
        AutoSpottingSwitchProperties sp = mock(AutoSpottingSwitchProperties.class);
        when(sp.cloudEnabled(true)).thenReturn(true);
        listener = new AutoSpottingEventListener(sqs, props, processor, null, switches, sp, env, true);
        dlq = new AwsEventDlqService(sqs, props, new ObjectMapper(), mock(AutoSpottingAuditWriter.class),
                com.xammer.cloud.service.autospotting.aws.events.AwsEventDlqServiceTest.txManager());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void insertGroup(UUID id, long accountId, String key) {
        jdbc.update("INSERT INTO spot_group (id, provider, cloud_account_id, client_id, group_key, state,"
                + " config_mode, state_changed_at, created_by, created_at, updated_at)"
                + " VALUES (?, 'AWS', ?, 1, ?, ?, 'MANAGED', now(), 't', now(), now())", id, accountId, key,
                GroupState.ACTIVE.name());
    }

    private static String body(String id, String detailType, String account, String detail) {
        return "{\"id\":\"" + id + "\",\"detail-type\":\"" + detailType + "\",\"source\":\"aws.autoscaling\","
                + "\"account\":\"" + account + "\",\"time\":\"2026-11-09T10:15:30Z\",\"region\":\"ap-south-1\","
                + "\"detail\":" + detail + "}";
    }

    private static String launchFailed(String id, String account, String group) {
        return body(id, AwsEventEnvelope.LAUNCH_FAILED, account, "{\"AutoScalingGroupName\":\"" + group
                + "\",\"ActivityId\":\"act-" + id
                + "\",\"StatusMessage\":\"There is no Spot capacity available that matches your request.\"}");
    }

    private int events() {
        return jdbc.queryForObject("SELECT count(*) FROM spot_group_event", Integer.class);
    }

    private double count(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void duplicatesAreAppliedOnce_andRecordOnlySetsNoHint() {
        String body = launchFailed("ev-1", "111111111111", "web-asg");
        sqs.send(body, "acct-111111111111-ap-south-1", "ev-1", FWD + ":s");
        listener.pollOnce();
        sqs.send(body, "acct-111111111111-ap-south-1", "ev-1-again", FWD + ":s");
        listener.pollOnce();
        assertThat(events()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT failure_class FROM spot_group_event", String.class))
                .isEqualTo("SPOT_CAPACITY");
        assertThat(count("autospotting.events", "outcome", "duplicate")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reconcile_hint_at IS NULL FROM spot_group WHERE id = ?",
                Boolean.class, group)).isTrue();
        assertThat(sqs.queue).isEmpty();
    }

    @Test
    void whenNotRecordOnlyALaunchFailureSetsTheHint() {
        build(false);
        sqs.send(launchFailed("ev-2", "111111111111", "web-asg"), "acct-111111111111-ap-south-1", "ev-2", FWD + ":s");
        listener.pollOnce();
        assertThat(jdbc.queryForObject("SELECT reconcile_hint_at IS NOT NULL FROM spot_group WHERE id = ?",
                Boolean.class, group)).isTrue();
    }

    @Test
    void aSenderMismatchIsDroppedAndCountedWithNoInboxRow() {
        sqs.send(launchFailed("ev-4", "111111111111", "web-asg"), "acct-111111111111-ap-south-1", "ev-4", "AROAEVIL:s");
        listener.pollOnce();
        assertThat(events()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM autospotting_event_inbox", Integer.class)).isZero();
        assertThat(count("autospotting.events.security", "reason", "sender_mismatch")).isEqualTo(1);
        assertThat(sqs.queue).isEmpty();
    }

    @Test
    void anUnmanagedGroupAndAnUnknownAccountAreAcknowledgedAndCounted() {
        sqs.send(launchFailed("ev-5", "111111111111", "other-asg"), "acct-111111111111-ap-south-1", "ev-5", FWD + ":s");
        sqs.send(launchFailed("ev-6", "999999999999", "web-asg"), "acct-999999999999-ap-south-1", "ev-6", FWD + ":s");
        listener.pollOnce();
        assertThat(events()).isZero();
        assertThat(count("autospotting.events", "outcome", "unmanaged")).isEqualTo(1);
        assertThat(count("autospotting.events", "outcome", "unknown_account")).isEqualTo(1);
        assertThat(sqs.queue).isEmpty();
    }

    @Test
    void poisonMovesToDlqAfterFiveReceives_andAPoisonGroupDoesNotDelayAnotherAccount() {
        sqs.send("not json", "acct-111111111111-ap-south-1", "bad", FWD + ":s");
        for (int i = 0; i < 5; i++) {
            listener.pollOnce();
            sqs.expireVisibility();
        }
        assertThat(sqs.dlq).hasSize(1);
        assertThat(sqs.queue).isEmpty();
        UUID other = UUID.randomUUID();
        insertGroup(other, 2L, "ap-south-1/api-asg");
        sqs.send("not json", "acct-111111111111-ap-south-1", "bad2", FWD + ":s");
        sqs.send(launchFailed("ev-7", "222222222222", "api-asg"), "acct-222222222222-ap-south-1", "ev-7", FWD + ":s");
        listener.pollOnce();
        assertThat(events()).isEqualTo(1);
        assertThat(sqs.queue).extracting(m -> m.group).containsExactly("acct-111111111111-ap-south-1");
    }

    @Test
    void dlqRedriveReplaysOnce() {
        String body = launchFailed("ev-8", "111111111111", "web-asg");
        sqs.send(body, "acct-111111111111-ap-south-1", "ev-8", FWD + ":s");
        listener.pollOnce();
        assertThat(events()).isEqualTo(1);
        sqs.send(body, "acct-111111111111-ap-south-1", "dl-1", FWD + ":s");
        sqs.dlq.add(sqs.queue.remove(0));
        dlq.redrive("replay", AutoSpottingAuditWriter.Actor.system());
        listener.pollOnce();
        assertThat(events()).isEqualTo(1);
        assertThat(count("autospotting.events", "outcome", "duplicate")).isEqualTo(1);
    }
}
