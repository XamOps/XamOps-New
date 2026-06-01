package com.xammer.cloud.dto.cicd;

public class CicdDoraDto {
    private double deploymentFrequency;
    private long mttr;
    private double changeFailureRate;

    public CicdDoraDto() {
    }

    public CicdDoraDto(double deploymentFrequency, long mttr, double changeFailureRate) {
        this.deploymentFrequency = deploymentFrequency;
        this.mttr = mttr;
        this.changeFailureRate = changeFailureRate;
    }

    // Getters and Setters
    public double getDeploymentFrequency() {
        return deploymentFrequency;
    }

    public void setDeploymentFrequency(double deploymentFrequency) {
        this.deploymentFrequency = deploymentFrequency;
    }

    public long getMttr() {
        return mttr;
    }

    public void setMttr(long mttr) {
        this.mttr = mttr;
    }

    public double getChangeFailureRate() {
        return changeFailureRate;
    }

    public void setChangeFailureRate(double changeFailureRate) {
        this.changeFailureRate = changeFailureRate;
    }
}
