package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO for holding historical reservation utilization and coverage data.
 * ✅ FIXED: All collections initialized as mutable for Redis serialization
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalReservationDataDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Time labels (e.g., ["Jan 2023", "Feb 2023", "Mar 2023"])
     */
    private List<String> labels = new ArrayList<>();

    /**
     * Utilization percentages for each period
     */
    private List<Double> utilizationPercentages = new ArrayList<>();

    /**
     * Coverage percentages for each period
     */
    private List<Double> coveragePercentages = new ArrayList<>();

}
