package com.xammer.cloud.service.compai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompAiApiClientTest {

    @Test
    void isConfigured_falseWhenApiKeyBlank() {
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), mock(RestTemplate.class));
        client.setApiKey("");
        client.setApiBaseUrl("https://compai-api.example.com/v1");
        assertFalse(client.isConfigured());
    }

    @Test
    void isConfigured_trueWhenApiKeySet() {
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), mock(RestTemplate.class));
        client.setApiKey("test-key");
        client.setApiBaseUrl("https://compai-api.example.com/v1");
        assertTrue(client.isConfigured());
    }

    @Test
    void getJson_sendsXApiKeyHeaderAndParsesBody() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), restTemplate);
        client.setApiKey("test-key");
        client.setApiBaseUrl("https://compai-api.example.com/v1");

        ResponseEntity<String> fakeResponse = ResponseEntity.ok("{\"data\":[{\"id\":\"1\"}]}");
        when(restTemplate.exchange(
                eq(java.net.URI.create("https://compai-api.example.com/v1/controls?perPage=100")),
                eq(HttpMethod.GET),
                any(),
                eq(String.class)))
                .thenReturn(fakeResponse);

        JsonNode result = client.getJson("/controls?perPage=100");

        assertEquals("1", result.path("data").get(0).path("id").asText());
        verify(restTemplate).exchange(any(java.net.URI.class), eq(HttpMethod.GET),
                argThat(entity -> {
                    HttpHeaders headers = (HttpHeaders) entity.getHeaders();
                    return "test-key".equals(headers.getFirst("X-API-Key"));
                }), eq(String.class));
    }

    @Test
    void postJson_sendsSerializedBodyAndParsesResponse() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), restTemplate);
        client.setApiKey("test-key");
        client.setApiBaseUrl("https://compai-api.example.com/v1");

        when(restTemplate.exchange(
                eq(java.net.URI.create("https://compai-api.example.com/v1/policies/pol_1/versions/publish")),
                eq(HttpMethod.POST),
                any(),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"pol_1\",\"status\":\"published\"}"));

        JsonNode result = client.postJson("/policies/pol_1/versions/publish", java.util.Map.of());

        assertEquals("published", result.path("status").asText());
        verify(restTemplate).exchange(any(java.net.URI.class), eq(HttpMethod.POST),
                argThat(entity -> "{}".equals(entity.getBody())), eq(String.class));
    }

    @Test
    void patchJson_sendsSerializedBodyAndParsesResponse() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), restTemplate);
        client.setApiKey("test-key");
        client.setApiBaseUrl("https://compai-api.example.com/v1");

        when(restTemplate.exchange(
                eq(java.net.URI.create("https://compai-api.example.com/v1/tasks/tsk_1")),
                eq(HttpMethod.PATCH),
                any(),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"tsk_1\",\"status\":\"done\"}"));

        JsonNode result = client.patchJson("/tasks/tsk_1", java.util.Map.of("status", "done"));

        assertEquals("done", result.path("status").asText());
        verify(restTemplate).exchange(any(java.net.URI.class), eq(HttpMethod.PATCH),
                argThat(entity -> String.valueOf(entity.getBody()).contains("\"status\":\"done\"")), eq(String.class));
    }

    @Test
    void postJson_returnsEmptyObjectWhenResponseBodyIsBlank() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), restTemplate);
        client.setApiKey("test-key");
        client.setApiBaseUrl("https://compai-api.example.com/v1");

        when(restTemplate.exchange(any(java.net.URI.class), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(""));

        JsonNode result = client.postJson("/vendors", java.util.Map.of("name", "AWS"));
        assertTrue(result.isObject());
        assertEquals(0, result.size());
    }

    @Test
    void deleteJson_sendsDeleteMethodWithApiKeyHeader() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), restTemplate);
        client.setApiKey("test-key");
        client.setApiBaseUrl("https://compai-api.example.com/v1");

        when(restTemplate.exchange(
                eq(java.net.URI.create("https://compai-api.example.com/v1/vendors/ven_1")),
                eq(HttpMethod.DELETE), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(""));

        JsonNode result = client.deleteJson("/vendors/ven_1");

        assertTrue(result.isObject());
        verify(restTemplate).exchange(any(java.net.URI.class), eq(HttpMethod.DELETE),
                argThat(entity -> {
                    HttpHeaders headers = (HttpHeaders) entity.getHeaders();
                    return "test-key".equals(headers.getFirst("X-API-Key"));
                }), eq(String.class));
    }
}
