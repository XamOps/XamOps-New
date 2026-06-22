package com.xammer.mediaops.dto;

import com.xammer.mediaops.domain.UserS3Config;
import lombok.Data;

import java.time.Instant;

@Data
public class UserS3ConfigDto {
    private Long id;
    private String nickname;
    private String bucketName;
    private String region;
    private String accessKeyHint;
    private String prefixFilter;
    private Instant createdAt;

    public static UserS3ConfigDto from(UserS3Config c) {
        UserS3ConfigDto dto = new UserS3ConfigDto();
        dto.id = c.getId();
        dto.nickname = c.getNickname();
        dto.bucketName = c.getBucketName();
        dto.region = c.getRegion();
        dto.prefixFilter = c.getPrefixFilter();
        dto.createdAt = c.getCreatedAt();
        if (c.getAccessKey() != null && c.getAccessKey().length() > 4) {
            dto.accessKeyHint = "****" + c.getAccessKey().substring(c.getAccessKey().length() - 4);
        }
        return dto;
    }
}
