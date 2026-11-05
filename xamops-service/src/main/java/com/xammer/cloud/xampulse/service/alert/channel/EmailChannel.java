package com.xammer.cloud.xampulse.service.alert.channel;

import com.xammer.cloud.service.EmailService;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component("xpEmailChannel")
public class EmailChannel implements NotificationChannel {

    private final EmailService email;

    public EmailChannel(EmailService email) {
        this.email = email;
    }

    @Override
    public String kind() {
        return "EMAIL";
    }

    @Override
    public void send(ChannelConfig config, Notification n) {
        String link = n.deepLink() == null ? "" : "<p><a href=\"" + HtmlUtils.htmlEscape(n.deepLink()) + "\">Open in XamPulse</a></p>";
        String html = "<h3>" + HtmlUtils.htmlEscape(n.title()) + "</h3><p>Severity: " + HtmlUtils.htmlEscape(n.severity())
                + "</p><p>" + HtmlUtils.htmlEscape(n.body()) + "</p>" + link;
        for (String to : config.to()) {
            email.sendHtmlEmail(to, "[XamPulse " + n.severity() + "] " + n.title(), html);
        }
    }
}
