package com.xammer.cloud.service.war;

import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.event.CloudAccountConnectedEvent;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.NotificationRecipientResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs the first WAR scan when an account is onboarded and emails the result.
 *
 * <p>
 * A new account is the one moment the customer is paying attention to their security
 * posture, and the one moment they have nothing to compare against. Sending the first
 * review unprompted converts "connected successfully" into an actual finding they can
 * act on.
 *
 * <h2>Why an event listener</h2>
 * A full scan makes hundreds of API calls per region and takes minutes, so it cannot run
 * inside the onboarding request. {@code AFTER_COMMIT} guarantees the CONNECTED row is
 * visible before the scan tries to assume the role — publishing mid-transaction raced
 * the commit and the scan would fail to find the account. {@code fallbackExecution} is
 * on because the AWS and Azure paths save outside an explicit transaction, and without
 * it the listener would silently never fire for two of the three providers.
 *
 * <p>
 * Onboarding never fails because of this service. Every failure is caught and logged:
 * the customer's account is connected either way, and the dashboard still works.
 */
@Service
public class WarOnboardingReportService {

    private static final Logger log = LoggerFactory.getLogger(WarOnboardingReportService.class);

    /**
     * Ceiling on the first scan. Generous because a cold account across a dozen regions
     * is the slow case, and there is no user waiting on a response.
     *
     * <p>
     * A field rather than a constant so it is tunable per environment, and so tests can
     * exercise the timeout path without waiting out the real budget.
     */
    @Value("${xamops.war.onboarding.scan-timeout-minutes:12}")
    private long scanTimeoutMinutes = 12;

    /**
     * Suppression window for a repeat send.
     *
     * <p>
     * The EventBridge callback is retried by AWS on any non-2xx, and a customer who hits
     * "verify" twice produces two CONNECTED transitions. Neither should produce two
     * emails. Keyed per account in the same table the reports themselves are archived
     * in, so this needs no migration.
     */
    private static final Duration RESEND_SUPPRESSION = Duration.ofHours(24);

    private static final String SENT_KEY_PREFIX = "WAR_ONBOARDING_EMAIL::";

    private final CloudAccountRepository cloudAccountRepository;
    private final NotificationRecipientResolver recipientResolver;
    private final CachedDataRepository cachedDataRepository;
    private final WarScanDispatcher scanDispatcher;
    private final WarEmailBuilder emailBuilder;
    private final EmailService emailService;

    public WarOnboardingReportService(CloudAccountRepository cloudAccountRepository,
            NotificationRecipientResolver recipientResolver,
            CachedDataRepository cachedDataRepository,
            WarScanDispatcher scanDispatcher,
            WarEmailBuilder emailBuilder,
            EmailService emailService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.recipientResolver = recipientResolver;
        this.cachedDataRepository = cachedDataRepository;
        this.scanDispatcher = scanDispatcher;
        this.emailBuilder = emailBuilder;
        this.emailService = emailService;
    }

    /**
     * Scans a newly connected account and mails the report to the owning tenant.
     *
     * <p>
     * Runs on {@code warReportExecutor} rather than the shared default pool: the method
     * blocks for the whole scan, and a batch of simultaneous onboardings on a 4-thread
     * pool would stall every other {@code @Async} in the service.
     */
    @Async("warReportExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountConnected(CloudAccountConnectedEvent event) {
        Long accountId = event.cloudAccountId();
        try {
            deliverReport(accountId);
        } catch (Exception e) {
            // Never propagate: onboarding already succeeded and the user has been told so.
            log.error("[WAR-onboarding] report delivery failed for cloud account {}", accountId, e);
        }
    }

    /**
     * The delivery pipeline, separated from the listener so a test or an admin re-send
     * can drive it directly without publishing an event.
     */
    @Transactional(readOnly = true)
    public void deliverReport(Long cloudAccountId) {
        Optional<CloudAccount> found = cloudAccountRepository.findById(cloudAccountId);
        if (found.isEmpty()) {
            log.warn("[WAR-onboarding] cloud account {} no longer exists, skipping", cloudAccountId);
            return;
        }
        CloudAccount account = found.get();

        if (!"CONNECTED".equals(account.getStatus())) {
            log.info("[WAR-onboarding] account {} is {}, not CONNECTED — skipping",
                    cloudAccountId, account.getStatus());
            return;
        }

        if (alreadySent(cloudAccountId)) {
            log.info("[WAR-onboarding] report already emailed for account {} within {}h — skipping",
                    cloudAccountId, RESEND_SUPPRESSION.toHours());
            return;
        }

        // Resolved inside the transaction: client is a LAZY @ManyToOne and users are a
        // separate query, both of which need the session this method holds open.
        //
        // Empty now covers two cases — the tenant has no usable address, or every user on it has
        // security email switched off in BillOps admin. Both mean skip, and skipping before the
        // scan rather than after is deliberate: a scan nobody will be mailed about is minutes of
        // API calls against the customer's account for nothing.
        List<String> recipients = recipientResolver.emailsForAccount(account);
        if (recipients.isEmpty()) {
            log.warn("[WAR-onboarding] no recipient for the tenant owning account {} — either no "
                    + "email address on file or every user has security email disabled. Skipping.",
                    cloudAccountId);
            return;
        }

        String accountName = account.getAccountName();
        log.info("[WAR-onboarding] starting first WAR scan for {} ({} {})",
                accountName, account.getProvider(), account.getProviderAccountId());

        WarReport report;
        try {
            report = scanDispatcher.scan(account, true)
                    .get(scanTimeoutMinutes, TimeUnit.MINUTES);
        } catch (UnsupportedOperationException e) {
            log.info("[WAR-onboarding] no WAR baseline for provider {} — skipping",
                    account.getProvider());
            return;
        } catch (Exception e) {
            // A scan that never returned is different from a scan that returned "blocked".
            // The latter still produces a useful email; this does not.
            log.error("[WAR-onboarding] first scan did not complete for account {}: {}",
                    cloudAccountId, e.getMessage());
            return;
        }

        if (report == null) {
            log.warn("[WAR-onboarding] scan returned no report for account {}", cloudAccountId);
            return;
        }

        if (!worthSending(report, accountName)) {
            return;
        }

        String subject = emailBuilder.subject(report, accountName);
        String html = emailBuilder.build(report, accountName);

        int sent = 0;
        for (String recipient : recipients) {
            try {
                emailService.sendHtmlEmail(recipient, subject, html);
                sent++;
                log.info("[WAR-onboarding] sent report for {} to {}", accountName, recipient);
            } catch (Exception e) {
                log.error("[WAR-onboarding] could not send report for {} to {}: {}",
                        accountName, recipient, e.getMessage());
            }
        }

        // Marked only after a real delivery, so a total send failure is retried by the
        // next CONNECTED transition rather than suppressed for a day.
        if (sent > 0) {
            markSent(cloudAccountId, report);
            log.info("[WAR-onboarding] complete for {} — score {} ({}), {} recipient(s)",
                    accountName, report.getWarScore(), report.getRiskLevel(), sent);
        }
    }

    // ── Is there anything to report? ────────────────────────────────────────

    /**
     * Whether this report earns an email. Two reasons to stay quiet.
     *
     * <p>
     * <b>The scan did not complete.</b> {@code assessable} is the report's own coverage
     * gate: false means fewer than half the checkable controls produced a verdict, so
     * there is no reliable picture to send. Mailing one anyway would either present a
     * forced zero as a score or ask the customer to act on a sample too small to trust.
     * Note this covers only a scan that ran and came back short — a scan that threw or
     * timed out never reaches this method.
     *
     * <p>
     * <b>The account is clean.</b> "We reviewed your account and found nothing" is a
     * notification about our own activity, not about their infrastructure. Spending a
     * customer's first-day attention on it teaches them XamOps mail can be ignored,
     * which is the wrong lesson to teach before the first real exposure lands.
     *
     * <p>
     * Consequence worth knowing: a tenant whose role was never granted read access gets
     * no email at all. The failure is visible in the logs and on the dashboard, which
     * shows the blocked banner, but nothing pushes it to them.
     */
    private boolean worthSending(WarReport report, String accountName) {
        if (!report.isAssessable()) {
            log.warn("[WAR-onboarding] scan of {} did not complete — only {} of {} controls "
                    + "produced a verdict ({}). No report sent.",
                    accountName, report.getVerifiedControls(), report.getTotalControls(),
                    report.getBlockedReason() == null ? "cause unknown" : report.getBlockedReason());
            return false;
        }

        int confirmed = count(report, "FAIL") + count(report, "WARN");
        if (confirmed > 0) {
            return true;
        }

        log.info("[WAR-onboarding] {} has no confirmed exposures across {} verified control(s) "
                + "— nothing to report, skipping email",
                accountName, report.getVerifiedControls());
        return false;
    }

    private static int count(WarReport report, String status) {
        if (report.getStatusCounts() == null) {
            return 0;
        }
        return report.getStatusCounts().getOrDefault(status, 0);
    }

    // ── Send deduplication ──────────────────────────────────────────────────

    private boolean alreadySent(Long cloudAccountId) {
        return cachedDataRepository.findById(SENT_KEY_PREFIX + cloudAccountId)
                .map(CachedData::getLastUpdated)
                .filter(at -> at != null
                        && at.isAfter(LocalDateTime.now().minus(RESEND_SUPPRESSION)))
                .isPresent();
    }

    private void markSent(Long cloudAccountId, WarReport report) {
        try {
            String key = SENT_KEY_PREFIX + cloudAccountId;
            CachedData record = cachedDataRepository.findById(key).orElseGet(CachedData::new);
            record.setCacheKey(key);
            record.setJsonData(String.format(
                    "{\"score\":%d,\"riskLevel\":\"%s\",\"assessable\":%b}",
                    report.getWarScore(), report.getRiskLevel(), report.isAssessable()));
            record.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(record);
        } catch (Exception e) {
            // A missing marker risks a duplicate email, which is far better than failing
            // a delivery that already happened.
            log.warn("[WAR-onboarding] could not record send marker for account {}: {}",
                    cloudAccountId, e.getMessage());
        }
    }

}
