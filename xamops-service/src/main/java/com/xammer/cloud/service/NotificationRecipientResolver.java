package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Who on a tenant receives security and WAR email, honouring each user's opt-out.
 *
 * <p>
 * Every alert path in the service resolved recipients the same way — each {@code app_user} row for
 * the client, mapped to {@code email} with a fallback to {@code username} when it looks like an
 * address — and each one carried its own copy of that stream. The comment in
 * {@code SecurityFindingNotifier} was explicit that the duplication was deliberate ("diverging
 * here would mean two different answers to 'who gets notified' in the same product"), which is
 * the argument for resolving it once rather than repeatedly. A per-user opt-out spread across
 * copies is not a visible bug when one is missed: it is a user who switched the toggle off in
 * BillOps admin and kept receiving mail from the path nobody updated.
 *
 * <h2>What this gates</h2>
 *
 * The security and SecOps family, and only that:
 *
 * <ul>
 *   <li>the WAR onboarding report ({@code WarOnboardingReportService})</li>
 *   <li>security finding notifications ({@code SecurityFindingNotifier})</li>
 *   <li>the AWS alert digest ({@code CloudGuardService})</li>
 *   <li>the GCP alert digest ({@code GcpCloudGuardService})</li>
 * </ul>
 *
 * <h2>What it deliberately does not touch</h2>
 *
 * <ul>
 *   <li><b>Cost and operational email</b> — the monthly cost digest, budget alerts and database
 *       health alerts. These resolve their own recipients and are outside the scope of this
 *       switch, which was asked for to quieten security noise specifically. Someone who is not on
 *       the security rota may still be the person who owns the bill.</li>
 *   <li><b>Login OTP, password reset, team invitations.</b> A user who muted security mail still
 *       has to be able to sign in. Routing these through a notification preference turns it into
 *       an account lockout.</li>
 *   <li><b>Ticket correspondence.</b> A reply on a ticket you opened is a conversation you
 *       started, not a broadcast, and it is addressed to one person rather than resolved here.</li>
 *   <li><b>The in-app bell and WebSocket push.</b> Muting email must not hide a finding from
 *       someone inside the product — the Security Center page and the notification inbox stay
 *       complete for every user regardless of this flag. That separation is why the flag is named
 *       for email rather than for notifications in general.</li>
 *   <li><b>Schedule-driven email</b> (FinOps report schedules, disk and agent-silence alerts).
 *       Those carry an explicit address on the schedule row rather than resolving a client's
 *       users, so there is no user to match against. Someone who requested a scheduled report by
 *       name is opted in by definition.</li>
 * </ul>
 */
@Service
public class NotificationRecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(NotificationRecipientResolver.class);

    private final UserRepository userRepository;

    public NotificationRecipientResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Security-email addresses for the tenant owning an account.
     *
     * <p>
     * Returns an empty list when the account has no client, matching what the call sites did
     * before. Callers must handle empty — it now also means "everyone here opted out", which is a
     * legitimate configuration rather than a fault.
     *
     * <p>
     * Note for callers inside a transaction: {@code CloudAccount.client} is a LAZY
     * {@code @ManyToOne}, so this must be called while the session is open. That is why
     * {@code WarOnboardingReportService} resolves recipients inside {@code deliverReport} rather
     * than after the scan returns.
     */
    public List<String> emailsForAccount(CloudAccount account) {
        if (account == null || account.getClient() == null || account.getClient().getId() == null) {
            return List.of();
        }
        return emailsForClient(account.getClient().getId());
    }

    /**
     * Security-email addresses for a client, excluding users who have the email switched off.
     *
     * <p>
     * Never throws. A failure to resolve recipients was swallowed independently at each original
     * call site and that behaviour is preserved: an alert path must not die because a lookup
     * failed, it must log and send to nobody, which is visible in the logs and retried on the next
     * pass.
     */
    public List<String> emailsForClient(Long clientId) {
        if (clientId == null) {
            return List.of();
        }
        try {
            List<User> users = userRepository.findByClientId(clientId);
            List<String> recipients = users.stream()
                    .filter(User::isEmailNotificationsEnabled)
                    .map(NotificationRecipientResolver::addressOf)
                    .filter(email -> email != null)
                    .distinct()
                    .collect(Collectors.toList());

            // Logged only when it actually happened, because "this client has no addressable
            // users" and "every user opted out" produce the same empty list at the call site and
            // lead to very different conversations with a customer asking why mail stopped.
            if (log.isDebugEnabled()) {
                long optedOut = users.stream().filter(u -> !u.isEmailNotificationsEnabled()).count();
                if (optedOut > 0) {
                    log.debug("[Recipients] Client {} — {} recipient(s), {} user(s) opted out of security email",
                            clientId, recipients.size(), optedOut);
                }
            }
            return recipients;
        } catch (Exception e) {
            log.warn("[Recipients] Could not resolve security email recipients for client {}: {}",
                    clientId, e.getMessage());
            return List.of();
        }
    }

    /**
     * A user's mail address, or null if they have none usable.
     *
     * <p>
     * {@code User.email} is nullable and older rows carry the address in {@code username}
     * instead, hence the fallback and the {@code contains("@")} test that goes with it. Kept
     * exactly as the original copies had it: changing this would change who is reachable across
     * every security alert in the product at once.
     */
    private static String addressOf(User user) {
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail();
        }
        if (user.getUsername() != null && user.getUsername().contains("@")) {
            return user.getUsername();
        }
        return null;
    }
}
