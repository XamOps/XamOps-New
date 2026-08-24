package com.xammer.cloud.dto.call;

import com.xammer.cloud.domain.call.CallSession;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;

@Data
public class CallSessionDto {

    private Long id;
    private String roomName;
    private Long ticketId;
    private String direction;
    private String state;
    private String mediaKind;
    private Long initiatorUserId;
    private String initiatorName;
    private Long answeredByUserId;
    private String answeredByName;
    private LocalDateTime createdAt;
    private LocalDateTime answeredAt;
    private LocalDateTime endedAt;
    private String endReason;
    private Long ringExpiresInSeconds;

    /** True once the loud ring window has passed and the call is holding quietly. */
    private boolean queued;

    /**
     * Seconds the caller will keep holding before this is recorded as a missed call.
     *
     * Separate from {@code ringExpiresInSeconds}, which only covers the interrupting
     * ring. The queue window is what the caller's "waiting for an agent" UI counts
     * against, and it is far longer.
     */
    private Long queueExpiresInSeconds;

    /** How long the caller has already been holding — the number they care about. */
    private Long waitingSeconds;

    public static CallSessionDto from(CallSession s) {
        CallSessionDto dto = new CallSessionDto();
        dto.setId(s.getId());
        dto.setRoomName(s.getRoomName());
        dto.setTicketId(s.getTicketId());
        dto.setDirection(s.getDirection() != null ? s.getDirection().name() : null);
        dto.setState(s.getState() != null ? s.getState().name() : null);
        dto.setMediaKind(s.getMediaKind() != null ? s.getMediaKind().name() : null);
        dto.setInitiatorUserId(s.getInitiatorUserId());
        dto.setInitiatorName(s.getInitiatorDisplayName() != null
                ? s.getInitiatorDisplayName()
                : s.getInitiatorUsername());
        dto.setAnsweredByUserId(s.getAnsweredByUserId());
        dto.setAnsweredByName(s.getAnsweredByUsername());
        dto.setCreatedAt(s.getCreatedAt());
        dto.setAnsweredAt(s.getAnsweredAt());
        dto.setEndedAt(s.getEndedAt());
        dto.setEndReason(s.getEndReason());
        dto.setQueued(s.isQueued());
        LocalDateTime now = LocalDateTime.now();
        if (s.getState() == CallSession.State.RINGING) {
            if (s.getRingExpiresAt() != null) {
                dto.setRingExpiresInSeconds(
                        Math.max(0, Duration.between(now, s.getRingExpiresAt()).getSeconds()));
            }
            if (s.getQueueExpiresAt() != null) {
                dto.setQueueExpiresInSeconds(
                        Math.max(0, Duration.between(now, s.getQueueExpiresAt()).getSeconds()));
            }
            if (s.getCreatedAt() != null) {
                dto.setWaitingSeconds(
                        Math.max(0, Duration.between(s.getCreatedAt(), now).getSeconds()));
            }
        }
        return dto;
    }
}
