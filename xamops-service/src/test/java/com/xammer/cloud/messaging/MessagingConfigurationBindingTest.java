package com.xammer.cloud.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the messaging beans wire up and the {@code xamops.messaging.*} properties actually
 * bind.
 *
 * <p>Compilation proves none of this: a misspelled property key, a nested class Spring cannot
 * bind to, or a missing {@code @EnableConfigurationProperties} all compile perfectly and then
 * silently leave the integration disabled at runtime — which looks identical to "no alerts were
 * due". The full-context smoke tests need PostgreSQL, Redis and Kafka, so this uses
 * {@link ApplicationContextRunner} to load just this slice.
 */
class MessagingConfigurationBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MessagingClientConfig.class, TestBeans.class);

    /** Mirrors what component scanning provides in the real applications. */
    @Configuration
    static class TestBeans {
        @Bean
        SmsWhatsAppClient smsWhatsAppClient(RestClient messagingRestClient, MessagingProperties props) {
            return new SmsWhatsAppClient(messagingRestClient, props);
        }

        @Bean
        MobileNotificationDispatcher dispatcher(SmsWhatsAppClient client, MessagingProperties props) {
            return new MobileNotificationDispatcher(client, props);
        }
    }

    @Test
    void beansAreCreatedAndDefaultToDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MessagingProperties.class);
            assertThat(context).hasBean("messagingRestClient");
            assertThat(context).hasSingleBean(SmsWhatsAppClient.class);
            assertThat(context).hasSingleBean(MobileNotificationDispatcher.class);

            // Shipping disabled is the whole safety story: an unconfigured deployment must
            // behave exactly as it did before this integration existed.
            assertThat(context.getBean(MobileNotificationDispatcher.class).isActive()).isFalse();
        });
    }

    @Test
    void bindsEveryPropertyIncludingNestedChannelsAndDurations() {
        runner.withPropertyValues(
                "xamops.messaging.enabled=true",
                "xamops.messaging.base-url=https://provider.example.com/",
                "xamops.messaging.api-key=secret-key",
                "xamops.messaging.default-country-code=+91",
                "xamops.messaging.connect-timeout=3s",
                "xamops.messaging.read-timeout=15s",
                "xamops.messaging.sms.enabled=true",
                "xamops.messaging.sms.sender-id=XAMOPS",
                "xamops.messaging.sms.route=transactional",
                "xamops.messaging.sms.alert-template-id=DLT_ALERT",
                "xamops.messaging.sms.report-template-id=DLT_REPORT",
                "xamops.messaging.sms.support-template-id=DLT_SUPPORT",
                "xamops.messaging.whatsapp.enabled=true",
                "xamops.messaging.whatsapp.sender=+911111111111",
                "xamops.messaging.whatsapp.alert-template-id=WA_ALERT",
                "xamops.messaging.whatsapp.report-template-id=WA_REPORT",
                "xamops.messaging.whatsapp.support-template-id=WA_SUPPORT",
                "xamops.messaging.whatsapp.sms-fallback=true"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            MessagingProperties props = context.getBean(MessagingProperties.class);

            assertThat(props.isEnabled()).isTrue();
            // Trailing slash must be trimmed or joined paths become "//api/v1/...".
            assertThat(props.getBaseUrl()).isEqualTo("https://provider.example.com");
            assertThat(props.getApiKey()).isEqualTo("secret-key");
            assertThat(props.getDefaultCountryCode()).isEqualTo("+91");
            assertThat(props.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(props.getReadTimeout()).isEqualTo(Duration.ofSeconds(15));

            assertThat(props.getSms().isEnabled()).isTrue();
            assertThat(props.getSms().getSenderId()).isEqualTo("XAMOPS");
            assertThat(props.getSms().getRoute()).isEqualTo("transactional");
            assertThat(props.getSms().getAlertTemplateId()).isEqualTo("DLT_ALERT");
            assertThat(props.getSms().getReportTemplateId()).isEqualTo("DLT_REPORT");
            assertThat(props.getSms().getSupportTemplateId()).isEqualTo("DLT_SUPPORT");

            assertThat(props.getWhatsapp().isEnabled()).isTrue();
            assertThat(props.getWhatsapp().getSender()).isEqualTo("+911111111111");
            assertThat(props.getWhatsapp().getAlertTemplateId()).isEqualTo("WA_ALERT");
            assertThat(props.getWhatsapp().getReportTemplateId()).isEqualTo("WA_REPORT");
            assertThat(props.getWhatsapp().getSupportTemplateId()).isEqualTo("WA_SUPPORT");
            assertThat(props.getWhatsapp().isSmsFallback()).isTrue();

            assertThat(props.isUsable()).isTrue();
            assertThat(context.getBean(MobileNotificationDispatcher.class).isActive()).isTrue();
        });
    }

    @Test
    void staysInactiveWhenCredentialsAreBlankEvenIfChannelsAreEnabled() {
        // The env-var placeholders in application.properties resolve to empty strings when
        // unset, which must not be mistaken for a configured provider.
        runner.withPropertyValues(
                "xamops.messaging.enabled=true",
                "xamops.messaging.base-url=",
                "xamops.messaging.api-key=",
                "xamops.messaging.sms.enabled=true"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MessagingProperties.class).isUsable()).isFalse();
            assertThat(context.getBean(MobileNotificationDispatcher.class).isActive()).isFalse();
        });
    }
}
