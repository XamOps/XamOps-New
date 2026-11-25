package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import jakarta.validation.constraints.NotNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpSetupPlanRequest(
        @NotNull Bucket bucket, @NotNull Kms kms, @NotNull String homeRegion, @NotNull List<RegionSelection> regions,
        @NotNull XpRetention retention, Sources sources) {
    public record Bucket(@NotNull String mode, String name, @NotNull String region) {}
    public record Kms(@NotNull String mode, String keyArn) {}
    public record RegionSelection(@NotNull String region, @NotNull String targetMode, List<String> instanceIds, String tagKey,
                                  String tagValue, Boolean confirmAllManaged) {}
    public record CustomSource(String path, String parser) {}
    public record Sources(boolean system, boolean docker, List<CustomSource> custom) {}
}
