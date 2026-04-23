package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.SreNotification;
import lombok.Data;

import java.time.Instant;

@Data
public class SreNotificationDto {
    private Long id;
    private Long investigationId;
    private String title;
    private String message;
    private boolean read;
    private Instant createdAt;

    public static SreNotificationDto from(SreNotification n) {
        SreNotificationDto dto = new SreNotificationDto();
        dto.setId(n.getId());
        dto.setInvestigationId(n.getInvestigationId());
        dto.setTitle(n.getTitle());
        dto.setMessage(n.getMessage());
        dto.setRead(n.isRead());
        dto.setCreatedAt(n.getCreatedAt());
        return dto;
    }
}
