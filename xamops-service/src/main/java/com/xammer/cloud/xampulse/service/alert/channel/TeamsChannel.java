package com.xammer.cloud.xampulse.service.alert.channel;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component("xpTeamsChannel")
public class TeamsChannel implements NotificationChannel {

    private final OutboundHttp http;

    public TeamsChannel(OutboundHttp http) {
        this.http = http;
    }

    @Override
    public String kind() {
        return "TEAMS";
    }

    @Override
    public void send(ChannelConfig config, Notification n) throws Exception {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("@type", "MessageCard");
        card.put("@context", "https://schema.org/extensions");
        card.put("summary", n.title());
        card.put("title", n.title() + " (" + n.severity() + ")");
        card.put("text", n.body() + (n.deepLink() == null ? "" : "\n\n" + n.deepLink()));
        http.postJson(config.url(), card);
    }
}
