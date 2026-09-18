package com.xammer.cloud.service.security;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.domain.SecurityNotification;
import com.xammer.cloud.domain.SecurityNotificationPreference;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CloudSecurityFindingRepository;
import com.xammer.cloud.repository.SecurityNotificationPreferenceRepository;
import com.xammer.cloud.repository.SecurityNotificationRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.NotificationRecipientResolver;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the notification decision: who gets told, how often, and what happens when delivery
 * fails.
 *
 * <p>
 * The cases that matter most are the ones protecting the user from us. An immediate email per
 * finding turns one incident into a mail flood, and a preferences table that reads "no row" as
 * "notifications off" silences the feature for every existing customer. Both are easy to write,
 * neither produces an error, and both are tested here rather than reasoned about.
 */
class SecurityFindingNotifierTest {

    private CloudAccountRepository accountRepository;
    private CloudSecurityFindingRepository findingRepository;
    private SecurityNotificationRepository notificationRepository;
    private SecurityNotificationPreferenceRepository preferenceRepository;
    private UserRepository userRepository;
    private EmailService emailService;
    private RedisCacheService redisCache;
    private SimpMessagingTemplate messagingTemplate;
    private SecurityFindingNotifier notifier;

    private static final Long CLOUD_ACCOUNT_ID = 42L;
    private static final Long CLIENT_ID = 7L;
    private static final String AWS_ACCOUNT = "123456789012";

    @BeforeEach
    void setUp() {
        accountRepository = Mockito.mock(CloudAccountRepository.class);
        findingRepository = Mockito.mock(CloudSecurityFindingRepository.class);
        notificationRepository = Mockito.mock(SecurityNotificationRepository.class);
        preferenceRepository = Mockito.mock(SecurityNotificationPreferenceRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        emailService = Mockito.mock(EmailService.class);
        redisCache = Mockito.mock(RedisCacheService.class);
        messagingTemplate = Mockito.mock(SimpMessagingTemplate.class);
        SchedulingEnvironmentConfig envConfig = Mockito.mock(SchedulingEnvironmentConfig.class);

        // A real TransactionTemplate over a mocked manager: markNotified is a @Modifying update
        // reached by self-invocation, so it needs a programmatic transaction rather than an
        // annotation. Wiring the real template here means the test exercises the same path
        // production does instead of one that happens to work without a transaction.
        PlatformTransactionManager txManager = Mockito.mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mockito.mock(org.springframework.transaction.TransactionStatus.class));

        // A real resolver over the mocked repository rather than a mocked resolver. The recipient
        // rules — the username fallback and the per-user email opt-out — are the thing several of
        // these cases are actually asserting, and stubbing the resolver would assert nothing but
        // the stub. The existing findByClientId stubs drive it unchanged.
        notifier = new SecurityFindingNotifier(accountRepository, findingRepository,
                notificationRepository, preferenceRepository, userRepository,
                new NotificationRecipientResolver(userRepository), emailService,
                new SecurityFindingEmailBuilder(), redisCache, messagingTemplate, envConfig,
                txManager);
        ReflectionTestUtils.setField(notifier, "enabled", true);
        ReflectionTestUtils.setField(notifier, "intervalMs", 300_000L);
        ReflectionTestUtils.setField(notifier, "maxBatch", 100);

        // A client with one user who has an email address, and no cap consumed yet.
        User user = new User();
        user.setId(11L);
        user.setEmail("ops@example.com");
        when(userRepository.findByClientId(CLIENT_ID)).thenReturn(List.of(user));
        when(redisCache.get(anyString(), eq(Integer.class))).thenReturn(Optional.empty());
        when(preferenceRepository.findById(CLIENT_ID)).thenReturn(Optional.empty());
    }

    private CloudAccount account() {
        Client client = new Client();
        client.setId(CLIENT_ID);

        CloudAccount account = new CloudAccount();
        account.setId(CLOUD_ACCOUNT_ID);
        account.setAwsAccountId(AWS_ACCOUNT);
        account.setAccountName("production");
        account.setProvider("AWS");
        account.setClient(client);
        return account;
    }

    private CloudSecurityFinding finding(Long id, String severity, String title) {
        CloudSecurityFinding f = new CloudSecurityFinding();
        f.setId(id);
        f.setCloudAccountId(CLOUD_ACCOUNT_ID);
        f.setAwsAccountId(AWS_ACCOUNT);
        f.setFindingId("f-" + id);
        f.setSource(CloudSecurityFinding.SOURCE_GUARDDUTY);
        f.setSeverity(severity);
        f.setTitle(title);
        f.setResourceArn("arn:aws:s3:::bucket-" + id);
        f.setRegion("us-east-1");
        f.setFirstSeenAt(Instant.now());
        f.setLastSeenAt(Instant.now());
        return f;
    }

    private void pending(CloudSecurityFinding... findings) {
        when(findingRepository.findUnnotified(eq(CLOUD_ACCOUNT_ID), anyList()))
                .thenReturn(new ArrayList<>(List.of(findings)));
    }

    // ── Threshold ───────────────────────────────────────────────────────────

    /**
     * A client with no preference row gets the defaults, not silence. Reading absence as
     * "notifications off" would disable the feature for every client that existed before this
     * table, with nothing in the logs to explain it.
     */
    @Test
    void clientWithNoPreferenceRowGetsDefaults() {
        SecurityNotificationPreference prefs = notifier.preferencesFor(CLIENT_ID);

        assertTrue(prefs.isEmailEnabled());
        assertTrue(prefs.isInboxEnabled());
        assertEquals(SecuritySeverity.CRITICAL, prefs.getSeverityThreshold());
        assertEquals(SecurityNotificationPreference.DEFAULT_MAX_EMAILS_PER_HOUR,
                prefs.getMaxEmailsPerHour());
    }

    @Test
    void thresholdExpandsToEverySeverityAtOrAboveIt() {
        assertEquals(List.of("CRITICAL"),
                SecurityFindingNotifier.severitiesAtOrAbove("CRITICAL"));
        assertEquals(List.of("CRITICAL", "HIGH"),
                SecurityFindingNotifier.severitiesAtOrAbove("HIGH"));
        assertEquals(List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFORMATIONAL"),
                SecurityFindingNotifier.severitiesAtOrAbove("INFORMATIONAL"));
        // Unrecognised input must not expand to everything, which would be a spam switch.
        assertEquals(List.of("CRITICAL", "HIGH", "MEDIUM"),
                SecurityFindingNotifier.severitiesAtOrAbove("nonsense"));
    }

    @Test
    void nothingHappensWhenThereAreNoPendingFindings() {
        pending();
        notifier.notifyForAccount(account());

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(notificationRepository, never()).save(any());
        verify(findingRepository, never()).markNotified(anyList(), any());
    }

    // ── Coalescing ──────────────────────────────────────────────────────────

    /**
     * The whole point of the sweep: many findings become one email. Notifying per finding is what
     * turns a single GuardDuty incident into a mail flood that buries the message that mattered.
     */
    @Test
    void aBurstOfFindingsBecomesExactlyOneEmailAndOneInboxRow() {
        pending(finding(1L, "CRITICAL", "Bucket public"),
                finding(2L, "CRITICAL", "Trail deleted"),
                finding(3L, "CRITICAL", "Detector deleted"));

        notifier.notifyForAccount(account());

        verify(emailService, Mockito.times(1)).sendHtmlEmail(eq("ops@example.com"), anyString(), anyString());
        verify(notificationRepository, Mockito.times(1)).save(any(SecurityNotification.class));
    }

    @Test
    void inboxRowCarriesTheBatchCountAndWorstSeverity() {
        pending(finding(1L, "HIGH", "Policy deleted"),
                finding(2L, "CRITICAL", "Bucket public"));

        SecurityNotificationPreference prefs = SecurityNotificationPreference.defaults(CLIENT_ID);
        prefs.setSeverityThreshold("HIGH");
        when(preferenceRepository.findById(CLIENT_ID)).thenReturn(Optional.of(prefs));

        notifier.notifyForAccount(account());

        ArgumentCaptor<SecurityNotification> captor =
                ArgumentCaptor.forClass(SecurityNotification.class);
        verify(notificationRepository).save(captor.capture());

        SecurityNotification row = captor.getValue();
        assertEquals(2, row.getFindingCount());
        assertEquals(SecuritySeverity.CRITICAL, row.getSeverity(),
                "the bell must be coloured by the worst finding in the batch");
        assertEquals(CLIENT_ID, row.getClientId());
        assertEquals(AWS_ACCOUNT, row.getAwsAccountId());
        assertTrue(row.isEmailSent());
        assertFalse(row.isRead());
    }

    /** A single finding gets its own title rather than a "1 new finding" summary. */
    @Test
    void singleFindingNotificationUsesTheFindingTitle() {
        pending(finding(1L, "CRITICAL", "S3 Block Public Access was removed"));

        notifier.notifyForAccount(account());

        ArgumentCaptor<SecurityNotification> captor =
                ArgumentCaptor.forClass(SecurityNotification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals("S3 Block Public Access was removed", captor.getValue().getTitle());
    }

    // ── Rate cap ────────────────────────────────────────────────────────────

    /**
     * Once the hourly cap is reached, nothing is written and nothing is marked — so the deferred
     * findings go out in the next window instead of being lost, and no duplicate inbox row is
     * created for them.
     */
    @Test
    void hourlyCapDefersTheWholeBatchWithoutLosingOrDuplicatingIt() {
        pending(finding(1L, "CRITICAL", "Bucket public"));
        when(redisCache.get(anyString(), eq(Integer.class))).thenReturn(Optional.of(4));

        notifier.notifyForAccount(account());

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(notificationRepository, never()).save(any());
        verify(findingRepository, never()).markNotified(anyList(), any());
    }

    @Test
    void sendingIncrementsTheHourlyCounterWithinASixtyMinuteWindow() {
        pending(finding(1L, "CRITICAL", "Bucket public"));
        when(redisCache.get(anyString(), eq(Integer.class))).thenReturn(Optional.of(1));

        notifier.notifyForAccount(account());

        verify(redisCache).put(eq("notif::secfinding::sent::" + AWS_ACCOUNT), eq(2), eq(60));
    }

    /**
     * Redis being down must not suppress security alerts. The cap is a comfort feature; losing it
     * means unmetered email, which is strictly better than a silent security product.
     */
    @Test
    void redisFailureAllowsTheSendRatherThanBlockingIt() {
        pending(finding(1L, "CRITICAL", "Bucket public"));
        when(redisCache.get(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("redis down"));

        notifier.notifyForAccount(account());

        verify(emailService).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    // ── Preferences honoured ────────────────────────────────────────────────

    /** Email off, inbox on: the bell still works, which is why the two switches are separate. */
    @Test
    void emailDisabledStillWritesTheInboxRowAndMarksNotified() {
        pending(finding(1L, "CRITICAL", "Bucket public"));
        SecurityNotificationPreference prefs = SecurityNotificationPreference.defaults(CLIENT_ID);
        prefs.setEmailEnabled(false);
        when(preferenceRepository.findById(CLIENT_ID)).thenReturn(Optional.of(prefs));

        notifier.notifyForAccount(account());

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        ArgumentCaptor<SecurityNotification> captor =
                ArgumentCaptor.forClass(SecurityNotification.class);
        verify(notificationRepository).save(captor.capture());
        assertFalse(captor.getValue().isEmailSent(),
                "the row must record that no email accompanied it");
        verify(findingRepository).markNotified(anyList(), any());
    }

    @Test
    void inboxDisabledStillSendsEmail() {
        pending(finding(1L, "CRITICAL", "Bucket public"));
        SecurityNotificationPreference prefs = SecurityNotificationPreference.defaults(CLIENT_ID);
        prefs.setInboxEnabled(false);
        when(preferenceRepository.findById(CLIENT_ID)).thenReturn(Optional.of(prefs));

        notifier.notifyForAccount(account());

        verify(emailService).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(notificationRepository, never()).save(any());
    }

    // ── Delivery failure ────────────────────────────────────────────────────

    /**
     * Findings are marked notified even when every send fails. The alternative is retrying a
     * broken SMTP configuration every five minutes forever, against findings the user can already
     * see on the page.
     */
    @Test
    void findingsAreMarkedNotifiedEvenWhenEmailFails() {
        pending(finding(1L, "CRITICAL", "Bucket public"));
        Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());

        notifier.notifyForAccount(account());

        verify(findingRepository).markNotified(anyList(), any());
    }

    // ── Per-user opt-out ────────────────────────────────────────────────────

    /**
     * A user switched off in BillOps admin is skipped while their colleagues still get the mail.
     *
     * <p>
     * The whole point of the per-user flag: the pre-existing switches were client-scoped, so
     * quietening one person meant silencing the tenant. Asserted with {@code never()} on the
     * specific address rather than by counting sends, because the failure mode is a recipient
     * quietly surviving the filter, not a change in volume.
     */
    @Test
    void aUserWhoOptedOutIsNotEmailedButTheirColleaguesAre() {
        User muted = new User();
        muted.setId(1L);
        muted.setEmail("muted@example.com");
        muted.setEmailNotificationsEnabled(false);
        User subscribed = new User();
        subscribed.setId(2L);
        subscribed.setEmail("subscribed@example.com");
        when(userRepository.findByClientId(CLIENT_ID)).thenReturn(List.of(muted, subscribed));

        pending(finding(1L, "CRITICAL", "Bucket public"));
        notifier.notifyForAccount(account());

        verify(emailService, never()).sendHtmlEmail(eq("muted@example.com"), anyString(), anyString());
        verify(emailService).sendHtmlEmail(eq("subscribed@example.com"), anyString(), anyString());
    }

    /**
     * Everyone opting out stops the email but must not stop the rest of the pipeline.
     *
     * <p>
     * The findings still have to reach the inbox row, the socket push and {@code markNotified}.
     * Treating "nobody wants email" as "nothing to do" would hide findings from the Security
     * Center page and leave them unmarked, which the next sweep would then reprocess forever.
     */
    @Test
    void everyUserOptingOutSuppressesEmailWithoutSuppressingTheInboxOrTheMarking() {
        User muted = new User();
        muted.setId(1L);
        muted.setEmail("muted@example.com");
        muted.setEmailNotificationsEnabled(false);
        when(userRepository.findByClientId(CLIENT_ID)).thenReturn(List.of(muted));

        pending(finding(1L, "CRITICAL", "Bucket public"));
        notifier.notifyForAccount(account());

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(notificationRepository).save(any(SecurityNotification.class));
        verify(findingRepository).markNotified(anyList(), any());
    }

    /**
     * Opting out of email must not remove the finding from the product.
     *
     * <p>
     * The flag is named for email deliberately. Someone who muted their mailbox still sees findings
     * on the Security Center page and still gets the live push, because the alternative is an admin
     * silently revoking a colleague's visibility of their own security posture through what reads
     * as a mail preference.
     */
    @Test
    void optingOutOfEmailStillPushesToTheUserInApp() {
        User muted = new User();
        muted.setId(11L);
        muted.setEmail("muted@example.com");
        muted.setEmailNotificationsEnabled(false);
        when(userRepository.findByClientId(CLIENT_ID)).thenReturn(List.of(muted));

        pending(finding(1L, "CRITICAL", "Bucket public"));
        notifier.notifyForAccount(account());

        verify(messagingTemplate).convertAndSendToUser(
                eq("11"), eq(SecurityFindingNotifier.USER_QUEUE), any());
    }

    /** One bad address must not deny the others in the same batch. */
    @Test
    void oneFailingRecipientDoesNotBlockTheRest() {
        User bad = new User();
        bad.setId(1L);
        bad.setEmail("bad@example.com");
        User good = new User();
        good.setId(2L);
        good.setEmail("good@example.com");
        when(userRepository.findByClientId(CLIENT_ID)).thenReturn(List.of(bad, good));

        Mockito.doThrow(new RuntimeException("rejected"))
                .when(emailService).sendHtmlEmail(eq("bad@example.com"), anyString(), anyString());

        pending(finding(1L, "CRITICAL", "Bucket public"));
        notifier.notifyForAccount(account());

        verify(emailService).sendHtmlEmail(eq("good@example.com"), anyString(), anyString());
        verify(findingRepository).markNotified(anyList(), any());
    }

    /**
     * Regression test for a live email loop.
     *
     * <p>
     * {@code markNotified} is a {@code @Modifying} JPQL update reached by self-invocation, so an
     * {@code @Transactional} annotation on it is silently ignored and the update throws
     * {@code TransactionRequiredException}. It threw <em>after</em> the email had been sent, so the
     * finding stayed unnotified and the next sweep sixty seconds later mailed every recipient
     * again. Observed in a real environment: two sends of the same finding, three minutes apart,
     * to nine addresses.
     *
     * <p>
     * Asserting the marking happens on the same pass as the send is what keeps that from returning.
     */
    @Test
    void aSentFindingIsMarkedOnTheSamePassSoItIsNeverReSent() {
        CloudSecurityFinding f = finding(1L, "CRITICAL", "Bucket public");
        pending(f);

        notifier.notifyForAccount(account());

        // Email out and mark recorded together: either both, or the next sweep re-sends.
        verify(emailService).sendHtmlEmail(anyString(), anyString(), anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(findingRepository).markNotified(ids.capture(), any());
        assertEquals(List.of(1L), ids.getValue(),
                "the finding that was just emailed must be the one marked");
    }

    /** A failed inbox write must not stop the email or the marking. */
    @Test
    void inboxWriteFailureDoesNotBlockTheRestOfTheNotification() {
        pending(finding(1L, "CRITICAL", "Bucket public"));
        when(notificationRepository.save(any())).thenThrow(new RuntimeException("db down"));

        notifier.notifyForAccount(account());

        verify(emailService).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(findingRepository).markNotified(anyList(), any());
    }

    // ── WebSocket ───────────────────────────────────────────────────────────

    /**
     * Pushes go to a user destination, never a topic.
     *
     * <p>
     * Every {@code /topic/...} in this application is broadcast with no tenant filter, so a
     * finding published there would be readable by any connected customer. This asserts the
     * destination shape because switching it to a topic would work perfectly in testing and leak
     * in production.
     */
    @Test
    void pushesToAUserQueueAndNotABroadcastTopic() {
        pending(finding(1L, "CRITICAL", "Bucket public"));

        notifier.notifyForAccount(account());

        verify(messagingTemplate).convertAndSendToUser(eq("11"),
                eq(SecurityFindingNotifier.USER_QUEUE), any());
        assertTrue(SecurityFindingNotifier.USER_QUEUE.startsWith("/queue/"),
                "tenant-sensitive pushes must not use /topic");
    }

    /** A socket failure is irrelevant to durability — the finding is already stored. */
    @Test
    void pushFailureDoesNotBlockTheNotification() {
        pending(finding(1L, "CRITICAL", "Bucket public"));
        Mockito.doThrow(new RuntimeException("no session"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        notifier.notifyForAccount(account());

        verify(findingRepository).markNotified(anyList(), any());
    }

    // ── Batch ceiling ───────────────────────────────────────────────────────

    /**
     * A flood is trimmed to the batch ceiling, and only the trimmed findings are marked — the
     * remainder stays pending for the next window rather than being silently dropped.
     */
    @Test
    void oversizedBatchIsTrimmedAndOnlyTheSentFindingsAreMarked() {
        ReflectionTestUtils.setField(notifier, "maxBatch", 2);
        pending(finding(1L, "CRITICAL", "one"),
                finding(2L, "CRITICAL", "two"),
                finding(3L, "CRITICAL", "three"));

        notifier.notifyForAccount(account());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(findingRepository).markNotified(captor.capture(), any());
        assertEquals(List.of(1L, 2L), captor.getValue(),
                "the third finding must remain pending, not be marked without being sent");
    }
}
