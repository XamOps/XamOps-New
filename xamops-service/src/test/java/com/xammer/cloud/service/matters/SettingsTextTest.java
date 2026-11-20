package com.xammer.cloud.service.matters;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Design 5.6.4: the credential shapes, and a drop logged with the field name only. */
class SettingsTextTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Logger logger = (Logger) LoggerFactory.getLogger(SettingsText.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("each of the five shapes is credential-shaped")
    void shapes() {
        assertThat(SettingsText.credentialShaped("postgres://user:pw@db.example.test/app")).isTrue();
        assertThat(SettingsText.credentialShaped("https://token@host.example.test")).isTrue();
        assertThat(SettingsText.credentialShaped("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl")).isTrue();
        assertThat(SettingsText.credentialShaped("-----BEGIN PRIVATE KEY-----\nMIIB")).isTrue();
        assertThat(SettingsText.credentialShaped("key AKIAABCDEFGHIJKLMNOP here")).isTrue();
        assertThat(SettingsText.credentialShaped("ASIAABCDEFGHIJKLMNOP")).isTrue();
        assertThat(SettingsText.credentialShaped("password=hunter")).isTrue();
        assertThat(SettingsText.credentialShaped("Api-Key: abc")).isTrue();
        assertThat(SettingsText.credentialShaped("access_key = abc")).isTrue();
        assertThat(SettingsText.credentialShaped("TOKEN:abc")).isTrue();
    }

    @Test
    @DisplayName("a plain path, an e-mail, a 56-character base64 id and an IPv6 host are not")
    void notShapes() {
        assertThat(SettingsText.credentialShaped("/var/data/reports/2026")).isFalse();
        assertThat(SettingsText.credentialShaped("ann.lee@example.test")).isFalse();
        assertThat(SettingsText.credentialShaped("QWxhZGRpbjpvcGVuIHNlc2FtZTEyMzQ1Njc4OTBhYmNkZWZnaGlqa2xt"))
                .isFalse();
        assertThat(SettingsText.credentialShaped("[2001:db8::1]")).isFalse();
        assertThat(SettingsText.credentialShaped("https://host.example.test/path")).isFalse();
        assertThat(SettingsText.credentialShaped(null)).isFalse();
    }

    @Test
    @DisplayName("clean keeps plain text, drops a credential and logs the field name, never the value")
    void cleanLogsFieldOnly() {
        assertThat(SettingsText.clean("host", NODES.textNode("  db.example.test "))).isEqualTo("db.example.test");
        assertThat(SettingsText.clean("host", NODES.numberNode(5))).isNull();
        assertThat(appender.list).isEmpty();

        assertThat(SettingsText.clean("client_id", NODES.textNode("secret=MARKERVALUE"))).isNull();
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).isEqualTo("dropped a credential-shaped value in client_id")
                .doesNotContain("MARKERVALUE");
    }

    @Test
    @DisplayName("cleanAll drops credentials, nulls and long values, and caps the list")
    void cleanAll() throws Exception {
        var array = new ObjectMapper().readTree(
                "[\"/a\", null, \"pwd: x\", \"/b\", \"" + "x".repeat(11) + "\", \"/c\", \"/d\"]");
        assertThat(SettingsText.cleanAll("paths", array, 3, 10)).containsExactly("/a", "/b", "/c");
        assertThat(SettingsText.cleanAll("paths", NODES.textNode("/a"), 3, 10)).isEmpty();
        assertThat(appender.list).hasSize(1);
    }
}
