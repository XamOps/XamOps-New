package com.xammer.cloud.dto;

import java.util.List;

public class ServicePaginatedResponse {
    private List<ServiceGroupDto> services;
    private int currentPage;
    private int totalServices;
    private int totalPages;
    private int servicesPerPage;
    private boolean hasNext;
    private boolean hasPrevious;

    public ServicePaginatedResponse() {
    }

    public ServicePaginatedResponse(List<ServiceGroupDto> services, int currentPage, int totalServices,
                                   int totalPages, int servicesPerPage, boolean hasNext, boolean hasPrevious) {
        this.services = services;
        this.currentPage = currentPage;
        this.totalServices = totalServices;
        this.totalPages = totalPages;
        this.servicesPerPage = servicesPerPage;
        this.hasNext = hasNext;
        this.hasPrevious = hasPrevious;
    }

    // Getters and Setters
    public List<ServiceGroupDto> getServices() {
        return services;
    }

    public void setServices(List<ServiceGroupDto> services) {
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

    public static class ServiceGroupDto {
        private String serviceType;
        private List<ResourceDto> resources;

        public ServiceGroupDto() {
        }

        public ServiceGroupDto(String serviceType, List<ResourceDto> resources) {
            this.serviceType = serviceType;
            this.resources = resources;
        }

        public String getServiceType() {
            return serviceType;
        }

        public void setServiceType(String serviceType) {
            this.serviceType = serviceType;
        }

        public List<ResourceDto> getResources() {
            return resources;
        }

        public void setResources(List<ResourceDto> resources) {
            this.resources = resources;
        }
    }
}