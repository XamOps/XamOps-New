package com.xammer.cloud.service.sre;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenSreClientTest {

    private RestTemplate restTemplate;
    private OpenSreClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new OpenSreClient(restTemplate, "http://localhost:2024", "test-api-key");
    }

    @Test
    void createThread_returnsThreadId() {
        ResponseEntity<Map> response = new ResponseEntity<>(Map.of("thread_id", "thread-abc-123"), HttpStatus.OK);
        when(restTemplate.exchange(eq("http://localhost:2024/threads"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(response);

        String threadId = client.createThread();
        assertThat(threadId).isEqualTo("thread-abc-123");
    }

    @Test
    void isCompleted_returnsTrueWhenStatusIdleWithValues() {
        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("status", "idle", "values", Map.of("summary", "ok")), HttpStatus.OK);
        when(restTemplate.exchange(eq("http://localhost:2024/threads/t1"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(response);

        assertThat(client.isCompleted("t1")).isTrue();
    }

    @Test
    void isCompleted_returnsFalseWhenStatusBusy() {
        ResponseEntity<Map> response = new ResponseEntity<>(Map.of("status", "busy"), HttpStatus.OK);
        when(restTemplate.exchange(eq("http://localhost:2024/threads/t1"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(response);

        assertThat(client.isCompleted("t1")).isFalse();
    }

    @Test
    void isCompleted_returnsFalseWhenStatusIdleButEmptyValues() {
        ResponseEntity<Map> response = new ResponseEntity<>(
                Map.of("status", "idle", "values", Map.of()), HttpStatus.OK);
        when(restTemplate.exchange(eq("http://localhost:2024/threads/t1"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(response);

        assertThat(client.isCompleted("t1")).isFalse();
    }
}
