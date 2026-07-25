package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for creating a vendor. Fields mirror comp.ai's CreateVendorDto;
 * category/status must be one of comp.ai's enum values (e.g. category:
 * cloud|infrastructure|software_as_a_service|finance|marketing|sales|hr|other;
 * status: not_assessed|in_progress|assessed) — invalid values are rejected by
 * comp.ai itself with a 400, surfaced as-is to the caller.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateVendorRequestDto {
    private String name;
    private String description;
    private String category;
    private String status;
}
