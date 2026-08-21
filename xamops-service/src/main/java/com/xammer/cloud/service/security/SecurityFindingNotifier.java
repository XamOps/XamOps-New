package com.xammer.cloud.service.security;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.config.multitenancy.TenantContext;
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
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Notifies about new security findings: in-app, by email, and over the socket.
 *
 * <p>
 * <b>Why a scheduled sweep and not a call from the ingest path.</b> Two reasons, both learned
 * from the shape of the data rather than chosen for tidiness. First, coalescing: findings arrive
 * in bursts — an active GuardDuty incident or a Config rule re-evaluating across an estate
 * produces dozens within a minute — and notifying per finding turns one incident into a mail
 * flood that buries the message that mattered. A window collapses a burst into one email.
 * Second, durability: notifying inline means a crash between "row written" and "email sent"
 * loses the alert permanently, whereas a sweep driven by {@code notified_at IS NULL} picks it up
 * on the next pass.
 *
 * <p>
 * <b>Delivery is best-effort, storage is not.</b> The finding is already durable before this
 * class runs. Everything here can fail — SMTP down, socket disconnected, Redis unavailable — and
 * the worst outcome is a delayed or missing notification against a finding that is still on the
 * page. So nothing here is allowed to throw into the scheduler.
 */
@Service
public class SecurityFindingNotifier {

    private static final Logger log = LoggerFactory.getLogger(SecurityFindingNotifier.class);

    /**
     * User-targeted destination for live finding pushes.
     *
     * <p>
     * {@code /user/queue/...} rather than a {@code /topic/...} broadcast, and that is not
     * incidental. Every existing topic in this application is broadcast with no tenant filter,
     * and {@code /topic/dashboard/{awsAccountId}/inventory} is guessable from an account id.
     * Publishing security findings that way would hand one customer another customer's live
     * vulnerabilities. User destinations route only to sessions carrying a matching principal,
     * which is the one authenticated pattern the codebase has.
     */
    public static final String USER_QUEUE = "/queue/security-findings";

    private final CloudAccountRepository cloudAccountRepository;
    private final CloudSecurityFindingRepository findingRepository;
    private final SecurityNotificationRepository notificationRepository;
    private final SecurityNotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final SecurityFindingEmailBuilder emailBuilder;
    private final RedisCacheService redisCache;
    private final SimpMessagingTemplate messagingTemplate;
    private final SchedulingEnvironmentConfig envConfig;

    /** See {@link #markNotified}: the update is a self-invocation, so annotations do not apply. */
    private final TransactionTemplate transactionTemplate;

    @Value("${xamops.security.notify.enabled:false}")
    private boolean enabled;

    /**
     * Coalescing window. Five minutes is short enough that a critical finding is not sitting
     * unread for long, and long enough that a burst arrives as one email.
     */
    @Value("${xamops.security.notify.interval-ms:300000}")
    private long intervalMs;

    /** Hard ceiling on findings carried in one batch, independent of the email's row cap. */
    @Value("${xamops.security.notify.max-batch:100}")
    private int maxBatch;

    /** Same reasoning as the inventory scheduler: a slow pass skips a beat, never queues. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SecurityFindingNotifier(CloudAccountRepository cloudAccountRepository,
            CloudSecurityFindingRepository findingRepository,
            SecurityNotificationRepository notificationRepository,
            SecurityNotificationPreferenceRepository preferenceRepository,
            UserRepository userRepository,
            EmailService emailService,
            SecurityFindingEmailBuilder emailBuilder,
            RedisCacheService redisCache,
            SimpMessagingTemplate messagingTemplate,
            SchedulingEnvironmentConfig envConfig,
            PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.cloudAccountRepository = cloudAccountRepository;
        this.findingRepository = findingRepository;
        this.notificationRepository = notificationRepository;
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.emailBuilder = emailBuilder;
        this.redisCache = redisCache;
        this.messagingTemplate = messagingTemplate;
        this.envConfig = envConfig;
    }

    // ── Sweep ───────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${xamops.security.notify.interval-ms:300000}",
            initialDelayString = "${xamops.security.notify.initial-delay-ms:180000}")
    public void notifyPendingFindings() {
        if (!enabled || !envConfig.isSchedulingEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("[SecNotify] Previous notification pass still running — skipping this cycle");
            return;
        }
        try {
            TenantContext.setCurrentTenant(envConfig.getCurrentTenantId());
            try {
                for (CloudAccount account : cloudAccountRepository.findAll()) {
                    if (!isNotifiableAwsAccount(account)) {
                        continue;
                    }
                    try {
                        notifyForAccount(account);
                    } catch (Exception e) {
                        // One account's failure must never end the pass for the others.
                        log.error("[SecNotify] Notification failed for account {}: {}",
                                account.getAwsAccountId(), e.getMessage());
                    }
                }
            } finally {
                TenantContext.clear();
            }
        } catch (Exception e) {
            // Never rethrow: an exception escaping a @Scheduled method suppresses all future
            // executions, which would silently disable notification entirely.
            log.error("[SecNotify] Notification pass aborted: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    private boolean isNotifiableAwsAccount(CloudAccount account) {
        return "AWS".equals(account.getProvider())
                && account.getId() != null
                && account.getAwsAccountId() != null
                && !account.getAwsAccountId().isBlank()
                && account.getClient() != null
                && account.getClient().getId() != null;
    }

    // ── Per account ─────────────────────────────────────────────────────────

    /** Visible for testing; drives one account through the whole notification decision. */
    void notifyForAccount(CloudAccount account) {
        Long clientId = account.getClient().getId();
        SecurityNotificationPreference prefs = preferencesFor(clientId);

        List<String> severities = severitiesAtOrAbove(prefs.getSeverityThreshold());
        if (severities.isEmpty()) {
            return;
        }

        List<CloudSecurityFinding> pending =
                findingRepository.findUnnotified(account.getId(), severities);
        if (pending.isEmpty()) {
            return;
        }
        if (pending.size() > maxBatch) {
            // Oldest first, so a flood is drained in order across successive windows rather
            // than the newest findings starving the ones that arrived while we were capped.
            pending = pending.subList(0, maxBatch);
        }

        boolean wantsEmail = prefs.isEmailEnabled() && !recipientsFor(clientId).isEmpty();

        // Cap checked before anything is written. Returning early rather than writing the inbox
        // row and skipping only the email is deliberate: if the row were written now and the
        // findings left unnotified, the next window would write a second row for the same
        // findings and the inbox would fill with duplicates.
        if (wantsEmail && !underHourlyCap(account.getAwsAccountId(), prefs.getMaxEmailsPerHour())) {
            log.warn("[SecNotify] Account {} hit its hourly email cap ({}) — {} finding(s) deferred "
                    + "to the next window. They remain visible on the Security Center page.",
                    account.getAwsAccountId(), prefs.getMaxEmailsPerHour(), pending.size());
            return;
        }

        String accountName = account.getAccountName() != null
                ? account.getAccountName()
                : account.getAwsAccountId();
        String worst = worstSeverity(pending);

        boolean emailSent = false;
        if (wantsEmail) {
            emailSent = sendEmail(clientId, pending, accountName, account.getAwsAccountId());
            if (emailSent) {
                recordEmailSent(account.getAwsAccountId());
            }
        }

        if (prefs.isInboxEnabled()) {
            writeInboxRow(account, clientId, pending, worst, emailSent, accountName);
        }

        pushToClientUsers(clientId, account, pending, worst);

        // Marked last, and only once. A finding whose delivery failed everywhere is still
        // marked, because the alternative is retrying a broken SMTP configuration every five
        // minutes forever against a finding the user can already see on the page.
        markNotified(pending);

        log.info("[SecNotify] Account {} — notified {} finding(s) at or above {} (email={}, worst={})",
                account.getAwsAccountId(), pending.size(), prefs.getSeverityThreshold(),
                emailSent, worst);
    }

    // ── Preferences ─────────────────────────────────────────────────────────

    /**
     * Preferences for a client, falling back to defaults.
     *
     * <p>
     * An absent row means defaults, never "notifications off". Treating absence as off would
     * silence the feature for every existing client — a failure that looks exactly like the
     * feature not working, with nothing in the logs to say why.
     */
    public SecurityNotificationPreference preferencesFor(Long clientId) {
        return preferenceRepository.findById(clientId)
                .orElseGet(() -> SecurityNotificationPreference.defaults(clientId));
    }

    /** Severities at or above a threshold, in the normalised vocabulary. */
    static List<String> severitiesAtOrAbove(String threshold) {
        String normalised = SecuritySeverity.normalize(threshold);
        return SecuritySeverity.RANKED.stream()
                .filter(s -> SecuritySeverity.atLeast(s, normalised))
                .collect(Collectors.toList());
    }

    private static String worstSeverity(List<CloudSecurityFinding> findings) {
        return SecuritySeverity.worstOf(findings.stream()
                .map(CloudSecurityFinding::getSeverity)
                .collect(Collectors.toList()));
    }

    // ── Rate cap ────────────────────────────────────────────────────────────

    private static String capKey(String awsAccountId) {
        return "notif::secfinding::sent::" + awsAccountId;
    }

    /**
     * Whether this account may send another email in the current hour.
     *
     * <p>
     * Best-effort by construction. The counter is read, compared and written rather than
     * incremented atomically, so two replicas racing can let one extra email through. That is
     * the right trade here: the cap exists to prevent a flood of hundreds, and being
     * occasionally off by one is immaterial, whereas taking a distributed lock on the
     * notification path would add a failure mode that could block alerts entirely.
     *
     * <p>
     * Redis being unavailable returns true — unmetered rather than silent. A cap is a comfort
     * feature; suppressing security alerts because a cache is down is not an acceptable
     * failure mode.
     */
    private boolean underHourlyCap(String awsAccountId, Integer maxPerHour) {
        int limit = maxPerHour == null ? SecurityNotificationPreference.DEFAULT_MAX_EMAILS_PER_HOUR
                : maxPerHour;
        if (limit <= 0) {
            return true;
        }
        try {
            Optional<Integer> sent = redisCache.get(capKey(awsAccountId), Integer.class);
            return sent.orElse(0) < limit;
        } catch (Exception e) {
            log.warn("[SecNotify] Could not read the email cap for {} — allowing the send: {}",
                    awsAccountId, e.getMessage());
            return true;
        }
    }

    private void recordEmailSent(String awsAccountId) {
        try {
            int sent = redisCache.get(capKey(awsAccountId), Integer.class).orElse(0);
            // 60-minute TTL makes this a rolling window that needs no cleanup: the counter
            // simply expires rather than being reset by a job that could fail to run.
            redisCache.put(capKey(awsAccountId), sent + 1, 60);
        } catch (Exception e) {
            log.debug("[SecNotify] Could not record the email send for {}: {}",
                    awsAccountId, e.getMessage());
        }
    }

    // ── Email ───────────────────────────────────────────────────────────────

    /**
     * Recipient addresses for a client.
     *
     * <p>
     * Copied from the resolution {@code CloudGuardService} already uses, including the fallback
     * to {@code username} when it looks like an address, so security email reaches exactly the
     * people the existing alert digest reaches. Diverging here would mean two different answers
     * to "who gets notified" in the same product.
     */
    private List<String> recipientsFor(Long clientId) {
        try {
            return userRepository.findByClientId(clientId).stream()
                    .map(u -> (u.getEmail() != null && !u.getEmail().isBlank())
                            ? u.getEmail()
                            : (u.getUsername() != null && u.getUsername().contains("@")
                                    ? u.getUsername() : null))
                    .filter(e -> e != null)
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[SecNotify] Could not resolve recipients for client {}: {}",
                    clientId, e.getMessage());
            return List.of();
        }
    }

    /** @return true when at least one recipient was successfully sent to */
    private boolean sendEmail(Long clientId, List<CloudSecurityFinding> findings,
            String accountName, String awsAccountId) {
        List<String> recipients = recipientsFor(clientId);
        if (recipients.isEmpty()) {
            return false;
        }
        String subject = emailBuilder.subjectFor(findings, accountName);
        String html = emailBuilder.build(findings, accountName, awsAccountId);

        boolean any = false;
        for (String recipient : recipients) {
            try {
                emailService.sendHtmlEmail(recipient, subject, html);
                any = true;
            } catch (Exception e) {
                // Per recipient, so one bad address does not deny the others. "At least one
                // succeeded" is what counts as sent, because the alternative is resending to
                // everybody to reach the one who failed.
                log.error("[SecNotify] Could not email {} about {} findings on {}: {}",
                        recipient, findings.size(), awsAccountId, e.getMessage());
            }
        }
        return any;
    }

    // ── Inbox ───────────────────────────────────────────────────────────────

    private void writeInboxRow(CloudAccount account, Long clientId,
            List<CloudSecurityFinding> findings, String worst, boolean emailSent,
            String accountName) {
        try {
            SecurityNotification row = new SecurityNotification();
            row.setClientId(clientId);
            row.setCloudAccountId(account.getId());
            row.setAwsAccountId(account.getAwsAccountId());
            row.setSeverity(worst);
            row.setFindingCount(findings.size());
            row.setEmailSent(emailSent);
            row.setCreatedAt(Instant.now());
            row.setTitle(findings.size() == 1
                    ? findings.get(0).getTitle()
                    : findings.size() + " new " + SecuritySeverity.toTitleCase(worst)
                            + " security findings on " + accountName);
            row.setMessage(summarise(findings));
            notificationRepository.save(row);
        } catch (Exception e) {
            log.warn("[SecNotify] Could not write the inbox row for client {}: {}",
                    clientId, e.getMessage());
        }
    }

    /** A few lines a user can read in the bell dropdown without opening anything. */
    private String summarise(List<CloudSecurityFinding> findings) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(findings.size(), 5);
        for (int i = 0; i < shown; i++) {
            CloudSecurityFinding f = findings.get(i);
            sb.append(f.getSeverity()).append(": ").append(f.getTitle());
            if (f.getResourceArn() != null && !f.getResourceArn().isBlank()) {
                sb.append(" (").append(f.getResourceArn()).append(")");
            }
            sb.append('\n');
        }
        if (findings.size() > shown) {
            sb.append("and ").append(findings.size() - shown).append(" more.");
        }
        return sb.toString();
    }

    // ── WebSocket ───────────────────────────────────────────────────────────

    /**
     * Pushes to each of the client's users individually.
     *
     * <p>
     * Only sessions that authenticated on CONNECT carry a principal, so only those receive the
     * push; everyone else picks the findings up on their next page load or poll. That is the
     * same graceful degradation the call queue already relies on, and it is the correct default
     * for this feature — a security finding that arrives one page-refresh late is a minor
     * annoyance, whereas one delivered to the wrong tenant is a breach.
     *
     * <p>
     * The broker is the in-memory simple broker, so this reaches only sessions on this replica.
     * The REST endpoints are what make the feature correct regardless.
     */
    private void pushToClientUsers(Long clientId, CloudAccount account,
            List<CloudSecurityFinding> findings, String worst) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "security-findings");
        payload.put("accountId", account.getAwsAccountId());
        payload.put("accountName", account.getAccountName());
        payload.put("count", findings.size());
        payload.put("severity", worst);
        // Titles only. The full finding, including remediation text and resource ARNs, stays
        // behind the authenticated REST endpoint; the push exists to prompt a refresh.
        payload.put("titles", findings.stream()
                .limit(5)
                .map(CloudSecurityFinding::getTitle)
                .collect(Collectors.toList()));

        List<User> users;
        try {
            users = userRepository.findByClientId(clientId);
        } catch (Exception e) {
            log.debug("[SecNotify] Could not resolve users of client {} for push: {}",
                    clientId, e.getMessage());
            return;
        }

        for (User user : users) {
            if (user.getId() == null) {
                continue;
            }
            try {
                messagingTemplate.convertAndSendToUser(
                        String.valueOf(user.getId()), USER_QUEUE, payload);
            } catch (Exception e) {
                log.debug("[SecNotify] Could not push to user {}: {}", user.getId(), e.getMessage());
            }
        }
    }

    // ── Marking ─────────────────────────────────────────────────────────────

    /**
     * Records that these findings have been notified.
     *
     * <p>
     * Wrapped in a {@link TransactionTemplate} rather than annotated {@code @Transactional}, and
     * that is not a style choice. {@code markNotified} is a {@code @Modifying} JPQL update, which
     * requires an active transaction, and this method is called from {@link #notifyForAccount} in
     * the same class — Spring's proxy-based transaction advice cannot intercept a self-invocation,
     * so the annotation would be silently ignored.
     *
     * <p>
     * The symptom that exposed it is worth recording, because it was expensive rather than merely
     * wrong. The update threw {@code TransactionRequiredException} <em>after</em> the email had
     * already gone out, so the finding stayed {@code notified_at IS NULL} and the next sweep sixty
     * seconds later selected it again and emailed everyone a second time. A failure that logs
     * politely and re-sends on a timer is worse than a loud one. {@code AwsInventoryReconciler}
     * carries the same {@code TransactionTemplate} for the same reason.
     */
    private void markNotified(List<CloudSecurityFinding> findings) {
        List<Long> ids = new ArrayList<>(findings.size());
        for (CloudSecurityFinding f : findings) {
            if (f.getId() != null) {
                ids.add(f.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        try {
            transactionTemplate.execute(status -> findingRepository.markNotified(ids, Instant.now()));
        } catch (Exception e) {
            // Deliberately loud. Leaving these unmarked means the next sweep re-notifies, so this
            // is the line that explains a customer receiving the same alert repeatedly. The
            // per-account hourly cap bounds the damage, which is the only reason this is
            // recoverable rather than an incident.
            log.error("[SecNotify] Could not mark {} finding(s) notified — the next sweep WILL "
                    + "re-notify them, bounded by the hourly email cap: {}", ids.size(), e.getMessage());
        }
    }
}
