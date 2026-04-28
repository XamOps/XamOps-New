package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpWasteItem {
    /** Unique resource identifier used for delete operations (name, ID, or full resource path). */
    private String resourceId;

    /** Human-readable display name. */
    private String resourceName;

    /** Resource category, e.g. "Idle Persistent Disk", "Unused IP Address". */
    private String type;

    /** Region or zone, e.g. "us-central1", "europe-west1-b", "global". */
    private String location;

    /** Estimated monthly savings in USD. */
    private double monthlySavings;

    /** Human-readable reason this resource is considered waste. */
    private String reason;
}
