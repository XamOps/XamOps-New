package com.xammer.cloud.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
public class LlmProviderKeyDto {
    private Long id;
    private String provider; // OPENAI, ANTHROPIC, GEMINI, BEDROCK
    private String keyHint;
    private String status;
    private int currentRequests;
    private long totalRequests;
    private BigDecimal totalCost;
    private long totalTokens;
    private Instant createdAt;
    // write-only — only sent from client to server when adding/rotating
    private String apiKey;
}
