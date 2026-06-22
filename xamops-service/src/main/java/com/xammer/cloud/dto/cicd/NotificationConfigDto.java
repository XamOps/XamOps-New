package com.xammer.cloud.dto.cicd;

public class NotificationConfigDto {
    private boolean emailEnabled;
    private String slackWebhookUrl;

    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean emailEnabled) { this.emailEnabled = emailEnabled; }
    public String getSlackWebhookUrl() { return slackWebhookUrl; }
    public void setSlackWebhookUrl(String slackWebhookUrl) { this.slackWebhookUrl = slackWebhookUrl; }
}
