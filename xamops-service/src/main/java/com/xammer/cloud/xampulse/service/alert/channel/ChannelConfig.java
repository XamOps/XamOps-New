package com.xammer.cloud.xampulse.service.alert.channel;

import java.util.List;

/** Decrypted channel settings. Never serialised into responses or logs. */
public record ChannelConfig(List<String> to, String url) {
    @Override
    public String toString() {
        return "ChannelConfig[redacted]";
    }
}
