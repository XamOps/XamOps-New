package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapacityListResponse {

    @JsonProperty(value = "values", access = Access.WRITE_ONLY) // deserialize from CloudiDR; serialized to frontend as "data" via @JsonGetter
    private List<CapacityDescriptionResponse> values;

    @JsonProperty("page")
    private Integer page; // Current page number

    @JsonProperty("pageSize")
    private Integer pageSize; // Records per page

    @JsonProperty("totalElements")
    private Integer totalElements; // Total count of records

    @JsonProperty("totalPageNumbers")
    private Integer totalPageNumbers; // Total number of pages

    // Helper methods for backward compatibility - also exposed to JSON for frontend
    @JsonGetter("data")
    public List<CapacityDescriptionResponse> getData() {
        return values;
    }

    public void setData(List<CapacityDescriptionResponse> data) {
        this.values = data;
    }

    @JsonGetter("total")
    public Integer getTotal() {
        return totalElements;
    }

    public void setTotal(Integer total) {
        this.totalElements = total;
    }
}
