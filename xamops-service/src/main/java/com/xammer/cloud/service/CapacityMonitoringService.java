package com.xammer.cloud.service;

import com.xammer.cloud.domain.CapacityNotificationHistory;
import com.xammer.cloud.domain.CloudiDRCredentials;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.flexcompute.CapacityDescriptionResponse;
import com.xammer.cloud.dto.flexcompute.CapacityListResponse;
import com.xammer.cloud.repository.CapacityNotificationHistoryRepository;
import com.xammer.cloud.repository.CloudiDRCredentialsRepository;
import com.xammer.cloud.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Service that monitors FlexCompute capacity consumption and sends email notifications
 * when capacities reach 80% or 90% threshold, especially for extended capacities.
 */
@Service
public class CapacityMonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(CapacityMonitoringService.class);

    // Threshold percentages to monitor
    private static final List<Integer> THRESHOLDS = Arrays.asList(80, 90);

    // Minimum hours between re-notifications for the same threshold (24 hours)
    private static final long MIN_HOURS_BETWEEN_NOTIFICATIONS = 24;

    private final FlexComputeService flexComputeService;
    private final EmailService emailService;
    private final CapacityNotificationEmailBuilder emailBuilder;
    private final CapacityNotificationHistoryRepository notificationHistoryRepository;
    private final CloudiDRCredentialsRepository credentialsRepository;
    private final UserRepository userRepository;

    @Value("${capacity.monitoring.enabled:true}")
    private boolean monitoringEnabled;

    public CapacityMonitoringService(FlexComputeService flexComputeService,
                                     EmailService emailService,
                                     CapacityNotificationEmailBuilder emailBuilder,
                                     CapacityNotificationHistoryRepository notificationHistoryRepository,
                                     CloudiDRCredentialsRepository credentialsRepository,
                                     UserRepository userRepository) {
        this.flexComputeService = flexComputeService;
        this.emailService = emailService;
        this.emailBuilder = emailBuilder;
        this.notificationHistoryRepository = notificationHistoryRepository;
        this.credentialsRepository = credentialsRepository;
        this.userRepository = userRepository;
    }

    /**
     * Scheduled task to monitor all active capacities.
     * Runs every 15 minutes to check for threshold breaches.
     */
    @Scheduled(fixedDelay = 900000) // 15 minutes = 900,000 ms
    public void monitorCapacities() {
        if (!monitoringEnabled) {
            logger.debug("Capacity monitoring is disabled");
            return;
        }

        logger.info("Starting scheduled capacity monitoring...");

        try {
            // Get all users with CloudiDR credentials
            List<CloudiDRCredentials> allCredentials = credentialsRepository.findAll();
            logger.info("Found {} users with CloudiDR credentials", allCredentials.size());

            for (CloudiDRCredentials credentials : allCredentials) {
                try {
                    monitorUserCapacities(credentials.getUserId());
                } catch (Exception e) {
                    logger.error("Error monitoring capacities for user {}: {}", credentials.getUserId(), e.getMessage(), e);
                }
            }

            logger.info("Completed scheduled capacity monitoring");
        } catch (Exception e) {
            logger.error("Error in capacity monitoring scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * Monitor capacities for a specific user.
     */
    private void monitorUserCapacities(Long userId) {
        logger.info("Monitoring capacities for user {}", userId);

        try {
            // Check if user is authenticated
            if (!flexComputeService.isAuthenticated(userId)) {
                logger.debug("User {} is not authenticated with CloudiDR, skipping", userId);
                return;
            }

            // Get user details for email
            Optional<User> userOpt = userRepository.findById(userId);
            if (!userOpt.isPresent()) {
                logger.warn("User {} not found in database", userId);
                return;
            }
            User user = userOpt.get();
            String userEmail = user.getEmail();

            if (userEmail == null || userEmail.isEmpty()) {
                logger.warn("User {} has no email address, skipping notifications", userId);
                return;
            }

            // Fetch all active capacities — only valid CloudiDR statuses (RUNNING/SCHEDULED don't exist → 404)
            List<String> activeStatuses = Arrays.asList("ACTIVE", "COMPLETED");
            CapacityListResponse capacityList = flexComputeService.describeCapacity(userId, 1, 100, "desc", activeStatuses);

            if (capacityList == null || capacityList.getValues() == null || capacityList.getValues().isEmpty()) {
                logger.debug("No active capacities found for user {}", userId);
                return;
            }

            logger.info("Found {} active capacities for user {}", capacityList.getValues().size(), userId);

            // Check each capacity
            for (CapacityDescriptionResponse capacity : capacityList.getValues()) {
                try {
                    // Check if capacity just became active (deployed) and send notification
                    checkDeploymentStatus(userId, userEmail, capacity);
                    checkCapacityThresholds(userId, userEmail, capacity);
                } catch (Exception e) {
                    logger.error("Error checking capacity {}: {}", capacity.getCidrId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logger.error("Error monitoring capacities for user {}: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * Check if a capacity has transitioned to active/running state and send deployment notification.
     * Uses thresholdPercent=0 as a sentinel to track deployment notifications.
     */
    private void checkDeploymentStatus(Long userId, String userEmail, CapacityDescriptionResponse capacity) {
        String status = capacity.getPolicyStatus();
        if (status == null) return;

        // Only send deployment notification for ACTIVE capacities (RUNNING is not a valid CloudiDR status)
        if (!"ACTIVE".equalsIgnoreCase(status)) {
            return;
        }

        String cidrId = capacity.getCidrId();

        // Check if we already sent a deployment notification for this capacity
        if (notificationHistoryRepository.hasDeploymentNotificationBeenSent(cidrId)) {
            logger.debug("Deployment notification already sent for capacity {}, skipping", cidrId);
            return;
        }

        try {
            logger.info("Sending deployment notification for capacity {} to user {} ({})",
                    cidrId, userId, userEmail);

            String htmlBody = emailBuilder.buildCapacityDeployedEmail(capacity, userEmail);
            String subject = String.format("XamOps FlexCompute - Capacity Deployed: %s", capacity.getPolicyName());

            emailService.sendHtmlEmail(userEmail, subject, htmlBody);

            // Record notification in history (thresholdPercent=0 marks deployment notification)
            CapacityNotificationHistory history = new CapacityNotificationHistory(
                    userId,
                    cidrId,
                    0, // sentinel value for deployment notification
                    0.0,
                    false,
                    capacity.getPolicyName(),
                    userEmail
            );
            recordNotificationHistory(history);

            logger.info("Successfully sent deployment notification for capacity {} to {}", cidrId, userEmail);

        } catch (Exception e) {
            logger.error("Failed to send deployment notification for capacity {}: {}", cidrId, e.getMessage(), e);
        }
    }

    /**
     * Check if a capacity has crossed any threshold and send notification if needed.
     */
    private void checkCapacityThresholds(Long userId, String userEmail, CapacityDescriptionResponse capacity) {
        Double consumptionPercent = capacity.getConsumptionPercent();
        if (consumptionPercent == null) {
            logger.debug("Capacity {} has no consumption data", capacity.getCidrId());
            return;
        }

        String cidrId = capacity.getCidrId();
        Boolean autoRenewal = capacity.getAutoRenewal();

        // Determine if capacity was extended (we consider auto-renewal as a form of extension)
        // In a real scenario, you might track extension history in the database
        Boolean wasExtended = autoRenewal != null && autoRenewal;

        logger.debug("Checking capacity {} - consumption: {}%, auto-renewal: {}", cidrId, consumptionPercent, autoRenewal);

        // Check each threshold
        for (Integer threshold : THRESHOLDS) {
            if (consumptionPercent >= threshold) {
                // Check if we should send notification
                if (shouldSendNotification(cidrId, threshold)) {
                    sendThresholdNotification(userId, userEmail, capacity, consumptionPercent, threshold, wasExtended);
                } else {
                    logger.debug("Skipping notification for capacity {} at {}% (already notified recently)", cidrId, threshold);
                }
            }
        }
    }

    /**
     * Check if we should send a notification for a capacity and threshold.
     * Returns true if no notification was sent in the last 24 hours for this threshold.
     */
    private boolean shouldSendNotification(String cidrId, Integer threshold) {
        Optional<CapacityNotificationHistory> lastNotification =
                notificationHistoryRepository.findFirstByCidrIdAndThresholdPercentOrderByNotifiedAtDesc(cidrId, threshold);

        if (!lastNotification.isPresent()) {
            return true; // Never notified before
        }

        // Check if enough time has passed since last notification
        Instant lastNotifiedAt = lastNotification.get().getNotifiedAt();
        long hoursSinceLastNotification = ChronoUnit.HOURS.between(lastNotifiedAt, Instant.now());

        return hoursSinceLastNotification >= MIN_HOURS_BETWEEN_NOTIFICATIONS;
    }

    /**
     * Send threshold notification email and record in history.
     */
    private void sendThresholdNotification(Long userId, String userEmail,
                                          CapacityDescriptionResponse capacity,
                                          Double consumptionPercent,
                                          Integer threshold,
                                          Boolean wasExtended) {
        try {
            logger.info("Sending {}% threshold notification for capacity {} to user {} ({})",
                    threshold, capacity.getCidrId(), userId, userEmail);

            // Build HTML email
            String htmlBody = emailBuilder.buildCapacityThresholdEmail(
                    capacity, consumptionPercent, threshold, wasExtended, userEmail);

            // Email subject
            String subject = String.format("XamOps FlexCompute Alert: Capacity %s%% Threshold Reached - %s",
                    threshold,
                    capacity.getPolicyName());

            // Send email
            emailService.sendHtmlEmail(userEmail, subject, htmlBody);

            // Record notification in history
            CapacityNotificationHistory history = new CapacityNotificationHistory(
                    userId,
                    capacity.getCidrId(),
                    threshold,
                    consumptionPercent,
                    wasExtended,
                    capacity.getPolicyName(),
                    userEmail
            );
            recordNotificationHistory(history);

            logger.info("Successfully sent {}% threshold notification for capacity {} to {}",
                    threshold, capacity.getCidrId(), userEmail);

        } catch (Exception e) {
            logger.error("Failed to send threshold notification for capacity {}: {}",
                    capacity.getCidrId(), e.getMessage(), e);
        }
    }

    /**
     * Persist a notification history record.
     * Spring Data JPA repository.save() provides its own transaction automatically.
     */
    protected void recordNotificationHistory(CapacityNotificationHistory history) {
        notificationHistoryRepository.save(history);
    }

    /**
     * Cleanup old notification history (older than 90 days).
     * Runs once a day at 2 AM.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void cleanupOldNotifications() {
        try {
            logger.info("Cleaning up old notification history...");
            Instant cutoffDate = Instant.now().minus(90, ChronoUnit.DAYS);
            notificationHistoryRepository.deleteByNotifiedAtBefore(cutoffDate);
            logger.info("Completed cleanup of notifications older than 90 days");
        } catch (Exception e) {
            logger.error("Error during notification history cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual trigger for monitoring (can be called via API for testing).
     */
    public void triggerManualMonitoring(Long userId) {
        logger.info("Manual capacity monitoring triggered for user {}", userId);
        monitorUserCapacities(userId);
    }
}
