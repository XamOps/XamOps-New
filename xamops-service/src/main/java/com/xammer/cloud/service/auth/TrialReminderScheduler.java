package com.xammer.cloud.service.auth;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily job that drives the trial lifecycle:
 *  - flips past-due ACTIVE trials to EXPIRED,
 *  - sends the 5-day reminder once (guarded by reminder5SentAt),
 *  - sends the last-day email once (guarded by finalDaySentAt).
 *
 * Runs at 09:00 IST. Each send is guarded so it fires at most once per window;
 * a failure for one client never stops processing of the rest.
 */
@Service
@Slf4j
public class TrialReminderScheduler {

    @Autowired private ClientRepository clientRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TrialService trialService;
    @Autowired private TrialEmailService trialEmailService;

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    public void processTrials() {
        List<Client> trials = clientRepository.findByPlanTypeAndTrialStatus(
                TrialService.PLAN_TRIAL, TrialService.STATUS_ACTIVE);
        log.info("[TrialReminder] Processing {} active trial clients", trials.size());

        for (Client client : trials) {
            try {
                processOne(client);
            } catch (Exception e) {
                log.error("[TrialReminder] Failed for client {}: {}", client.getId(), e.getMessage());
            }
        }
    }

    private void processOne(Client client) {
        if (client.getTrialEndAt() == null) return;

        LocalDateTime now = LocalDateTime.now();

        // Expire past-due trials.
        if (now.isAfter(client.getTrialEndAt())) {
            client.setTrialStatus(TrialService.STATUS_EXPIRED);
            clientRepository.save(client);
            trialService.invalidateCache(client.getId());
            log.info("[TrialReminder] Client {} trial expired", client.getId());
            return;
        }

        long daysRemaining = trialService.daysRemaining(client);
        String email = recipientEmail(client);
        String name = recipientName(client);
        if (email == null) {
            log.warn("[TrialReminder] No recipient email for client {}", client.getId());
            return;
        }

        // 5-day reminder (fires when 5 or fewer days remain and not yet sent).
        if (daysRemaining <= 5 && daysRemaining > 1 && client.getReminder5SentAt() == null) {
            trialEmailService.sendFiveDayReminder(email, name, daysRemaining);
            client.setReminder5SentAt(now);
            clientRepository.save(client);
            log.info("[TrialReminder] 5-day reminder sent to client {}", client.getId());
        }

        // Last-day email (fires when 1 or fewer days remain and not yet sent).
        if (daysRemaining <= 1 && client.getFinalDaySentAt() == null) {
            trialEmailService.sendLastDay(email, name);
            client.setFinalDaySentAt(now);
            clientRepository.save(client);
            log.info("[TrialReminder] Last-day email sent to client {}", client.getId());
        }
    }

    private String recipientEmail(Client client) {
        if (client.getEmail() != null && !client.getEmail().isBlank()) {
            return client.getEmail();
        }
        List<User> users = userRepository.findByClientId(client.getId());
        return users.stream().map(User::getEmail).filter(e -> e != null && !e.isBlank())
                .findFirst().orElse(null);
    }

    private String recipientName(Client client) {
        List<User> users = userRepository.findByClientId(client.getId());
        return users.stream().map(User::getUsername).filter(n -> n != null && !n.isBlank())
                .findFirst().orElse(client.getName());
    }
}
