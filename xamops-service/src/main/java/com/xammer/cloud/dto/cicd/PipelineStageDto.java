package com.xammer.cloud.dto.cicd;

public class PipelineStageDto {
    private String name;
    private String status; // success, failure, running, pending, skipped
    private String duration; // e.g., "2m 10s"
    private String url;

    public PipelineStageDto() {
    }

    public PipelineStageDto(String name, String status, String duration, String url) {
        this.name = name;
        this.status = status;
        this.duration = duration;
        this.url = url;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}