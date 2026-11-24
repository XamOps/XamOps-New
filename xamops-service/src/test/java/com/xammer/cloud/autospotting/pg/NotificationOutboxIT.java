package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.autospotting.NotificationStore;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.NotificationRecipientResolver;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.Result;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingEmailBuilder;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingNotifier;
import com.xammer.cloud.service.autospotting.notify.NotificationEvent;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notification outbox on real PostgreSQL (plan-phase-24.md task 24.1, design-ux-ops.md section 7.1, PO-24,
 * AC-OBS-03, AC-SEC-03). The real lifecycle service, store, notifier and recipient resolver run over the
 * harness database; only the mail sender ({@link EmailService}) and the user lookup are mocks, so nothing is
 * ever sent. {@code @Tag("containers")} keeps it out of the default {@code mvn test}.
 *
 * <ul>
 *   <li>a rolled-back transition leaves no outbox row; a committed one leaves exactly one;</li>
 *   <li>a notice is sent once even with two notifier instances running at the same time;</li>
 *   <li>a security mute (and an AutoSpotting mute) never silences a failure notice, while a non-mandatory
 *       notice honours the AutoSpotting opt-out;</li>
 *   <li>the table is append-only: the notice never changes, and only delivery state does;</li>
 *   <li>a failed send is retried with backoff, then left unsent after the last attempt;</li>
 *   <li>DEGRADED for longer than N minutes is told once per episode;</li>
 *   <li>ADMIN_ENABLED is delivered (AC-SEC-03);</li>
 *   <li>the migration is idempotent in both harness modes.</li>
 * </ul>
 */
@Tag("containers")
class NotificationOutboxIT {

    static final String VERSION = "20261110.1";
    private static final long CLIENT = 1L;

    private AnnotationConfigApplicationContext contextFor(PgHarness.Database db) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        DataSource dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        ctx.getBeanFactory().registerSingleton("dataSource", dataSource);
        ctx.register(AutoSpottingCoreBeans.class);
        ctx.refresh();
        return ctx;
    }

    private static UUID insertGroup(JdbcTemplate jdbc, String state) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("provider", "AWS");
        row.put("cloud_account_id", 1L);
        row.put("client_id", CLIENT);
        row.put("group_key", "us-east-1/asg-" + id.toString().substring(0, 8));
        row.put("state", state);
        row.put("config_mode", "MANAGED");
        row.put("state_changed_at", Timestamp.from(now));
        row.put("created_by", "test");
        row.put("created_at", Timestamp.from(now));
        row.put("updated_at", Timestamp.from(now));
        PgHarness.insertRow(jdbc, "spot_group", row);
        return id;
    }

    private static void insertOperation(JdbcTemplate jdbc, UUID opId, UUID groupId, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", opId);
        row.put("group_id", groupId);
        row.put("cloud_account_id", 1L);
        row.put("kind", kind);
        row.put("action_class", "PROGRESS");
        row.put("status", "RUNNING");
        row.put("prior_state", "UNMANAGED");
        row.put("compensation_target", "SNAPSHOT");
        row.put("requested_by", "test");
        row.put("error_code", "CAPACITY");
        row.put("deadline_at", Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        row.put("created_at", Timestamp.from(Instant.now()));
        row.put("updated_at", Timestamp.from(Instant.now()));
        PgHarness.insertRow(jdbc, "spot_operation", row);
    }

    private static User user(String email, boolean securityEmail, boolean autospottingEmail) {
        User u = new User();
        u.setEmail(email);
        u.setUsername(email);
        u.setEmailNotificationsEnabled(securityEmail);
        u.setAutospottingEmailEnabled(autospottingEmail);
        return u;
    }

    private AutoSpottingNotifier notifier(AnnotationConfigApplicationContext ctx, EmailService mail,
                                          List<User> users, int maxAttempts, int degradedMinutes) {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByClientId(CLIENT)).thenReturn(users);
        AutoSpottingSwitchProperties switches = mock(AutoSpottingSwitchProperties.class);
        when(switches.ledgerBillingEnabled()).thenReturn(false);
        return new AutoSpottingNotifier(mock(JobRunner.class), ctx.getBean(NotificationStore.class),
                new NotificationRecipientResolver(repo), mail,
                new AutoSpottingEmailBuilder("https://live.example.test"),
                ctx.getBean(AutoSpottingMetrics.class), switches, ctx.getBean(PlatformTransactionManager.class),
                degradedMinutes, maxAttempts, 14);
    }

    private static Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("groupKey", "us-east-1/web-asg");
        p.put("provider", "AWS");
        p.put("accountName", "Prod AWS");
        p.put("occurredAt", Instant.now().toString());
        return p;
    }

    private static long count(JdbcTemplate jdbc, String where) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM autospotting_notification WHERE " + where, Long.class);
        return n == null ? 0 : n;
    }

    // ── transactional outbox ────────────────────────────────────────────────

    @Test
    void aRolledBackTransitionLeavesNoRow_andACommittedOneLeavesExactlyOne() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            LeaseService leases = ctx.getBean(LeaseService.class);
            GroupLifecycleService lifecycle = ctx.getBean(GroupLifecycleService.class);
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            UUID id = insertGroup(jdbc, "ACTIVE");
            UUID op = UUID.randomUUID();
            LeaseHandle handle = leases.acquireGroup(id, LeaseService.Component.DISPATCHER).orElseThrow();

            // The transition writes its outbox row, then the surrounding unit of work fails.
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                SpotGroup g = groups.findById(id).orElseThrow();
                assertThat(lifecycle.transition(handle, g, TransitionRequest.of(GroupState.FALLBACK,
                        "FALLBACK_APPLIED", Actor.system(), op, Instant.now()))).isEqualTo(Result.APPLIED);
                assertThat(count(jdbc, "group_id = '" + id + "'")).isEqualTo(1); // visible inside the tx
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(count(jdbc, "group_id = '" + id + "'")).isZero();
            assertThat(jdbc.queryForObject("SELECT state FROM spot_group WHERE id = ?", String.class, id))
                    .isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group_transition WHERE group_id = ?",
                    Long.class, id)).isZero();

            // The same transition committed leaves one FALLBACK_ENTERED row keyed on the operation.
            SpotGroup g = groups.findById(id).orElseThrow();
            assertThat(lifecycle.transition(handle, g, TransitionRequest.of(GroupState.FALLBACK, "FALLBACK_APPLIED",
                    Actor.system(), op, Instant.now()))).isEqualTo(Result.APPLIED);
            assertThat(count(jdbc, "group_id = '" + id + "'")).isEqualTo(1);
            Map<String, Object> row = jdbc.queryForMap("SELECT event_type, mandatory, audience, dedupe_key,"
                    + " client_id FROM autospotting_notification WHERE group_id = ?", id);
            assertThat(row.get("event_type")).isEqualTo("FALLBACK_ENTERED");
            assertThat(row.get("mandatory")).isEqualTo(false);
            assertThat(row.get("audience")).isEqualTo("CUSTOMER");
            assertThat(row.get("dedupe_key")).isEqualTo("fb_in:" + op);
            assertThat(((Number) row.get("client_id")).longValue()).isEqualTo(CLIENT);
        }
    }

    @Test
    void theSameNoticeRecordedTwiceIsOneRow() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            AutoSpottingNotificationService service = ctx.getBean(AutoSpottingNotificationService.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            UUID group = UUID.randomUUID();
            List<Boolean> results = tx.execute(status -> List.of(
                    service.record(NotificationEvent.PLAN_APPLIED, CLIENT, 1L, group, null, "applied:x", payload()),
                    service.record(NotificationEvent.PLAN_APPLIED, CLIENT, 1L, group, null, "applied:x", payload())));
            assertThat(results).containsExactly(true, false);
            assertThat(count(jdbc, "dedupe_key = 'applied:x'")).isEqualTo(1);
            // Outside a transaction the write is refused: a notice never exists without its state change.
            assertThatThrownBy(() -> service.record(NotificationEvent.PLAN_APPLIED, CLIENT, 1L, group, null,
                    "applied:y", payload())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        }
    }

    @Test
    void thePayloadIsRedactedBeforeItIsStored() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            AutoSpottingNotificationService service = ctx.getBean(AutoSpottingNotificationService.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            Map<String, Object> p = payload();
            p.put("accessKey", "AKIAABCDEFGHIJKLMNOP");
            p.put("reason", "see AKIAABCDEFGHIJKLMNOP and Bearer abc.def.ghi");
            tx.executeWithoutResult(status -> service.record(NotificationEvent.ADMIN_ENABLED, CLIENT, 1L, null, null,
                    "admin_enable:p", p));
            String stored = jdbc.queryForObject("SELECT payload::text FROM autospotting_notification", String.class);
            assertThat(stored).doesNotContain("AKIAABCDEFGHIJKLMNOP").doesNotContain("abc.def.ghi");
        }
    }

    // ── delivery ────────────────────────────────────────────────────────────

    @Test
    void aRowIsSentOnceAcrossTwoNotifierInstances() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            AutoSpottingNotificationService service = ctx.getBean(AutoSpottingNotificationService.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            for (int i = 0; i < 8; i++) {
                int n = i;
                tx.executeWithoutResult(status -> service.record(NotificationEvent.PLAN_APPLIED, CLIENT, 1L,
                        UUID.randomUUID(), null, "applied:" + n, payload()));
            }
            AtomicInteger sends = new AtomicInteger();
            EmailService mail = mock(EmailService.class);
            Mockito.doAnswer(inv -> {
                sends.incrementAndGet();
                Thread.sleep(25); // widen the window in which the other instance could take the same row
                return null;
            }).when(mail).sendHtmlEmail(anyString(), anyString(), anyString());
            List<User> users = List.of(user("owner@example.test", true, true));
            AutoSpottingNotifier a = notifier(ctx, mail, users, 5, 30);
            AutoSpottingNotifier b = notifier(ctx, mail, users, 5, 30);

            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger sentByA = new AtomicInteger();
            AtomicInteger sentByB = new AtomicInteger();
            Thread ta = new Thread(() -> {
                await(go);
                sentByA.set(a.runOnce());
            });
            Thread tb = new Thread(() -> {
                await(go);
                sentByB.set(b.runOnce());
            });
            ta.start();
            tb.start();
            go.countDown();
            ta.join();
            tb.join();

            assertThat(sends.get()).isEqualTo(8);
            assertThat(sentByA.get() + sentByB.get()).isEqualTo(8);
            assertThat(count(jdbc, "email_sent_at IS NOT NULL")).isEqualTo(8);
            // A third pass finds nothing left to send.
            assertThat(a.runOnce()).isZero();
            assertThat(sends.get()).isEqualTo(8);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aMutedSecurityCategoryStillDeliversAnAutoSpottingFailure_andOptOutHoldsForTheRest() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            AutoSpottingNotificationService service = ctx.getBean(AutoSpottingNotificationService.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            // securityMuted: security mail off, AutoSpotting on. asMuted: security on, AutoSpotting off.
            // both: everything off. open: everything on.
            User securityMuted = user("security-muted@example.test", false, true);
            User asMuted = user("as-muted@example.test", true, false);
            User both = user("both-muted@example.test", false, false);
            User open = user("open@example.test", true, true);
            EmailService mail = mock(EmailService.class);
            AutoSpottingNotifier notifier = notifier(ctx, mail, List.of(securityMuted, asMuted, both, open), 5, 30);

            tx.executeWithoutResult(status -> {
                service.record(NotificationEvent.APPLY_FAILED, CLIENT, 1L, UUID.randomUUID(), null, "apply_failed:1",
                        payload());
                service.record(NotificationEvent.PLAN_APPLIED, CLIENT, 1L, UUID.randomUUID(), null, "applied:1",
                        payload());
            });
            assertThat(notifier.runOnce()).isEqualTo(2);

            ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            verify(mail, Mockito.times(4 + 2)).sendHtmlEmail(to.capture(), subject.capture(), anyString());
            List<String> failureTo = new java.util.ArrayList<>();
            List<String> appliedTo = new java.util.ArrayList<>();
            for (int i = 0; i < to.getAllValues().size(); i++) {
                (subject.getAllValues().get(i).contains("Apply failed") ? failureTo : appliedTo)
                        .add(to.getAllValues().get(i));
            }
            // The failure reaches all four users, including the one who muted security mail and the one who
            // muted AutoSpotting mail (PO-24). The non-mandatory notice honours only the AutoSpotting mute.
            assertThat(failureTo).containsExactlyInAnyOrder("security-muted@example.test", "as-muted@example.test",
                    "both-muted@example.test", "open@example.test");
            assertThat(appliedTo).containsExactlyInAnyOrder("security-muted@example.test", "open@example.test");
        }
    }

    @Test
    void aNonMandatoryNoticeWithEveryoneOptedOutIsDoneNotRetried_andStaysInApp() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            AutoSpottingNotificationService service = ctx.getBean(AutoSpottingNotificationService.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            EmailService mail = mock(EmailService.class);
            AutoSpottingNotifier notifier = notifier(ctx, mail, List.of(user("a@example.test", true, false)), 5, 30);
            tx.executeWithoutResult(status -> service.record(NotificationEvent.FALLBACK_EXITED, CLIENT, 1L,
                    UUID.randomUUID(), null, "fb_out:1", payload()));
            assertThat(notifier.runOnce()).isZero();
            verify(mail, never()).sendHtmlEmail(anyString(), anyString(), anyString());
            assertThat(jdbc.queryForObject("SELECT last_error FROM autospotting_notification", String.class))
                    .isEqualTo("NO_RECIPIENTS");
            assertThat(count(jdbc, "email_sent_at IS NOT NULL")).isEqualTo(1); // closed: not retried
            assertThat(ctx.getBean(NotificationStore.class).listForClient(CLIENT, null, 10)).hasSize(1);
        }
    }

    @Test
    void aFailedSendIsRetriedWithBackoffThenLeftUnsent() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            AutoSpottingNotificationService service = ctx.getBean(AutoSpottingNotificationService.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            EmailService mail = mock(EmailService.class);
            Mockito.doThrow(new RuntimeException("smtp down")).when(mail)
                    .sendHtmlEmail(anyString(), anyString(), anyString());
            AutoSpottingNotifier notifier = notifier(ctx, mail, List.of(user("a@example.test", true, true)), 3, 30);
            tx.executeWithoutResult(status -> service.record(NotificationEvent.APPLY_FAILED, CLIENT, 1L,
                    UUID.randomUUID(), null, "apply_failed:r", payload()));

            assertThat(notifier.runOnce()).isZero();
            assertThat(jdbc.queryForObject("SELECT attempts FROM autospotting_notification", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() FROM autospotting_notification",
                    Boolean.class)).isTrue(); // backoff: not due yet
            assertThat(notifier.runOnce()).isZero();
            verify(mail, Mockito.times(1)).sendHtmlEmail(anyString(), anyString(), anyString());

            for (int expected = 2; expected <= 3; expected++) {
                jdbc.update("UPDATE autospotting_notification SET next_attempt_at = now() - interval '1 minute'");
                notifier.runOnce();
                assertThat(jdbc.queryForObject("SELECT attempts FROM autospotting_notification", Integer.class))
                        .isEqualTo(expected);
            }
            // The last attempt is spent: the row stays unsent and is not picked up again.
            jdbc.update("UPDATE autospotting_notification SET next_attempt_at = now() - interval '1 minute'");
            notifier.runOnce();
            verify(mail, Mockito.times(3)).sendHtmlEmail(anyString(), anyString(), anyString());
            assertThat(count(jdbc, "email_sent_at IS NULL")).isEqualTo(1);
            assertThat(ctx.getBean(AutoSpottingMetrics.class)).isNotNull();
            assertThat(ctx.getBean(SimpleMeterRegistry.class).get("autospotting.notifications")
                    .tag("outcome", "failed").counter().count()).isEqualTo(1.0);

            // The mail comes back: a manual reset of the attempt count lets it through, once.
            Mockito.reset(mail);
            jdbc.update("UPDATE autospotting_notification SET attempts = 0, next_attempt_at = NULL");
            assertThat(notifier.runOnce()).isEqualTo(1);
            assertThat(count(jdbc, "email_sent_at IS NOT NULL")).isEqualTo(1);
        }
    }

    @Test
    void aGroupDegradedLongerThanNMinutesIsToldOncePerEpisode() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            LeaseService leases = ctx.getBean(LeaseService.class);
            GroupLifecycleService lifecycle = ctx.getBean(GroupLifecycleService.class);
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);
            EmailService mail = mock(EmailService.class);
            AutoSpottingNotifier notifier = notifier(ctx, mail, List.of(user("a@example.test", false, false)), 5, 30);
            Instant now = Instant.now();

            // Degraded only 10 minutes ago: not long enough yet.
            UUID recent = insertGroup(jdbc, "ACTIVE");
            LeaseHandle hr = leases.acquireGroup(recent, LeaseService.Component.DISPATCHER).orElseThrow();
            lifecycle.transition(hr, groups.findById(recent).orElseThrow(), TransitionRequest.of(GroupState.DEGRADED,
                    "HEALTH_DEGRADED", Actor.system(), null, now.minus(10, ChronoUnit.MINUTES)));
            notifier.runOnce();
            assertThat(count(jdbc, "event_type = 'DEGRADED_LONG'")).isZero();

            // Degraded 50 minutes ago: told once, mandatory, so a user who muted everything still gets it.
            UUID id = insertGroup(jdbc, "ACTIVE");
            LeaseHandle handle = leases.acquireGroup(id, LeaseService.Component.DISPATCHER).orElseThrow();
            lifecycle.transition(handle, groups.findById(id).orElseThrow(), TransitionRequest.of(GroupState.DEGRADED,
                    "HEALTH_DEGRADED", Actor.system(), null, now.minus(50, ChronoUnit.MINUTES)));
            assertThat(notifier.runOnce()).isEqualTo(1);
            notifier.runOnce();
            assertThat(count(jdbc, "event_type = 'DEGRADED_LONG'")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT mandatory FROM autospotting_notification", Boolean.class)).isTrue();
            verify(mail, Mockito.times(1)).sendHtmlEmail(Mockito.eq("a@example.test"), anyString(), anyString());

            // Recovered, then degraded again (both still older than 30 minutes): a new episode is a new notice.
            lifecycle.transition(handle, groups.findById(id).orElseThrow(), TransitionRequest.of(GroupState.ACTIVE,
                    "HEALTH_RECOVERED", Actor.system(), null, now.minus(48, ChronoUnit.MINUTES)));
            lifecycle.transition(handle, groups.findById(id).orElseThrow(), TransitionRequest.of(GroupState.DEGRADED,
                    "HEALTH_DEGRADED", Actor.system(), null, now.minus(46, ChronoUnit.MINUTES)));
            notifier.runOnce();
            assertThat(count(jdbc, "event_type = 'DEGRADED_LONG'")).isEqualTo(2);
        }
    }

    @Test
    void failureAndRevertTransitionsWriteTheirNotices_withTheDesignedMandatoryFlags() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            LeaseService leases = ctx.getBean(LeaseService.class);
            GroupLifecycleService lifecycle = ctx.getBean(GroupLifecycleService.class);
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);

            // An ENABLE whose compensation failed: APPLY_FAILED, mandatory, carrying the stable error code only.
            UUID g1 = insertGroup(jdbc, "REVERTING");
            UUID enableOp = UUID.randomUUID();
            insertOperation(jdbc, enableOp, g1, "ENABLE");
            LeaseHandle h1 = leases.acquireGroup(g1, LeaseService.Component.DISPATCHER).orElseThrow();
            lifecycle.transition(h1, groups.findById(g1).orElseThrow(), TransitionRequest.of(GroupState.FAILED,
                    "COMPENSATION_FAILED", Actor.system(), enableOp, Instant.now()));

            // A REVERT that completed and one that failed.
            UUID g2 = insertGroup(jdbc, "REVERTING");
            UUID revertOp = UUID.randomUUID();
            insertOperation(jdbc, revertOp, g2, "REVERT");
            LeaseHandle h2 = leases.acquireGroup(g2, LeaseService.Component.DISPATCHER).orElseThrow();
            lifecycle.transition(h2, groups.findById(g2).orElseThrow(), TransitionRequest.of(GroupState.REVERTED,
                    "OP_REVERT_VERIFIED", Actor.system(), revertOp, Instant.now()));
            UUID g3 = insertGroup(jdbc, "REVERTING");
            LeaseHandle h3 = leases.acquireGroup(g3, LeaseService.Component.DISPATCHER).orElseThrow();
            lifecycle.transition(h3, groups.findById(g3).orElseThrow(), TransitionRequest.of(GroupState.FAILED,
                    "REVERT_FAILED", Actor.system(), UUID.randomUUID(), Instant.now()));

            assertThat(jdbc.queryForObject("SELECT event_type || ':' || mandatory FROM autospotting_notification"
                    + " WHERE group_id = ?", String.class, g1)).isEqualTo("APPLY_FAILED:true");
            assertThat(jdbc.queryForObject("SELECT payload->>'errorCode' FROM autospotting_notification"
                    + " WHERE group_id = ?", String.class, g1)).isEqualTo("CAPACITY");
            assertThat(jdbc.queryForObject("SELECT event_type || ':' || mandatory FROM autospotting_notification"
                    + " WHERE group_id = ?", String.class, g2)).isEqualTo("REVERT_COMPLETED:false");
            assertThat(jdbc.queryForObject("SELECT event_type || ':' || mandatory FROM autospotting_notification"
                    + " WHERE group_id = ?", String.class, g3)).isEqualTo("REVERT_FAILED:true");
        }
    }

    @Test
    void adminEnabledIsDeliveredToEveryoneIncludingMutedUsers_andNamesTheAdminAndReason() {
        try (PgHarness.Database db = PgHarness.emptySchema(); AnnotationConfigApplicationContext ctx = contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            AutoSpottingNotificationService service = ctx.getBean(AutoSpottingNotificationService.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            SpotGroupRepository groups = ctx.getBean(SpotGroupRepository.class);
            UUID id = insertGroup(jdbc, "UNMANAGED");
            UUID planId = UUID.randomUUID();
            SpotGroup group = groups.findById(id).orElseThrow();
            tx.executeWithoutResult(status -> {
                service.recordAdminEnabled(group, 1L, null, planId, "admin@xamops.test", "PLATFORM_ADMIN", null,
                        "Customer asked on the support ticket <b>42</b>");
                // The same confirm retried writes the same notice: one row.
                service.recordAdminEnabled(group, 1L, null, planId, "admin@xamops.test", "PLATFORM_ADMIN", null,
                        "Customer asked on the support ticket <b>42</b>");
            });
            assertThat(count(jdbc, "event_type = 'ADMIN_ENABLED' AND dedupe_key = 'admin_enable:" + planId + "'"))
                    .isEqualTo(1);

            EmailService mail = mock(EmailService.class);
            AutoSpottingNotifier notifier = notifier(ctx, mail, List.of(user("muted@example.test", false, false)),
                    5, 30);
            assertThat(notifier.runOnce()).isEqualTo(1);
            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(mail).sendHtmlEmail(Mockito.eq("muted@example.test"), Mockito.contains("Enabled by an administrator"),
                    html.capture());
            assertThat(html.getValue()).contains("admin@xamops.test").contains("support ticket")
                    .doesNotContain("<b>42</b>");
        }
    }

    // ── schema ──────────────────────────────────────────────────────────────

    @Test
    void theTableIsAppendOnly_theNoticeNeverChangesAndOnlyDeliveryStateDoes() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            jdbc.update("INSERT INTO autospotting_notification (event_type, audience, mandatory, client_id,"
                    + " cloud_account_id, dedupe_key, payload) VALUES ('PLAN_APPLIED', 'CUSTOMER', false, 1, 1,"
                    + " 'k1', '{\"a\":1}'::jsonb)");
            // Delivery state may change.
            jdbc.update("UPDATE autospotting_notification SET attempts = 1, last_error = 'x',"
                    + " read_by = '[7]'::jsonb WHERE dedupe_key = 'k1'");
            jdbc.update("UPDATE autospotting_notification SET email_sent_at = now() WHERE dedupe_key = 'k1'");
            // The notice may not.
            for (String set : List.of("payload = '{\"a\":2}'::jsonb", "event_type = 'APPLY_FAILED'",
                    "mandatory = true", "client_id = 2", "cloud_account_id = 2", "dedupe_key = 'k2'",
                    "created_at = now() + interval '1 day'", "audience = 'OPERATOR'",
                    "email_sent_at = now() + interval '1 day'", "email_sent_at = NULL")) {
                assertThatThrownBy(() -> jdbc.update("UPDATE autospotting_notification SET " + set
                        + " WHERE dedupe_key = 'k1'")).as(set).isInstanceOf(DataAccessException.class)
                        .hasMessageContaining("append-only");
            }
            assertThatThrownBy(() -> jdbc.update("DELETE FROM autospotting_notification WHERE dedupe_key = 'k1'"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
            // The dedupe index is the idempotency key.
            assertThatThrownBy(() -> jdbc.update("INSERT INTO autospotting_notification (event_type, audience,"
                    + " mandatory, client_id, cloud_account_id, dedupe_key, payload) VALUES ('PLAN_APPLIED',"
                    + " 'CUSTOMER', false, 1, 1, 'k1', '{}'::jsonb)")).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void emptySchema_createsTheTableAndIsIdempotent() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            assertThat(db.appliedVersions()).contains(VERSION);
            assertThat(PgHarness.tableExists(db.jdbc(), "autospotting_notification")).isTrue();
            assertThat(PgHarness.tableExists(db.jdbc(), "app_user")).isFalse(); // guarded ALTER skipped
            db.jdbc().execute(PgHarness.migration(VERSION).sql());
            db.jdbc().execute(PgHarness.migration(VERSION).sql());
            assertIndexes(db.jdbc());
            // No foreign key from or to the table (PO-21 retention).
            assertThat(db.jdbc().queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f'"
                    + " AND (conrelid = to_regclass('autospotting_notification')"
                    + " OR confrelid = to_regclass('autospotting_notification'))", Integer.class)).isZero();
        }
    }

    @Test
    void hibernateBase_addsTheUserColumnWithDefaultTrue_andIsIdempotent() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            assertThat(db.appliedVersions()).contains(VERSION);
            assertThat(columnCount(db.jdbc())).isEqualTo(1);
            db.jdbc().execute(PgHarness.migration(VERSION).sql());
            assertThat(columnCount(db.jdbc())).isEqualTo(1);
            assertIndexes(db.jdbc());
            assertThat(db.jdbc().queryForObject("SELECT column_default FROM information_schema.columns WHERE"
                    + " table_schema = current_schema() AND table_name = 'app_user'"
                    + " AND column_name = 'autospotting_email_enabled'", String.class)).contains("true");
        }
    }

    private static int columnCount(JdbcTemplate jdbc) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE"
                + " table_schema = current_schema() AND table_name = 'app_user'"
                + " AND column_name = 'autospotting_email_enabled'", Integer.class);
        return n == null ? 0 : n;
    }

    private static void assertIndexes(JdbcTemplate jdbc) {
        for (String index : List.of("ux_asn_dedupe", "ix_asn_pending", "ix_asn_client")) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND indexname = ?", Integer.class, index);
            assertThat(n).as(index).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname ="
                + " 'trg_autospotting_notification_guard'", Integer.class)).isEqualTo(1);
    }
}
