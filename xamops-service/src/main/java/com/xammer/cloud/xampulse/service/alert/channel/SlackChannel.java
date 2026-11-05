package com.xammer.cloud.xampulse.service.alert.channel;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component("xpSlackChannel")
public class SlackChannel implements NotificationChannel {

    private final OutboundHttp http;

    public SlackChannel(OutboundHttp http) {
        this.http = http;
    }

    @Override
    public String kind() {
        return "SLACK";
    }

    @Override
    public void send(ChannelConfig config, Notification n) throws Exception {
        String link = n.deepLink() == null ? "" : "\n" + n.deepLink();
        http.postJson(config.url(), Map.of("text", "*" + n.title() + "* (" + n.severity() + ")\n" + n.body() + link));
    }
}
