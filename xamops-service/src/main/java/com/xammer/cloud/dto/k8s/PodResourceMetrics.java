package com.xammer.cloud.dto.k8s;

/**
 * DTO for aggregated pod resource metrics across the cluster.
 * Shows total requests, limits, usage, and capacity for CPU and memory.
 */
public class PodResourceMetrics {

    // CPU Metrics (in millicores)
    private double totalCpuRequests;
    private double totalCpuLimits;
    private double totalCpuUsage;
    private double totalCpuCapacity;

    // Memory Metrics (in megabytes)
    private double totalMemoryRequests;
    private double totalMemoryLimits;
    private double totalMemoryUsage;
    private double totalMemoryCapacity;

    // Pod Configuration Statistics
    private int podsWithoutRequests;
    private int podsWithoutLimits;

    // Constructors
    public PodResourceMetrics() {
    }

    // Getters and Setters

    public double getTotalCpuRequests() {
        return totalCpuRequests;
    }

    public void setTotalCpuRequests(double totalCpuRequests) {
        this.totalCpuRequests = totalCpuRequests;
    }

    public double getTotalCpuLimits() {
        return totalCpuLimits;
    }

    public void setTotalCpuLimits(double totalCpuLimits) {
        this.totalCpuLimits = totalCpuLimits;
    }

    public double getTotalCpuUsage() {
        return totalCpuUsage;
    }

    public void setTotalCpuUsage(double totalCpuUsage) {
        this.totalCpuUsage = totalCpuUsage;
    }

    public double getTotalCpuCapacity() {
        return totalCpuCapacity;
    }

    public void setTotalCpuCapacity(double totalCpuCapacity) {
        this.totalCpuCapacity = totalCpuCapacity;
    }

    public double getTotalMemoryRequests() {
        return totalMemoryRequests;
    }

    public void setTotalMemoryRequests(double totalMemoryRequests) {
        this.totalMemoryRequests = totalMemoryRequests;
    }

    public double getTotalMemoryLimits() {
        return totalMemoryLimits;
    }

    public void setTotalMemoryLimits(double totalMemoryLimits) {
        this.totalMemoryLimits = totalMemoryLimits;
    }

    public double getTotalMemoryUsage() {
        return totalMemoryUsage;
    }

    public void setTotalMemoryUsage(double totalMemoryUsage) {
        this.totalMemoryUsage = totalMemoryUsage;
    }

    public double getTotalMemoryCapacity() {
        return totalMemoryCapacity;
    }

    public void setTotalMemoryCapacity(double totalMemoryCapacity) {
        this.totalMemoryCapacity = totalMemoryCapacity;
    }

    public int getPodsWithoutRequests() {
        return podsWithoutRequests;
    }

    public void setPodsWithoutRequests(int podsWithoutRequests) {
        this.podsWithoutRequests = podsWithoutRequests;
    }

    public int getPodsWithoutLimits() {
        return podsWithoutLimits;
    }

    public void setPodsWithoutLimits(int podsWithoutLimits) {
        this.podsWithoutLimits = podsWithoutLimits;
    }
}
