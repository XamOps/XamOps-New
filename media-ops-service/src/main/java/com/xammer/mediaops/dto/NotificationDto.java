package com.xammer.mediaops.dto;

import com.xammer.mediaops.domain.Notification;
import lombok.Data;

import java.time.Instant;

@Data
public class NotificationDto {

    private Long id;
    private Long clientId;
    private Long jobId;
    private String type;
    private String message;
    private boolean read;
    private Instant createdAt;

    public static NotificationDto from(Notification n) {
        NotificationDto dto = new NotificationDto();
        dto.setId(n.getId());
        dto.setClientId(n.getClientId());
        dto.setJobId(n.getJobId());
        dto.setType(n.getType().name());
        dto.setMessage(n.getMessage());
        dto.setRead(n.isRead());
        dto.setCreatedAt(n.getCreatedAt());
        return dto;
    }
}
