package com.xammer.cloud.xampulse.service.alert.channel;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component("xpWebhookChannel")
public class WebhookChannel implements NotificationChannel {

    private final OutboundHttp http;

    public WebhookChannel(OutboundHttp http) {
        this.http = http;
    }

    @Override
    public String kind() {
        return "WEBHOOK";
    }

    @Override
    public void send(ChannelConfig config, Notification n) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "xampulse");
        body.put("kind", n.kind());
        body.put("severity", n.severity());
        body.put("title", n.title());
        body.put("message", n.body());
        body.put("accountId", n.accountId());
        body.put("instanceId", n.instanceId());
        body.put("incidentId", n.incidentId());
        body.put("link", n.deepLink());
        body.put("at", n.at() == null ? null : n.at().toString());
        http.postJson(config.url(), body);
    }
}
