package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SecurityNotificationPreference;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.repository.SecurityNotificationPreferenceRepository;
import com.xammer.cloud.service.security.SecuritySeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Decides which alerts are severe enough to email, and in what order.
 *
 * <p>Applied to the <em>new</em> alerts a run found, never to the seen-set. The seen-set is
 * recorded in full regardless of threshold, so that raising the threshold for a while and
 * lowering it again does not produce a flood of months-old findings all reported as new. A
 * preference is a delivery choice; it should not rewrite the history of what we have already
 * looked at.
 *
 * <p>Shared by the AWS, GCP and Azure notifiers so the three cannot drift on what "critical
 * only" means.
 */
@Component
public class AlertDigestSeverityGate {

    private static final Logger logger = LoggerFactory.getLogger(AlertDigestSeverityGate.class);

    /** Most severe first. Unknown severities sort last, never silently to the top. */
    private static final Comparator<AlertDto> WORST_FIRST =
            Comparator.comparingInt(a -> SecuritySeverity.rank(a.getSeverity()));

    private final SecurityNotificationPreferenceRepository preferenceRepository;

    public AlertDigestSeverityGate(SecurityNotificationPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    /**
     * The alerts worth emailing to this account's client, worst first.
     *
     * <p>Sorting here rather than at the email builder is deliberate: the builder caps the
     * itemised rows, and a cap applied to an unsorted list can hide a critical behind twenty
     * informational ones.
     *
     * @return a new list; empty when nothing clears the threshold
     */
    public List<AlertDto> notifiable(CloudAccount account, List<AlertDto> newAlerts) {
        if (newAlerts == null || newAlerts.isEmpty()) {
            return Collections.emptyList();
        }
        String threshold = thresholdFor(account);
        List<AlertDto> kept = newAlerts.stream()
                .filter(a -> SecuritySeverity.atLeast(a.getSeverity(), threshold))
                .sorted(WORST_FIRST)
                .collect(Collectors.toList());

        int dropped = newAlerts.size() - kept.size();
        if (dropped > 0) {
            logger.info("[AlertGate] Held back {} of {} new alert(s) below the {} threshold",
                    dropped, newAlerts.size(), threshold);
        }
        return kept;
    }

    /**
     * The client's digest threshold, or the default when they have no preference row.
     *
     * <p>Falls back to the permissive default on any lookup failure. A database problem must
     * not silently escalate into "this customer stopped being told about their alerts".
     */
    public String thresholdFor(CloudAccount account) {
        try {
            if (account == null || account.getClient() == null || account.getClient().getId() == null) {
                return SecurityNotificationPreference.DEFAULT_DIGEST_SEVERITY_THRESHOLD;
            }
            return preferenceRepository.findById(account.getClient().getId())
                    .map(SecurityNotificationPreference::getDigestSeverityThreshold)
                    .map(SecuritySeverity::normalize)
                    .orElse(SecurityNotificationPreference.DEFAULT_DIGEST_SEVERITY_THRESHOLD);
        } catch (Exception e) {
            logger.warn("[AlertGate] Could not read digest preferences — allowing all severities: {}",
                    e.getMessage());
            return SecurityNotificationPreference.DEFAULT_DIGEST_SEVERITY_THRESHOLD;
        }
    }

    /** Worst severity present, for the subject line and the inbox badge. */
    public static String worstOf(List<AlertDto> alerts) {
        return SecuritySeverity.worstOf(alerts.stream()
                .map(AlertDto::getSeverity)
                .collect(Collectors.toList()));
    }
}
