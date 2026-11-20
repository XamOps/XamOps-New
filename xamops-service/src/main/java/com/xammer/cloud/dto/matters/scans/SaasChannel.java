package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One Slack channel of a SaaS scope (design 5.4). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasChannel(String id, String name, String channelType, String channelStatus, Long memberCount,
                          boolean externalShared, String topic, boolean isNew, boolean selected) {
}
