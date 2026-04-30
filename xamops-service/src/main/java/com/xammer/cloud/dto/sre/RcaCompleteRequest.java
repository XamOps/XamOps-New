package com.xammer.cloud.dto.sre;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RcaCompleteRequest {

    @JsonProperty("incident_id")
    private String incidentId;

    @JsonProperty("version")
    private int version;

    @JsonProperty("report_summary")
    private String reportSummary;

    @JsonProperty("report_root_cause")
    private String reportRootCause;

    @JsonProperty("report_recommendations")
    private List<String> reportRecommendations;

    @JsonProperty("report_full_markdown")
    private String reportFullMarkdown;

    @JsonProperty("is_noise")
    private boolean noise;
}
