package com.xammer.cloud.service;

import com.xammer.cloud.domain.AccountObservabilityConfig;
import com.xammer.cloud.domain.AgentSilenceAlertState;
import com.xammer.cloud.dto.AgentHealthDto;
import com.xammer.cloud.repository.AccountObservabilityConfigRepository;
import com.xammer.cloud.repository.AgentSilenceAlertStateRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.FinOpsReportScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects agents that have gone silent and notifies the account's subscribers.
 *
 * Everything else in the observability UI is passive — a banner only helps someone who
 * happens to open the page. An agent that stops shipping is exactly the failure a user needs
 * told about, because until they know, the dashboards simply look empty.
 *
 * Alerts fire on transitions: silent hosts are notified once and recorded, and a recovery
 * notice is sent when they come back. Re-notifying every scan for a host that has been down
 * for a week trains people to filter the alert out.
 */
@Service
public class AgentSilenceAlertService {

    private static final Logger logger = LoggerFactory.getLogger(AgentSilenceAlertService.class);

    private final AccountObservabilityConfigRepository accountConfigRepo;
    private final AgentSilenceAlertStateRepository alertStateRepo;
    private final CloudAccountRepository cloudAccountRepo;
    private final FinOpsReportScheduleRepository scheduleRepository;
    private final AgentHealthService agentHealthService;
    private final EmailService emailService;

    public AgentSilenceAlertService(AccountObservabilityConfigRepository accountConfigRepo,
                                    AgentSilenceAlertStateRepository alertStateRepo,
                                    CloudAccountRepository cloudAccountRepo,
                                    FinOpsReportScheduleRepository scheduleRepository,
                                    AgentHealthService agentHealthService,
                                    EmailService emailService) {
        this.accountConfigRepo = accountConfigRepo;
        this.alertStateRepo = alertStateRepo;
        this.cloudAccountRepo = cloudAccountRepo;
        this.scheduleRepository = scheduleRepository;
        this.agentHealthService = agentHealthService;
        this.emailService = emailService;
    }

    /** Scans every provisioned account. One bad account must not stop the rest. */
    public void runScan() {
        List<AccountObservabilityConfig> configs = accountConfigRepo.findAll();
        logger.info("[AgentSilence] Scanning {} provisioned observability account(s)", configs.size());

        for (AccountObservabilityConfig config : configs) {
            if (config.getO2OrgId() == null) continue; // half-provisioned — nothing to query
            try {
                scanAccount(config);
            } catch (Exception e) {
                logger.error("[AgentSilence] Scan failed for account {}: {}",
                        config.getProviderAccountId(), e.getMessage(), e);
            }
        }
    }

    /** Package-private for direct test access. */
    @Transactional
    void scanAccount(AccountObservabilityConfig config) {
        Long clientId = config.getClientId();
        String accountId = config.getProviderAccountId();

        List<AgentHealthDto> agents = agentHealthService.listAgents(clientId, accountId);
        if (agents.isEmpty()) {
            // No roster at all usually means observability was never set up for this account,
            // not that every agent died. Alerting here would be noise.
            return;
        }

        Map<String, AgentSilenceAlertState> openByHost = new HashMap<>();
        for (AgentSilenceAlertState state : alertStateRepo.findByClientIdAndResolvedAtIsNull(clientId)) {
            if (accountId != null && !accountId.equals(state.getProviderAccountId())) continue;
            openByHost.put(state.getHostName(), state);
        }

        List<AgentHealthDto> newlySilent = new ArrayList<>();
        List<AgentHealthDto> recovered = new ArrayList<>();
        Set<String> seenHosts = new HashSet<>();

        for (AgentHealthDto agent : agents) {
            seenHosts.add(agent.getHostName());
            boolean silent = agent.getStatus() == AgentHealthDto.Status.OFFLINE
                    || agent.getStatus() == AgentHealthDto.Status.GONE;
            AgentSilenceAlertState open = openByHost.get(agent.getHostName());

            if (silent && open == null) {
                alertStateRepo.save(newState(clientId, accountId, agent));
                newlySilent.add(agent);
            } else if (!silent && open != null) {
                open.setResolvedAt(LocalDateTime.now());
                alertStateRepo.save(open);
                recovered.add(agent);
            }
        }

        // An open alert for a host no longer in the roster has aged past the lookback window.
        // Close it rather than leaving a row that can never resolve.
        openByHost.forEach((host, state) -> {
            if (!seenHosts.contains(host)) {
                state.setResolvedAt(LocalDateTime.now());
                alertStateRepo.save(state);
            }
        });

        if (!newlySilent.isEmpty() || !recovered.isEmpty()) {
            notify(config, newlySilent, recovered);
        }
    }

    private AgentSilenceAlertState newState(Long clientId, String accountId, AgentHealthDto agent) {
        AgentSilenceAlertState state = new AgentSilenceAlertState();
        state.setClientId(clientId);
        state.setProviderAccountId(accountId);
        state.setHostName(agent.getHostName());
        state.setStatus(agent.getStatus().name());
        state.setAlertedAt(LocalDateTime.now());
        if (agent.getLastSeenMs() > 0) {
            state.setLastSeenAt(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(agent.getLastSeenMs()), ZoneId.systemDefault()));
        }
        return state;
    }

    private void notify(AccountObservabilityConfig config,
                        List<AgentHealthDto> newlySilent,
                        List<AgentHealthDto> recovered) {
        String accountLabel = config.getAccountName() != null
                ? config.getAccountName()
                : String.valueOf(config.getProviderAccountId());

        List<String> recipients = cloudAccountRepo.findByProviderAccountId(config.getProviderAccountId())
                .map(acc -> scheduleRepository.findActiveEmailsByAccountId(acc.getId()))
                .orElseGet(List::of);

        if (recipients.isEmpty()) {
            // Still worth a log line: the condition is real even when nobody is subscribed.
            logger.warn("[AgentSilence] {} agent(s) silent and {} recovered for {}, but no subscribers to notify",
                    newlySilent.size(), recovered.size(), accountLabel);
            return;
        }

        String subject = newlySilent.isEmpty()
                ? "XamOps: agent reporting restored — " + accountLabel
                : newlySilent.size() + " agent(s) stopped reporting — " + accountLabel;
        String html = buildEmail(accountLabel, newlySilent, recovered);

        for (String email : recipients) {
            try {
                emailService.sendHtmlEmail(email, subject, html);
            } catch (Exception e) {
                logger.error("[AgentSilence] Could not email {}: {}", email, e.getMessage());
            }
        }
        logger.info("[AgentSilence] Notified {} recipient(s) for {} ({} silent, {} recovered)",
                recipients.size(), accountLabel, newlySilent.size(), recovered.size());
    }

    /** Package-private so the wording is directly testable. */
    String buildEmail(String accountLabel,
                      List<AgentHealthDto> newlySilent,
                      List<AgentHealthDto> recovered) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;font-size:14px;color:#111\">");
        sb.append("<h2 style=\"font-size:16px;margin:0 0 12px\">Observability agent status — ")
          .append(escape(accountLabel)).append("</h2>");

        if (!newlySilent.isEmpty()) {
            sb.append("<p style=\"margin:0 0 8px\">These hosts have stopped sending metrics. "
                    + "Dashboards for them will appear empty until reporting resumes.</p><ul>");
            for (AgentHealthDto agent : newlySilent) {
                sb.append("<li><strong>").append(escape(agent.getHostName())).append("</strong> — silent for ")
                  .append(formatAge(agent.getAgeMs()));
                // The actionable distinction: a live collector means the agent is running and
                // its metrics pipeline is broken, which is a config fix rather than a restart.
                if (agent.isCollectorAlive()) {
                    sb.append(" (agent process is alive — its metrics pipeline looks misconfigured)");
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }

        if (!recovered.isEmpty()) {
            sb.append("<p style=\"margin:12px 0 8px\">Recovered:</p><ul>");
            for (AgentHealthDto agent : recovered) {
                sb.append("<li>").append(escape(agent.getHostName())).append("</li>");
            }
            sb.append("</ul>");
        }

        sb.append("<p style=\"margin:12px 0 0;color:#666;font-size:12px\">"
                + "Check agent status under Observability in XamOps.</p></div>");
        return sb.toString();
    }

    static String formatAge(long ms) {
        if (ms == Long.MAX_VALUE || ms < 0) return "an unknown period";
        long mins = ms / 60_000;
        if (mins < 60) return mins + " min";
        long hours = mins / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour" : " hours");
        long days = hours / 24;
        return days + (days == 1 ? " day" : " days");
    }

    /** Host names come from telemetry, so they are untrusted input in an HTML email. */
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
