package com.xammer.cloud.service.sre;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenSreClientTest {

    private RestTemplate restTemplate;
    private OpenSreClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new OpenSreClient(restTemplate, "http://localhost:2024");
    }

    @Test
    void createThread_returnsThreadId() {
        when(restTemplate.postForObject(eq("http://localhost:2024/threads"), any(), eq(java.util.Map.class)))
            .thenReturn(java.util.Map.of("thread_id", "thread-abc-123"));

        String threadId = client.createThread();
        assertThat(threadId).isEqualTo("thread-abc-123");
    }

    @Test
    void isCompleted_returnsTrueWhenStatusSuccess() {
        when(restTemplate.getForObject(eq("http://localhost:2024/threads/t1/state"), eq(java.util.Map.class)))
            .thenReturn(java.util.Map.of("status", "success", "values", java.util.Map.of()));

        assertThat(client.isCompleted("t1")).isTrue();
    }

    @Test
    void isCompleted_returnsFalseWhenStatusRunning() {
        when(restTemplate.getForObject(eq("http://localhost:2024/threads/t1/state"), eq(java.util.Map.class)))
            .thenReturn(java.util.Map.of("status", "pending", "values", java.util.Map.of()));

        assertThat(client.isCompleted("t1")).isFalse();
    }
}
