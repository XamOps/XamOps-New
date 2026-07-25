package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for creating a risk. Fields mirror comp.ai's CreateRiskDto;
 * category must be one of comp.ai's enum values (customer|fraud|governance|
 * operations|other|people|regulatory|reporting|resilience|technology|
 * vendor_management) — invalid values are rejected by comp.ai itself with a
 * 400, surfaced as-is to the caller. department is free text or one of
 * none|admin|gov|hr|it|itsm|qms.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateRiskRequestDto {
    private String title;
    private String description;
    private String category;
    private String department;
}
