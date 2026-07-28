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
        if (s.getState() == CallSession.State.RINGING && s.getRingExpiresAt() != null) {
            long left = Duration.between(LocalDateTime.now(), s.getRingExpiresAt()).getSeconds();
            dto.setRingExpiresInSeconds(Math.max(0, left));
        }
        return dto;
    }
}
