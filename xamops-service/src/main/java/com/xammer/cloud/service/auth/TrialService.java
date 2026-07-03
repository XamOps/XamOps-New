package com.xammer.cloud.service.auth;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Owns the trial lifecycle for self-signup Clients: start, day math, expiry,
 * extension, conversion, and a Redis read-through cache of trial status keyed
 * by clientId (used by the cross-service TrialEnforcementFilter).
 */
@Service
@Slf4j
public class TrialService {

    public static final String PLAN_TRIAL = "TRIAL";
    public static final String PLAN_PAID = "PAID";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CONVERTED = "CONVERTED";

    // Cache sentinel meaning "not trial-gated" (PAID / converted / unknown).
    public static final String STATUS_NONE = "NONE";

    private static final String CACHE_PREFIX = "trial:status:";
    private static final int CACHE_TTL_MINUTES = 30;

    @Value("${app.trial.duration-days:14}")
    private int trialDurationDays;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    /** Initializes a fresh 14-day trial on a newly created self-signup Client. */
    public void startTrial(Client client) {
        LocalDateTime now = LocalDateTime.now();
        client.setPlanType(PLAN_TRIAL);
        client.setTrialStatus(STATUS_ACTIVE);
        client.setTrialStartAt(now);
        client.setTrialEndAt(now.plusDays(trialDurationDays));
        client.setReminder5SentAt(null);
        client.setFinalDaySentAt(null);
        clientRepository.save(client);
        cacheStatus(client.getId(), STATUS_ACTIVE);
    }

    /** Whole days remaining until trial end, never negative. 0 for non-trial. */
    public long daysRemaining(Client client) {
        if (!PLAN_TRIAL.equals(client.getPlanType()) || client.getTrialEndAt() == null) {
            return 0;
        }
        long days = Duration.between(LocalDateTime.now(), client.getTrialEndAt()).toDays();
        return Math.max(days, 0);
    }

    /**
     * Effective status for enforcement. Lazily transitions an ACTIVE trial whose
     * end date has passed to EXPIRED. Returns STATUS_NONE for non-trial clients.
     */
    public String effectiveStatus(Client client) {
        if (!PLAN_TRIAL.equals(client.getPlanType())) {
            return STATUS_NONE;
        }
        if (STATUS_EXPIRED.equals(client.getTrialStatus())) {
            return STATUS_EXPIRED;
        }
        if (client.getTrialEndAt() != null && LocalDateTime.now().isAfter(client.getTrialEndAt())) {
            client.setTrialStatus(STATUS_EXPIRED);
            clientRepository.save(client);
            return STATUS_EXPIRED;
        }
        return STATUS_ACTIVE;
    }

    /** Cached status lookup by clientId for the enforcement filter. DB fallback on miss. */
    public String statusForClient(Long clientId) {
        if (clientId == null) return STATUS_NONE;
        try {
            String cached = redisCacheService.getString(CACHE_PREFIX + clientId);
            if (cached != null) {
                return cached;
            }
        } catch (Exception e) {
            // Redis unavailable — fall through to DB rather than blocking every request.
            log.warn("Trial status cache read failed for client {}: {}", clientId, e.getMessage());
        }
        String status;
        try {
            status = clientRepository.findById(clientId)
                    .map(this::effectiveStatus)
                    .orElse(STATUS_NONE);
        } catch (Exception e) {
            // DB read failed too — do not hard-block the user on infra hiccups.
            log.warn("Trial status DB read failed for client {}: {}", clientId, e.getMessage());
            return STATUS_NONE;
        }
        try { cacheStatus(clientId, status); } catch (Exception ignored) {}
        return status;
    }

    /** Admin action: extend the trial window by the given number of days. */
    public Client extendTrial(Long clientId, int days) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientId));
        LocalDateTime base = client.getTrialEndAt() != null && client.getTrialEndAt().isAfter(LocalDateTime.now())
                ? client.getTrialEndAt()
                : LocalDateTime.now();
        client.setPlanType(PLAN_TRIAL);
        client.setTrialStatus(STATUS_ACTIVE);
        client.setTrialEndAt(base.plusDays(days));
        // Clear sent flags so reminders can fire again for the new window.
        client.setReminder5SentAt(null);
        client.setFinalDaySentAt(null);
        clientRepository.save(client);
        cacheStatus(clientId, STATUS_ACTIVE);
        log.info("Trial extended for client {} by {} days -> {}", clientId, days, client.getTrialEndAt());
        return client;
    }

    /** Admin action: convert a trial to a paid plan (removes trial gating). */
    public Client convertToPaid(Long clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientId));
        client.setPlanType(PLAN_PAID);
        client.setTrialStatus(STATUS_CONVERTED);
        clientRepository.save(client);
        cacheStatus(clientId, STATUS_NONE);
        log.info("Client {} converted to PAID", clientId);
        return client;
    }

    public void invalidateCache(Long clientId) {
        if (clientId != null) {
            redisCacheService.evict(CACHE_PREFIX + clientId);
        }
    }

    private void cacheStatus(Long clientId, String status) {
        if (clientId != null) {
            redisCacheService.putString(CACHE_PREFIX + clientId, status, CACHE_TTL_MINUTES);
        }
    }
}
