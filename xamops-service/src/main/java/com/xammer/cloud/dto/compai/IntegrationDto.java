package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An integration provider from comp.ai's catalog, with connection state. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationDto {
    private String id;
    private String name;
    private String category;
    private String description;
    private boolean connected;
    private String capabilities;   // comma-joined, e.g. "checks"
}
