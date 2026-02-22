package com.xammer.cloud.dto.azure;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO for Azure Reservation Management data.
 * Contains Reserved VM Instances, Savings Plans, metrics, and recommendations.
 */
public class AzureReservationData {

    private List<ReservationItem> reservations = new ArrayList<>();
    private List<SavingsPlanItem> savingsPlans = new ArrayList<>();
    private ReservationMetrics metrics = new ReservationMetrics();
    private List<ReservationRecommendation> recommendations = new ArrayList<>();

    // --- Getters and Setters ---

    public List<ReservationItem> getReservations() { return reservations; }
    public void setReservations(List<ReservationItem> reservations) { this.reservations = reservations; }

    public List<SavingsPlanItem> getSavingsPlans() { return savingsPlans; }
    public void setSavingsPlans(List<SavingsPlanItem> savingsPlans) { this.savingsPlans = savingsPlans; }

    public ReservationMetrics getMetrics() { return metrics; }
    public void setMetrics(ReservationMetrics metrics) { this.metrics = metrics; }

    public List<ReservationRecommendation> getRecommendations() { return recommendations; }
    public void setRecommendations(List<ReservationRecommendation> recommendations) { this.recommendations = recommendations; }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    public static class ReservationItem {
        private String reservationId;
        private String displayName;
        private String sku;
        private int quantity;
        private String state;
        private String term;         // P1Y or P3Y
        private String billingPlan;  // Upfront or Monthly
        private String expiryDate;
        private String location;
        private String scope;
        private double utilizationPercentage;

        public String getReservationId() { return reservationId; }
        public void setReservationId(String reservationId) { this.reservationId = reservationId; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }

        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public String getTerm() { return term; }
        public void setTerm(String term) { this.term = term; }

        public String getBillingPlan() { return billingPlan; }
        public void setBillingPlan(String billingPlan) { this.billingPlan = billingPlan; }

        public String getExpiryDate() { return expiryDate; }
        public void setExpiryDate(String expiryDate) { this.expiryDate = expiryDate; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }

        public double getUtilizationPercentage() { return utilizationPercentage; }
        public void setUtilizationPercentage(double utilizationPercentage) { this.utilizationPercentage = utilizationPercentage; }
    }

    public static class SavingsPlanItem {
        private String savingsPlanId;
        private String displayName;
        private String commitment;
        private String paymentOption;
        private String term;
        private String state;
        private String startDate;
        private String expiryDate;
        private String appliedScope;

        public String getSavingsPlanId() { return savingsPlanId; }
        public void setSavingsPlanId(String savingsPlanId) { this.savingsPlanId = savingsPlanId; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public String getCommitment() { return commitment; }
        public void setCommitment(String commitment) { this.commitment = commitment; }

        public String getPaymentOption() { return paymentOption; }
        public void setPaymentOption(String paymentOption) { this.paymentOption = paymentOption; }

        public String getTerm() { return term; }
        public void setTerm(String term) { this.term = term; }

        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }

        public String getExpiryDate() { return expiryDate; }
        public void setExpiryDate(String expiryDate) { this.expiryDate = expiryDate; }

        public String getAppliedScope() { return appliedScope; }
        public void setAppliedScope(String appliedScope) { this.appliedScope = appliedScope; }
    }

    public static class ReservationMetrics {
        private int totalReservations;
        private int activeReservations;
        private int totalSavingsPlans;
        private int activeSavingsPlans;

        public int getTotalReservations() { return totalReservations; }
        public void setTotalReservations(int totalReservations) { this.totalReservations = totalReservations; }

        public int getActiveReservations() { return activeReservations; }
        public void setActiveReservations(int activeReservations) { this.activeReservations = activeReservations; }

        public int getTotalSavingsPlans() { return totalSavingsPlans; }
        public void setTotalSavingsPlans(int totalSavingsPlans) { this.totalSavingsPlans = totalSavingsPlans; }

        public int getActiveSavingsPlans() { return activeSavingsPlans; }
        public void setActiveSavingsPlans(int activeSavingsPlans) { this.activeSavingsPlans = activeSavingsPlans; }
    }

    public static class ReservationRecommendation {
        private String serviceName;
        private double currentMonthlyCost;
        private double estimatedSavingsPercentage;
        private double estimatedMonthlySavings;
        private String recommendedTerm;
        private String reason;

        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }

        public double getCurrentMonthlyCost() { return currentMonthlyCost; }
        public void setCurrentMonthlyCost(double currentMonthlyCost) { this.currentMonthlyCost = currentMonthlyCost; }

        public double getEstimatedSavingsPercentage() { return estimatedSavingsPercentage; }
        public void setEstimatedSavingsPercentage(double estimatedSavingsPercentage) { this.estimatedSavingsPercentage = estimatedSavingsPercentage; }

        public double getEstimatedMonthlySavings() { return estimatedMonthlySavings; }
        public void setEstimatedMonthlySavings(double estimatedMonthlySavings) { this.estimatedMonthlySavings = estimatedMonthlySavings; }

        public String getRecommendedTerm() { return recommendedTerm; }
        public void setRecommendedTerm(String recommendedTerm) { this.recommendedTerm = recommendedTerm; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
