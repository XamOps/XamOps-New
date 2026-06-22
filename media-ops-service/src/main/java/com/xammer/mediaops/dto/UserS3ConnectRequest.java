package com.xammer.mediaops.dto;

import lombok.Data;

@Data
public class UserS3ConnectRequest {
    private String nickname;
    private String bucketName;
    private String region;
    private String accessKey;
    private String secretKey;
    private String prefixFilter;
    private String endpointUrl;
}
