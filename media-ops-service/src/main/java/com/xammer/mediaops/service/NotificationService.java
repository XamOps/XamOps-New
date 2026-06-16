package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.Notification;
import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.dto.NotificationDto;
import com.xammer.mediaops.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepo;
    private final SimpMessagingTemplate messaging;

    public NotificationService(NotificationRepository notificationRepo,
                               SimpMessagingTemplate messaging) {
        this.notificationRepo = notificationRepo;
        this.messaging = messaging;
    }

    /**
     * Creates a success notification when a transcode job completes and broadcasts it via WebSocket.
     */
    public void notifyJobCompleted(TranscodeJob job) {
        Notification notification = new Notification();
        notification.setClientId(job.getClientId());
        notification.setJobId(job.getId());
        notification.setType(Notification.NotificationType.JOB_COMPLETED);
        notification.setMessage("Job '" + job.getName() + "' completed successfully.");
        notification.setCreatedAt(Instant.now());
        notification = notificationRepo.save(notification);

        broadcast(job.getClientId(), notification);
        log.info("[MediaOps] Notification sent: job {} completed for client {}", job.getId(), job.getClientId());
    }

    /**
     * Creates a failure notification when a transcode job fails and broadcasts it via WebSocket.
     * Includes the first 100 characters of the error message as a summary.
     */
    public void notifyJobFailed(TranscodeJob job) {
        String errorSummary = job.getErrorMessage() != null
                ? job.getErrorMessage().substring(0, Math.min(100, job.getErrorMessage().length()))
                : "Unknown error";

        Notification notification = new Notification();
        notification.setClientId(job.getClientId());
        notification.setJobId(job.getId());
        notification.setType(Notification.NotificationType.JOB_FAILED);
        notification.setMessage("Job '" + job.getName() + "' failed: " + errorSummary);
        notification.setCreatedAt(Instant.now());
        notification = notificationRepo.save(notification);

        broadcast(job.getClientId(), notification);
        log.info("[MediaOps] Notification sent: job {} failed for client {}", job.getId(), job.getClientId());
    }

    /**
     * Returns all notifications for a client, ordered by creation time descending.
     */
    public List<NotificationDto> getNotifications(Long clientId) {
        return notificationRepo.findByClientIdOrderByCreatedAtDesc(clientId)
                .stream()
                .map(NotificationDto::from)
                .collect(Collectors.toList());
    }

    /**
     * Returns the count of unread notifications for a client.
     */
    public int getUnreadCount(Long clientId) {
        return notificationRepo.countByClientIdAndReadFalse(clientId);
    }

    /**
     * Marks a notification as read. Only succeeds if the notification belongs to the given client.
     */
    @Transactional
    public boolean markAsRead(Long notificationId, Long clientId) {
        return notificationRepo.findByIdAndClientId(notificationId, clientId)
                .map(notification -> {
                    notification.setRead(true);
                    notificationRepo.save(notification);
                    return true;
                })
                .orElse(false);
    }

    private void broadcast(Long clientId, Notification notification) {
        try {
            NotificationDto dto = NotificationDto.from(notification);
            messaging.convertAndSend("/topic/media-ops/notifications/" + clientId, dto);
        } catch (Exception e) {
            log.warn("[MediaOps] Failed to broadcast notification: {}", e.getMessage());
        }
    }
}
