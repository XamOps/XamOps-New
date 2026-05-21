package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbHealthAlert;
import com.xammer.cloud.domain.DbHealthSnapshot;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.dbops.AgentDiagnosticDto;
import com.xammer.cloud.dto.dbops.AgentInstanceMetricsDto;
import com.xammer.cloud.dto.dbops.DbMetricsDto;
import com.xammer.cloud.repository.DbHealthAlertRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DbHealthAlertService {

    private static final Logger logger = LoggerFactory.getLogger(DbHealthAlertService.class);

    private final DbHealthAlertRepository alertRepository;
    private final EmailService            emailService;
    private final AlertEmailBuilder       alertEmailBuilder;
    private final UserRepository          userRepository;

    public DbHealthAlertService(DbHealthAlertRepository alertRepository,
                                EmailService emailService,
                                AlertEmailBuilder alertEmailBuilder,
                                UserRepository userRepository) {
        this.alertRepository   = alertRepository;
        this.emailService      = emailService;
        this.alertEmailBuilder = alertEmailBuilder;
        this.userRepository    = userRepository;
    }

    @Transactional
    public void evaluate(CloudAccount account, DbHealthSnapshot snapshot,
                         DbMetricsDto metrics, AgentInstanceMetricsDto agentMetrics,
                         int slowQueriesCount, List<AgentDiagnosticDto> diagnostics) {

        List<AlertCondition> conditions = buildConditions(snapshot, metrics, agentMetrics,
            slowQueriesCount, diagnostics);
        List<AlertDto> newAlerts = new ArrayList<>();

        for (AlertCondition cond : conditions) {
            Optional<DbHealthAlert> existing = alertRepository
                .findFirstByAccountIdAndInstanceIdAndAlertTypeAndIsActiveTrue(
                    account.getId(), snapshot.getInstanceId(), cond.type);

            if (cond.triggered) {
                if (existing.isEmpty()) {
                    DbHealthAlert alert = new DbHealthAlert();
                    alert.setAccountId(account.getId());
                    alert.setInstanceId(snapshot.getInstanceId());
                    alert.setAlertType(cond.type);
                    alert.setSeverity(cond.severity);
                    alert.setMessage(cond.message);
                    alertRepository.save(alert);

                    AlertDto dto = new AlertDto();
                    dto.setId(snapshot.getInstanceId());
                    dto.setName(snapshot.getInstanceId());
                    dto.setDescription(cond.message);
                    dto.setStatus(cond.severity);
                    dto.setType(cond.type);
                    dto.setService("DBOps");
                    newAlerts.add(dto);
                    logger.info("DbHealthAlert: NEW {} {} for {}", cond.severity, cond.type, snapshot.getInstanceId());
                }
            } else {
                existing.ifPresent(a -> {
                    a.setActive(false);
                    a.setResolvedAt(LocalDateTime.now());
                    alertRepository.save(a);
                    logger.info("DbHealthAlert: RESOLVED {} for {}", cond.type, snapshot.getInstanceId());
                });
            }
        }

        if (!newAlerts.isEmpty()) sendAlertEmail(account, newAlerts);
    }

    private void sendAlertEmail(CloudAccount account, List<AlertDto> alerts) {
        List<String> emails = resolveEmails(account);
        if (emails.isEmpty()) {
            logger.warn("DbHealthAlert: no recipients for account {}", account.getId());
            return;
        }
        String accountName = account.getClient() != null ? account.getClient().getName() : String.valueOf(account.getId());
        String html    = alertEmailBuilder.buildAlertDigestEmail(alerts, accountName, String.valueOf(account.getId()));
        String subject = "[XamOps] Database Health Alert — " + alerts.size() + " issue(s) detected";
        for (String email : emails) {
            try {
                emailService.sendHtmlEmail(email, subject, html);
                logger.info("DbHealthAlert: email sent to {}", email);
            } catch (Exception e) {
                logger.warn("DbHealthAlert: email failed to {}: {}", email, e.getMessage());
            }
        }
    }

    private List<String> resolveEmails(CloudAccount account) {
        if (account.getClient() == null) return List.of();
        return userRepository.findByClientId(account.getClient().getId()).stream()
            .map(u -> u.getEmail() != null && !u.getEmail().isBlank() ? u.getEmail()
                    : (u.getUsername() != null && u.getUsername().contains("@") ? u.getUsername() : null))
            .filter(Objects::nonNull).distinct().collect(Collectors.toList());
    }

    // ── Condition builders ────────────────────────────────────────────────────

    private static class AlertCondition {
        String type, severity, message; boolean triggered;
        AlertCondition(String t, String s, String m, boolean tr) { type = t; severity = s; message = m; triggered = tr; }
    }

    private List<AlertCondition> buildConditions(DbHealthSnapshot snap, DbMetricsDto m,
            AgentInstanceMetricsDto am, int slowCount, List<AgentDiagnosticDto> diags) {

        List<AlertCondition> conds = new ArrayList<>();
        String id = snap.getInstanceId();

        // DB_HEALTH_SCORE — single condition, severity based on score
        boolean scoreTriggered = snap.getScore() < 70;
        String  scoreSeverity  = snap.getScore() < 50 ? "CRITICAL" : "WARNING";
        conds.add(new AlertCondition("DB_HEALTH_SCORE", scoreSeverity,
            "Health score dropped to " + snap.getScore() + " on " + id
                + (snap.getScore() < 50 ? " — critical issues detected" : ""),
            scoreTriggered));

        // DB_CONNECTION_SATURATION
        double connPct = (am != null && am.getMaxConnections() > 0)
            ? (double) am.getCurrentConnections() / am.getMaxConnections() * 100 : 0;
        conds.add(new AlertCondition("DB_CONNECTION_SATURATION", "CRITICAL",
            "Connection pool at " + Math.round(connPct) + "% on " + id, connPct > 80));

        // DB_SLOW_QUERIES
        conds.add(new AlertCondition("DB_SLOW_QUERIES", "WARNING",
            slowCount + " slow queries detected on " + id, slowCount > 20));

        // DB_REPLICA_LAG
        double lag = m != null ? m.getReplicaLagSeconds() : 0;
        conds.add(new AlertCondition("DB_REPLICA_LAG", "WARNING",
            "Replica lag " + Math.round(lag) + "s on " + id, lag > 30));

        // DB_SECURITY
        boolean hasSecurity = snap.getIssuesJson() != null && snap.getIssuesJson().contains("security failure");
        conds.add(new AlertCondition("DB_SECURITY", "CRITICAL",
            "Security check failures detected on " + id, hasSecurity));

        // DB_BLOAT
        boolean hasBloat = snap.getIssuesJson() != null && snap.getIssuesJson().contains("high bloat");
        conds.add(new AlertCondition("DB_BLOAT", "WARNING",
            "Table bloat detected on " + id, hasBloat));

        // DB_PRIVILEGE_ERROR
        boolean hasPrivError = diags.stream().anyMatch(d ->
            d.getErrorType() != null && !d.getErrorType().isEmpty()
            && d.getDbHost() != null
            && (d.getDbHost().contains(id) || (id != null && id.contains(d.getDbHost().split(":")[0]))));
        conds.add(new AlertCondition("DB_PRIVILEGE_ERROR", "WARNING",
            "Agent privilege or connection error on " + id, hasPrivError));

        return conds;
    }
}
