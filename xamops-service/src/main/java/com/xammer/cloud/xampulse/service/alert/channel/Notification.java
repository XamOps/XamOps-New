package com.xammer.cloud.xampulse.service.alert.channel;

import java.time.Instant;

/** What a channel sends. Contains no secrets and no raw log lines. */
public record Notification(String kind, String title, String body, String severity, Long accountId,
                           String instanceId, Long incidentId, String deepLink, Instant at) {}
