package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpWasteReport {
    private List<GcpWasteItem> items;
    /** True when slow-detector group timed out and results are incomplete. */
    private boolean partial;
}
