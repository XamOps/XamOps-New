package com.xammer.cloud.dto.gcp;

import java.util.List;

/**
 * DTO for paginated GCP services grouped by service type.
 * Shows top services sorted by resource count (most used first).
 */
public class GcpServicePaginatedResponse {
    private List<GcpServiceGroupDto> services;
    private int currentPage;
    private int totalServices;
    private int totalPages;
    private int servicesPerPage;
    private int totalResources;
    private boolean hasNext;
    private boolean hasPrevious;

    public GcpServicePaginatedResponse() {
    }

    public GcpServicePaginatedResponse(List<GcpServiceGroupDto> services, int currentPage, int totalServices,
                                       int totalPages, int servicesPerPage, int totalResources,
                                       boolean hasNext, boolean hasPrevious) {
        this.services = services;
        this.currentPage = currentPage;
        this.totalServices = totalServices;
        this.totalPages = totalPages;
        this.servicesPerPage = servicesPerPage;
        this.totalResources = totalResources;
        this.hasNext = hasNext;
        this.hasPrevious = hasPrevious;
    }

    // Getters and Setters
    public List<GcpServiceGroupDto> getServices() {
        return services;
    }

    public void setServices(List<GcpServiceGroupDto> services) {
        this.services = services;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = currentPage;
    }

    public int getTotalServices() {
        return totalServices;
    }

    public void setTotalServices(int totalServices) {
        this.totalServices = totalServices;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public int getServicesPerPage() {
        return servicesPerPage;
    }

    public void setServicesPerPage(int servicesPerPage) {
        this.servicesPerPage = servicesPerPage;
    }

    public int getTotalResources() {
        return totalResources;
    }

    public void setTotalResources(int totalResources) {
        this.totalResources = totalResources;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public void setHasNext(boolean hasNext) {
        this.hasNext = hasNext;
    }

    public boolean isHasPrevious() {
        return hasPrevious;
    }

    public void setHasPrevious(boolean hasPrevious) {
        this.hasPrevious = hasPrevious;
    }

    /**
     * Inner class representing a group of GCP resources by service type.
     */
    public static class GcpServiceGroupDto {
        private String serviceType;
        private int resourceCount;
        private List<GcpResourceDto> resources;

        public GcpServiceGroupDto() {
        }

        public GcpServiceGroupDto(String serviceType, int resourceCount, List<GcpResourceDto> resources) {
            this.serviceType = serviceType;
            this.resourceCount = resourceCount;
            this.resources = resources;
        }

        public String getServiceType() {
            return serviceType;
        }

        public void setServiceType(String serviceType) {
            this.serviceType = serviceType;
        }

        public int getResourceCount() {
            return resourceCount;
        }

        public void setResourceCount(int resourceCount) {
            this.resourceCount = resourceCount;
        }

        public List<GcpResourceDto> getResources() {
            return resources;
        }

        public void setResources(List<GcpResourceDto> resources) {
            this.resources = resources;
        }
    }
}
