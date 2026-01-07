package com.xammer.cloud.dto.cloudidr;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CspRegion {
    private String regionCode;
    private String regionType;
    private String availabilityZoneId;
}
