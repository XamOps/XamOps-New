package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProphetResponseDto {

    @JsonProperty("status")
    private String status;

    @JsonProperty("forecast")
    private List<ForecastDto> forecast;

    @JsonProperty("metadata")
    private Map<String, Object> metadata;
}
