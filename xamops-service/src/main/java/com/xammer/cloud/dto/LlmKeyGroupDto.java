package com.xammer.cloud.dto;

import java.time.Instant;
import java.util.List;

public class LlmKeyGroupDto {

    private Long id;
    private String name;
    private String description;
    private String groupKey;
    private List<Long> providerIds;
    private List<String> providerNames;
    private String loadBalanceStrategy;
    private int keyCount;
    private Instant createdAt;

    public LlmKeyGroupDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }

    public List<Long> getProviderIds() { return providerIds; }
    public void setProviderIds(List<Long> providerIds) { this.providerIds = providerIds; }

    public List<String> getProviderNames() { return providerNames; }
    public void setProviderNames(List<String> providerNames) { this.providerNames = providerNames; }

    public String getLoadBalanceStrategy() { return loadBalanceStrategy; }
    public void setLoadBalanceStrategy(String loadBalanceStrategy) { this.loadBalanceStrategy = loadBalanceStrategy; }

    public int getKeyCount() { return keyCount; }
    public void setKeyCount(int keyCount) { this.keyCount = keyCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
