package com.xammer.cloud.autospotting.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AutoSpottingService;
import com.xammer.cloud.service.SpotEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

/**
 * AC-SPN-11 / XC-11 (plan-phase-05.md task 5.2): with the base + {local, uat, preprod} property
 * sets the legacy SQS listener never polls, and with base + prod it does. Each profile's effective
 * {@code autospotting.enabled} and {@code autospotting.sqs.queue-url} are resolved from the real
 * {@code application*.properties} on the classpath (base first, then the profile overlay, as Spring
 * layers them), and the listener's {@code pollEvents()} is driven to check it only polls for prod.
 */
class SpotEventListenerProfileTest {

    private static final String DEFAULT_ENABLED = "false";   // code default @Value("${autospotting.enabled:false}")
    private static final String DEFAULT_QUEUE = "";          // code default @Value("${autospotting.sqs.queue-url:}")

    static Stream<String> nonProdProfiles() {
        return Stream.of("local", "uat", "preprod");
    }

    private SpotEventListener listenerFor(boolean enabled, String queueUrl, SqsClient sqs) {
        SpotEventListener listener = new SpotEventListener(
                sqs,
                mock(AutoSpottingService.class),
                mock(CloudAccountRepository.class),
                mock(AutoSpottingSettingsRepository.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(listener, "globalEnabled", enabled);
        ReflectionTestUtils.setField(listener, "queueUrl", queueUrl);
        return listener;
    }

    /** Resolve a key across base then a profile overlay (profile wins), falling back to a default. */
    private String resolve(String profile, String key, String def) throws Exception {
        Properties base = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        String value = base.getProperty(key);
        ClassPathResource overlay = new ClassPathResource("application-" + profile + ".properties");
        if (overlay.exists()) {
            Properties p = PropertiesLoaderUtils.loadProperties(overlay);
            if (p.getProperty(key) != null) {
                value = p.getProperty(key);
            }
        }
        return value != null ? value : def;
    }

    @ParameterizedTest
    @MethodSource("nonProdProfiles")
    void listenerNeverPollsOutsideProd(String profile) throws Exception {
        boolean enabled = Boolean.parseBoolean(resolve(profile, "autospotting.enabled", DEFAULT_ENABLED));
        String queue = resolve(profile, "autospotting.sqs.queue-url", DEFAULT_QUEUE);

        SqsClient sqs = mock(SqsClient.class);
        SpotEventListener listener = listenerFor(enabled, queue, sqs);
        listener.pollEvents();

        verify(sqs, never()).receiveMessage(any(software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.class));
        assertThat(mockingDetails(sqs).getInvocations()).as("no SQS call for profile %s", profile).isEmpty();
    }

    @Test
    void basePropertiesAloneNeverPoll() throws Exception {
        // The base profile no longer sets autospotting.enabled or the queue URL, so the code
        // defaults apply: off and blank. No poll.
        boolean enabled = Boolean.parseBoolean(resolve("__none__", "autospotting.enabled", DEFAULT_ENABLED));
        String queue = resolve("__none__", "autospotting.sqs.queue-url", DEFAULT_QUEUE);
        assertThat(enabled).isFalse();
        assertThat(queue).isEmpty();

        SqsClient sqs = mock(SqsClient.class);
        listenerFor(enabled, queue, sqs).pollEvents();
        assertThat(mockingDetails(sqs).getInvocations()).isEmpty();
    }

    @Test
    void listenerPollsWithProdProperties() throws Exception {
        boolean enabled = Boolean.parseBoolean(resolve("prod", "autospotting.enabled", DEFAULT_ENABLED));
        String queue = resolve("prod", "autospotting.sqs.queue-url", DEFAULT_QUEUE);
        assertThat(enabled).as("prod enables the global flag").isTrue();
        assertThat(queue).as("prod names the queue").isNotBlank();

        // A mock SQS that returns no messages: pollEvents() must at least call receiveMessage.
        SqsClient sqs = mock(SqsClient.class);
        org.mockito.Mockito.when(sqs.receiveMessage(any(software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.class)))
                .thenReturn(software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse.builder().build());
        listenerFor(enabled, queue, sqs).pollEvents();
        verify(sqs).receiveMessage(any(software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.class));
    }
}
