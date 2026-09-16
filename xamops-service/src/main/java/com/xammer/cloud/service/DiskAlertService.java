package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DiskAlertState;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.messaging.MessageKind;
import com.xammer.cloud.messaging.MobileNotificationDispatcher;
import com.xammer.cloud.messaging.NotificationChannel;
import com.xammer.cloud.repository.DiskAlertStateRepository;
import com.xammer.cloud.repository.FinOpsReportScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiskAlertService {

    private static final Logger logger = LoggerFactory.getLogger(DiskAlertService.class);
    private static final double NEAR_FULL_THRESHOLD   = 80.0;
    private static final double UNDERUTIL_THRESHOLD   = 25.0;
    private static final int    UNDERUTIL_MIN_SIZE_GB = 20;

    private final FinOpsReportScheduleRepository scheduleRepository;
    private final EbsDiskRightsizingService       ebsDiskRightsizingService;
    private final DiskAlertStateRepository        diskAlertStateRepository;
    private final AlertEmailBuilder               alertEmailBuilder;
    private final EmailService                    emailService;
    private final AlertTextBuilder                alertTextBuilder;
    private final MobileNotificationDispatcher    mobileNotificationDispatcher;

    public void runAlertScan() {
        List<CloudAccount> accounts = scheduleRepository.findDistinctActiveAccounts();
        logger.info("[DiskAlert] Scanning {} accounts with active FinOps subscriptions", accounts.size());

        for (CloudAccount account : accounts) {
            if (!"AWS".equals(account.getProvider())) continue;
            try {
                scanAccount(account);
            } catch (Exception e) {
                logger.error("[DiskAlert] Failed to scan account {}: {}",
                        account.getProviderAccountId(), e.getMessage(), e);
            }
        }
    }

    /** Package-private for direct test access. */
    void scanAccount(CloudAccount account) {
        List<Map<String, Object>> volumes =
                ebsDiskRightsizingService.getVolumes(account.getProviderAccountId(), false);

        // Preload active states once — O(1) lookup per volume
        List<DiskAlertState> activeStates =
                diskAlertStateRepository.findByAccount_IdAndResolvedAtIsNull(account.getId());

        Set<String> activeNearFull = activeStates.stream()
                .filter(s -> "NEAR_FULL".equals(s.getAlertType()))
                .map(DiskAlertState::getVolumeId)
                .collect(Collectors.toSet());

        Set<String> activeUnderutilized = activeStates.stream()
                .filter(s -> "UNDERUTILIZED".equals(s.getAlertType()))
                .map(DiskAlertState::getVolumeId)
                .collect(Collectors.toSet());

        Set<String> scannedVolumeIds     = new HashSet<>();
        Set<String> currentNearFull      = new HashSet<>();
        Set<String> currentUnderutilized = new HashSet<>();
        List<AlertDto> newAlerts         = new ArrayList<>();

        for (Map<String, Object> vol : volumes) {
            if (!vol.containsKey("usedPercent")) continue;

            String volumeId = (String) vol.get("volumeId");
            if (volumeId == null) continue;
            String region   = (String) vol.get("region");
            double usedPct  = ((Number) vol.get("usedPercent")).doubleValue();
            int    sizeGb   = ((Number) vol.get("sizeGb")).intValue();
            double freeGb   = vol.containsKey("freeGb")
                    ? ((Number) vol.get("freeGb")).doubleValue()
                    : sizeGb * (1 - usedPct / 100.0);

            scannedVolumeIds.add(volumeId);

            if (usedPct > NEAR_FULL_THRESHOLD) {
                currentNearFull.add(volumeId);
                if (!activeNearFull.contains(volumeId)) {
                    newAlerts.add(buildAlertDto(volumeId, region, usedPct, freeGb, sizeGb, "NEAR_FULL"));
                    persistAlertState(account, volumeId, region, "NEAR_FULL");
                }
            }

            if (usedPct < UNDERUTIL_THRESHOLD && sizeGb >= UNDERUTIL_MIN_SIZE_GB) {
                currentUnderutilized.add(volumeId);
                if (!activeUnderutilized.contains(volumeId)) {
                    newAlerts.add(buildAlertDto(volumeId, region, usedPct, freeGb, sizeGb, "UNDERUTILIZED"));
                    persistAlertState(account, volumeId, region, "UNDERUTILIZED");
                }
            }
        }

        // Mark recoveries: volume is scanned but no longer breaching
        for (DiskAlertState state : activeStates) {
            if (!scannedVolumeIds.contains(state.getVolumeId())) continue; // no agent data — leave unchanged
            boolean stillBreaching =
                    ("NEAR_FULL".equals(state.getAlertType())     && currentNearFull.contains(state.getVolumeId()))
                 || ("UNDERUTILIZED".equals(state.getAlertType()) && currentUnderutilized.contains(state.getVolumeId()));
            if (!stillBreaching) {
                state.setResolvedAt(Instant.now());
                diskAlertStateRepository.save(state);
                logger.info("[DiskAlert] Volume {} recovered from {} for account {}",
                        state.getVolumeId(), state.getAlertType(), account.getProviderAccountId());
            }
        }

        if (!newAlerts.isEmpty()) {
            sendAlerts(account, newAlerts);
        }
    }

    private void persistAlertState(CloudAccount account, String volumeId, String region, String alertType) {
        // Upsert: reuse existing (resolved) row if present; otherwise insert new.
        Optional<DiskAlertState> existing = diskAlertStateRepository
                .findByAccount_IdAndVolumeIdAndAlertType(account.getId(), volumeId, alertType);
        DiskAlertState state = existing.orElseGet(DiskAlertState::new);
        state.setAccount(account);
        state.setVolumeId(volumeId);
        state.setRegion(region);
        state.setAlertType(alertType);
        state.setAlertedAt(Instant.now());
        state.setResolvedAt(null);
        diskAlertStateRepository.save(state);
    }

    private void sendAlerts(CloudAccount account, List<AlertDto> alerts) {
        List<String> recipients = scheduleRepository.findActiveEmailsByAccountId(account.getId());
        if (recipients.isEmpty()) return;

        String html = alertEmailBuilder.buildAlertDigestEmail(
                alerts, account.getAccountName(), account.getProviderAccountId());
        String subject = String.format("XamOps Disk Alert — %s (%d volume(s) need attention)",
                account.getAccountName(), alerts.size());

        for (String email : recipients) {
            try {
                emailService.sendHtmlEmail(email, subject, html);
                logger.info("[DiskAlert] Sent alert to {} for account {}", email, account.getProviderAccountId());
            } catch (Exception e) {
                logger.error("[DiskAlert] Failed to send alert to {}: {}", email, e.getMessage());
            }
        }

        sendMobileAlerts(account, alerts);
    }

    /**
     * Mirrors the alert digest onto SMS / WhatsApp for recipients who have a mobile number.
     *
     * <p>Additive: email remains the channel of record and runs first, so a provider outage or a
     * missing template changes nothing about existing behaviour. The extra recipient query is
     * skipped entirely when no mobile channel is configured.
     *
     * <p>Only a summary and a portal link go out — see {@link AlertTextBuilder} for why the
     * affected volume IDs stay in the email.
     */
    private void sendMobileAlerts(CloudAccount account, List<AlertDto> alerts) {
        if (!mobileNotificationDispatcher.isActive()) return;

        try {
            // Per-customer entitlement, checked before the recipient query. These channels are
            // billed per message, so a client nobody has granted them to must cost nothing —
            // not even the lookup.
            Set<NotificationChannel> entitled = scheduleRepository
                    .findClientByAccountId(account.getId())
                    .map(c -> NotificationChannel.entitled(
                            c.isSmsNotificationsEnabled(), c.isWhatsappNotificationsEnabled()))
                    .orElseGet(() -> EnumSet.noneOf(NotificationChannel.class));
            if (entitled.isEmpty()) return;

            List<String> contacts = scheduleRepository.findActiveContactsByAccountId(account.getId());
            if (contacts.isEmpty()) return;

            mobileNotificationDispatcher.dispatchAll(
                    contacts,
                    alertTextBuilder.buildAlertVariables(alerts, account.getAccountName()),
                    MessageKind.ALERT,
                    "account " + account.getProviderAccountId(),
                    entitled);
        } catch (Exception e) {
            // The dispatcher already swallows per-message failures; this guards the query and
            // variable build so a mobile problem can never cost the email that already went out.
            logger.error("[DiskAlert] Mobile alert dispatch failed for account {}: {}",
                    account.getProviderAccountId(), e.getMessage());
        }
    }

    private AlertDto buildAlertDto(String volumeId, String region,
                                   double usedPct, double freeGb, int sizeGb, String alertType) {
        AlertDto dto = new AlertDto();
        dto.setId(volumeId);
        dto.setName(volumeId);
        dto.setService("EBS");
        dto.setType("DISK");
        dto.setRegion(region);
        dto.setUsage(usedPct);
        dto.setLimit(100.0);
        if ("NEAR_FULL".equals(alertType)) {
            dto.setStatus("CRITICAL");
            dto.setDescription(String.format(
                    "%.1f%% used — %.1f GB free of %d GB. Recommended action: Expand.",
                    usedPct, freeGb, sizeGb));
        } else {
            dto.setStatus("WARNING");
            dto.setDescription(String.format(
                    "%.1f%% used — volume is over-provisioned (%d GB). Recommended action: Shrink.",
                    usedPct, sizeGb));
        }
        return dto;
    }
}
