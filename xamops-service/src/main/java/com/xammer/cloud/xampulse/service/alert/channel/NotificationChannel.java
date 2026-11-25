package com.xammer.cloud.xampulse.service.alert.channel;

/** One delivery mechanism. Implementations throw on failure so the router can record it. */
public interface NotificationChannel {

    /** EMAIL, SLACK, TEAMS or WEBHOOK. */
    String kind();

    void send(ChannelConfig config, Notification notification) throws Exception;
}
