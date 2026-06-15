package com.xammer.mediaops.dto;

import lombok.Data;

@Data
public class MediaStreamRequest {
    private String name;
    private String resolution = "1080p";
    private String bitrate = "4500k";
}
