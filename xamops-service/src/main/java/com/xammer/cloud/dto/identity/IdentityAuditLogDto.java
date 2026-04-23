package com.xammer.cloud.dto.identity;

import com.xammer.cloud.domain.IdentityAuditLog;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Response DTO for the audit log page. Mapped from IdentityAuditLog entity. */
@Data
@NoArgsConstructor
public class IdentityAuditLogDto {

    private Long id;
    private String action;
    private Long actorUserId;
    private String targetUserEmail;
    private String groupName;
    private LocalDateTime timestamp;

    public static IdentityAuditLogDto from(IdentityAuditLog log) {
        IdentityAuditLogDto dto = new IdentityAuditLogDto();
        dto.setId(log.getId());
        dto.setAction(log.getAction());
        dto.setActorUserId(log.getActorUserId());
        dto.setTargetUserEmail(log.getTargetUserEmail());
        dto.setGroupName(log.getGroupName());
        dto.setTimestamp(log.getTimestamp());
        return dto;
    }
}
