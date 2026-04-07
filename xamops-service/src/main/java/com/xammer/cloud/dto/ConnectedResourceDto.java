package com.xammer.cloud.dto;

import java.util.List;
import java.util.Map;

/**
 * Represents a cloud resource that is connected/related to another resource
 * (e.g. an EBS volume attached to an EC2 instance, or a Load Balancer targeting it).
 * Includes any label assignments already on this resource.
 */
public class ConnectedResourceDto {

    private String id;
    private String name;
    private String type;
    private String region;
    private Map<String, String> details;
    private List<ResourceLabelDto> labels;

    public ConnectedResourceDto() {}

    public ConnectedResourceDto(String id, String name, String type, String region,
                                Map<String, String> details, List<ResourceLabelDto> labels) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.region = region;
        this.details = details;
        this.labels = labels;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getDetails() { return details; }
    public void setDetails(Map<String, String> details) { this.details = details; }

    public List<ResourceLabelDto> getLabels() { return labels; }
    public void setLabels(List<ResourceLabelDto> labels) { this.labels = labels; }
}
