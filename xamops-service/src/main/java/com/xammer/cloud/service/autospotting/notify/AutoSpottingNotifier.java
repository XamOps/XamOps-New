package com.xammer.cloud.service.autospotting.notify;

import com.xammer.cloud.domain.autospotting.AutoSpottingNotification;
import com.xammer.cloud.repository.autospotting.NotificationStore;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.NotificationCategory;
import com.xammer.cloud.service.NotificationRecipientResolver;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sends the pending rows of the notification outbox (design-ux-ops.md section 7.1, PO-24, PD-10). Triggered
 * every minute on {@code spotTaskScheduler}, run on {@code spotJobsExecutor} under the {@code notifier} job
 * lease through {@link JobRunner}, so slow mail never delays the operation dispatcher.
 *
 * <p>Each tick first turns two standing conditions into outbox rows (a group DEGRADED for more than N minutes;
 * statements and disputes the ledger recorded, only while ledger billing is on, because a shadow statement is
 * not customer-visible), then sends. Sending claims one unsent row at a time with
 * {@code FOR UPDATE SKIP LOCKED} and sends inside that row's transaction, so two notifier instances never send
 * the same row: the second skips the locked row, and sees {@code email_sent_at} once the first commits.
 *
 * <p>Recipients come from {@link NotificationRecipientResolver} under {@link NotificationCategory#AUTOSPOTTING}
 * (honours the AutoSpotting opt-out) or {@link NotificationCategory#AUTOSPOTTING_MANDATORY} (ignores every
 * opt-out, so a security mute can't silence a failure notice, PO-24). A send that fails is retried with
 * backoff up to {@code max-attempts} times, then left unsent and counted on
 * {@code autospotting.notifications{outcome=failed}}. If some recipients got the mail and others did not, the
 * row is marked sent with a note: a retry would mail the first group twice.
 *
 * <p>Email goes through the existing {@link EmailService#sendHtmlEmail(String, String, String)}. This class
 * never uses {@code SnsNotificationService}, which is the inbound Marketplace webhook.
 */
@Component
public class AutoSpottingNotifier {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingNotifier.class);
    static final String JOB = "notifier";
    private static final int BATCH_LIMIT = 200;

    private final JobRunner jobRunner;
    private final NotificationStore store;
    private final NotificationRecipientResolver recipients;
    private final EmailService emailService;
    private final AutoSpottingEmailBuilder builder;
    private final AutoSpottingMetrics metrics;
    private final AutoSpottingSwitchProperties switches;
    private final TransactionTemplate tx;
    private final int degradedMinutes;
    private final int maxAttempts;
    private final Duration financeLookback;

    public AutoSpottingNotifier(JobRunner jobRunner, NotificationStore store,
                                NotificationRecipientResolver recipients, EmailService emailService,
                                AutoSpottingEmailBuilder builder, AutoSpottingMetrics metrics,
                                AutoSpottingSwitchProperties switches, PlatformTransactionManager txManager,
                                @Value("${autospotting.notifier.degraded-minutes:30}") int degradedMinutes,
                                @Value("${autospotting.notifier.max-attempts:5}") int maxAttempts,
                                @Value("${autospotting.notifier.finance-lookback-days:14}") int financeLookbackDays) {
        this.jobRunner = jobRunner;
        this.store = store;
        this.recipients = recipients;
        this.emailService = emailService;
        this.builder = builder;
        this.metrics = metrics;
        this.switches = switches;
        this.tx = new TransactionTemplate(txManager);
        this.degradedMinutes = degradedMinutes;
        this.maxAttempts = maxAttempts;
        this.financeLookback = Duration.ofDays(financeLookbackDays);
    }

    /** Every minute (core section 4.2); the body runs under the {@code notifier} job lease. */
    @Scheduled(scheduler = "spotTaskScheduler", fixedDelayString = "${autospotting.notifier.interval-ms:60000}")
    public void tick() {
        jobRunner.runLeased(JOB, null, this::runOnce);
    }

    /** One pass: the sweeps, then the sends. Public so the integration test drives it without the scheduler. */
    public int runOnce() {
        sweeps();
        int sent = 0;
        for (int i = 0; i < BATCH_LIMIT; i++) {
            Boolean outcome = tx.execute(status -> sendOne());
            if (outcome == null) {
                break; // nothing due
            }
            if (outcome) {
                sent++;
            }
        }
        return sent;
    }

    private void sweeps() {
        try {
            tx.executeWithoutResult(status -> store.insertDegradedLong(degradedMinutes));
        } catch (RuntimeException e) {
            log.warn("notifier: degraded sweep failed: {}", e.getClass().getSimpleName());
        }
        if (!switches.ledgerBillingEnabled()) {
            return; // shadow statements and disputes are not customer-visible
        }
        Instant since = Instant.now().minus(financeLookback);
        try {
            tx.executeWithoutResult(status -> {
                store.insertStatementReady(since);
                store.insertDisputeUpdates(since);
            });
        } catch (RuntimeException e) {
            log.warn("notifier: ledger sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Send the oldest due row. Returns null when nothing is due, true when it was sent, false when not. */
    private Boolean sendOne() {
        Optional<AutoSpottingNotification> claimed = store.claimNextDue(maxAttempts);
        if (claimed.isEmpty()) {
            return null;
        }
        AutoSpottingNotification n = claimed.get();
        NotificationCategory category = n.mandatory() ? NotificationCategory.AUTOSPOTTING_MANDATORY
                : NotificationCategory.AUTOSPOTTING;
        List<String> to = recipients.emailsForClient(n.clientId(), category);
        if (to.isEmpty()) {
            if (n.mandatory()) {
                fail(n, "NO_RECIPIENTS");
                return false;
            }
            // Everyone opted out: there is nobody to mail and the in-app notice stays. Done, not failed.
            store.markSent(n.id(), "NO_RECIPIENTS");
            metrics.notification("skipped");
            return false;
        }
        AutoSpottingEmailBuilder.Email email;
        try {
            email = builder.build(n);
        } catch (RuntimeException e) {
            fail(n, "BUILD_FAILED " + e.getClass().getSimpleName());
            return false;
        }
        List<String> failed = new ArrayList<>();
        for (String address : to) {
            try {
                emailService.sendHtmlEmail(address, email.subject(), email.html());
            } catch (RuntimeException e) {
                failed.add(address);
                log.warn("notifier: send of notification {} failed: {}", n.id(), e.getClass().getSimpleName());
            }
        }
        if (failed.size() == to.size()) {
            fail(n, "SEND_FAILED " + to.size() + " of " + to.size());
            return false;
        }
        store.markSent(n.id(), failed.isEmpty() ? null
                : "PARTIAL " + (to.size() - failed.size()) + " of " + to.size());
        metrics.notification("sent");
        return true;
    }

    private void fail(AutoSpottingNotification n, String error) {
        int attempt = n.attempts() + 1;
        long delayMinutes = Math.min(60L, 1L << Math.min(n.attempts(), 6));
        store.markFailed(n.id(), error, Instant.now().plus(delayMinutes, ChronoUnit.MINUTES));
        if (attempt >= maxAttempts) {
            metrics.notification("failed");
            log.warn("notifier: notification {} left unsent after {} attempts ({})", n.id(), attempt, error);
        }
    }
}
