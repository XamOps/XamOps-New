package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmProviderConfigDto {

    private Long id;

    private String provider;

    private String displayName;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    private String awsRegion;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String awsAccessKey;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String awsSecretKey;

    private String status;

    private String proxyBaseUrl;

    private boolean apiKeyConfigured;

    private String trackingToken;

    private String createdAt;
}
