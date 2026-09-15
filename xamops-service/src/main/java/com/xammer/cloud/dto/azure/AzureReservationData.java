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

    /**
     * Priced monthly savings available from reservation purchases.
     *
     * <p>
     * Read by {@code ClientSavingsService} out of the {@code RESERVATION_PAGE::} snapshot, which is
     * why the field name matches its GCP counterpart exactly. Azure had no writer for that key at
     * all, so cross-provider reservation savings were structurally zero for every Azure
     * subscription — and because the reader defaults a missing snapshot to {@code 0.0}, that was
     * indistinguishable from a subscription with genuinely nothing to save.
     *
     * <p>
     * <strong>Excludes indicative recommendations.</strong> Anything flagged
     * {@link ReservationRecommendation#isIndicativeOnly()} is a flat percentage of current spend
     * rather than a per-SKU quote, and feeding those into a savings headline is precisely the kind
     * of invented number this field exists to keep out. They are reported separately below.
     */
    private double totalMonthlySavings;

    /** Indicative (unpriced) savings, kept apart from {@link #totalMonthlySavings}. */
    private double indicativeMonthlySavings;

    /** How many recommendations are indicative only, so the UI can caption the figure. */
    private int indicativeRecommendationCount;

    /**
     * Why {@link #recommendations} is empty, or null when Azure answered normally.
     *
     * <p>
     * An empty recommendation list has two completely different meanings — "Azure analysed your
     * usage and found nothing worth reserving" and "we were never able to ask" — and the UI cannot
     * tell them apart without this. The second case is usually a missing role assignment or a
     * subscription offer that the Consumption API does not serve, both of which are fixable, so
     * saying nothing here strands the customer on an empty tab with no next step.
     */
    private String recommendationsUnavailableReason;

    /**
     * ISO currency of the recommendation figures, or null when Azure did not state one, or stated
     * more than one.
     */
    private String recommendationCurrency;

    /**
     * True when Azure reported recommendations in more than one currency.
     *
     * <p>
     * When it does, {@link #totalMonthlySavings} is not published: adding amounts in different
     * currencies gives a number that is wrong in all of them, and this total feeds the cross-provider
     * savings figure.
     */
    private boolean mixedCurrencies;

    /**
     * Why {@link #totalMonthlySavings} should not be trusted as a comparable figure, or null when it
     * can be. Covers mixed currencies and a non-USD enrollment reporting into a USD group total.
     */
    private String savingsUnavailableReason;

    // --- Getters and Setters ---

    public List<ReservationItem> getReservations() { return reservations; }
    public void setReservations(List<ReservationItem> reservations) { this.reservations = reservations; }

    public double getTotalMonthlySavings() { return totalMonthlySavings; }
    public void setTotalMonthlySavings(double totalMonthlySavings) { this.totalMonthlySavings = totalMonthlySavings; }

    public double getIndicativeMonthlySavings() { return indicativeMonthlySavings; }
    public void setIndicativeMonthlySavings(double v) { this.indicativeMonthlySavings = v; }

    public int getIndicativeRecommendationCount() { return indicativeRecommendationCount; }
    public void setIndicativeRecommendationCount(int v) { this.indicativeRecommendationCount = v; }

    public String getRecommendationsUnavailableReason() { return recommendationsUnavailableReason; }
    public void setRecommendationsUnavailableReason(String reason) { this.recommendationsUnavailableReason = reason; }

    public String getRecommendationCurrency() { return recommendationCurrency; }
    public void setRecommendationCurrency(String recommendationCurrency) { this.recommendationCurrency = recommendationCurrency; }

    public boolean isMixedCurrencies() { return mixedCurrencies; }
    public void setMixedCurrencies(boolean mixedCurrencies) { this.mixedCurrencies = mixedCurrencies; }

    public String getSavingsUnavailableReason() { return savingsUnavailableReason; }
    public void setSavingsUnavailableReason(String reason) { this.savingsUnavailableReason = reason; }

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

        /**
         * Percentage of the reservation actually consumed, or null when it is not known.
         *
         * <p>
         * Nullable on purpose. This was a primitive {@code double} defaulted to {@code 0.0}, which
         * made "Azure did not tell us" indistinguishable from "this reservation is completely
         * unused" — the second of which is an urgent, expensive problem and the first of which is
         * nothing. The reservations list endpoint does not carry consumption; that needs the
         * Consumption reservation-summaries API at a billing scope, which is not available on every
         * subscription type.
         */
        private Double utilizationPercentage;

        /** Why {@link #utilizationPercentage} is null, or null when it is known. */
        private String utilizationUnavailableReason;

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

        public Double getUtilizationPercentage() { return utilizationPercentage; }
        public void setUtilizationPercentage(Double utilizationPercentage) { this.utilizationPercentage = utilizationPercentage; }

        public String getUtilizationUnavailableReason() { return utilizationUnavailableReason; }
        public void setUtilizationUnavailableReason(String reason) { this.utilizationUnavailableReason = reason; }
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

    /**
     * A single reservation purchase recommendation as returned by Azure's Consumption
     * {@code reservationRecommendations} API.
     *
     * <p>
     * Every monetary field here is a per-SKU figure Azure computed from this subscription's own
     * metered usage at its own negotiated rates, normalised to a 30-day month. It replaces a flat
     * 20%-of-spend estimate that was applied to whole billing service lines: that number was not
     * derived from any rate card, it was the same for every service, and it was attached to the
     * total spend of a service rather than to the reservable portion of it — so both the base and
     * the rate were wrong.
     */
    public static class ReservationRecommendation {

        /** Azure's own identifier for the recommendation, for drill-through and de-duplication. */
        private String recommendationId;

        /** Human label derived from {@link #resourceType}, e.g. "Virtual Machines". */
        private String serviceName;

        /** Raw Azure resource type, e.g. {@code virtualmachines}, {@code sqldatabases}. */
        private String resourceType;

        /** The specific SKU to reserve, e.g. {@code Standard_D2ds_v4}. */
        private String sku;

        /** Azure region the recommendation applies to, when Azure reported one. */
        private String region;

        /** {@code Single} (this subscription) or {@code Shared} (billing scope). */
        private String scope;

        /** How many of {@link #sku} Azure recommends reserving. */
        private Integer recommendedQuantity;

        /** Projected 30-day cost of this usage on pay-as-you-go rates. */
        private double currentMonthlyCost;

        /** Projected 30-day cost of the same usage with the reservation in place. */
        private double reservedMonthlyCost;

        /** {@link #currentMonthlyCost} minus {@link #reservedMonthlyCost}, as a percentage. */
        private double estimatedSavingsPercentage;

        /** Azure's {@code netSavings} for this SKU, normalised to a 30-day month. */
        private double estimatedMonthlySavings;

        /** Term as Azure reports it, e.g. {@code P1Y} or {@code P3Y}. */
        private String recommendedTerm;

        /**
         * The other term Azure costed for the same SKU, when it costed more than one.
         *
         * <p>
         * Azure returns one row per term, and a one-year and three-year recommendation for the same
         * SKU are alternatives, not additions. Summing them would roughly double the reported
         * savings, so only the better term becomes a recommendation and the runner-up is carried
         * here for comparison.
         */
        private String alternativeTerm;

        /** Monthly savings of {@link #alternativeTerm}, for side-by-side comparison. */
        private Double alternativeTermMonthlySavings;

        /** ISO currency, or null on the legacy response shape which does not state one. */
        private String currency;

        /** Days of usage Azure analysed: 7, 30 or 60. */
        private int lookbackDays;

        /** First usage date Azure considered, as reported. */
        private String firstUsageDate;

        private String reason;

        /**
         * True when {@link #estimatedMonthlySavings} is not a priced per-SKU figure.
         *
         * <p>
         * Now only set when Azure returned a recommendation whose savings amount could not be read
         * — the row is still worth showing, but its number must not be summed into a headline
         * total. Consumers must keep excluding these from any savings figure a customer plans
         * against.
         */
        private boolean indicativeOnly;

        public boolean isIndicativeOnly() { return indicativeOnly; }
        public void setIndicativeOnly(boolean indicativeOnly) { this.indicativeOnly = indicativeOnly; }

        public String getRecommendationId() { return recommendationId; }
        public void setRecommendationId(String recommendationId) { this.recommendationId = recommendationId; }

        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }

        public String getResourceType() { return resourceType; }
        public void setResourceType(String resourceType) { this.resourceType = resourceType; }

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }

        public Integer getRecommendedQuantity() { return recommendedQuantity; }
        public void setRecommendedQuantity(Integer recommendedQuantity) { this.recommendedQuantity = recommendedQuantity; }

        public double getCurrentMonthlyCost() { return currentMonthlyCost; }
        public void setCurrentMonthlyCost(double currentMonthlyCost) { this.currentMonthlyCost = currentMonthlyCost; }

        public double getReservedMonthlyCost() { return reservedMonthlyCost; }
        public void setReservedMonthlyCost(double reservedMonthlyCost) { this.reservedMonthlyCost = reservedMonthlyCost; }

        public double getEstimatedSavingsPercentage() { return estimatedSavingsPercentage; }
        public void setEstimatedSavingsPercentage(double estimatedSavingsPercentage) { this.estimatedSavingsPercentage = estimatedSavingsPercentage; }

        public double getEstimatedMonthlySavings() { return estimatedMonthlySavings; }
        public void setEstimatedMonthlySavings(double estimatedMonthlySavings) { this.estimatedMonthlySavings = estimatedMonthlySavings; }

        public String getRecommendedTerm() { return recommendedTerm; }
        public void setRecommendedTerm(String recommendedTerm) { this.recommendedTerm = recommendedTerm; }

        public String getAlternativeTerm() { return alternativeTerm; }
        public void setAlternativeTerm(String alternativeTerm) { this.alternativeTerm = alternativeTerm; }

        public Double getAlternativeTermMonthlySavings() { return alternativeTermMonthlySavings; }
        public void setAlternativeTermMonthlySavings(Double v) { this.alternativeTermMonthlySavings = v; }

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }

        public int getLookbackDays() { return lookbackDays; }
        public void setLookbackDays(int lookbackDays) { this.lookbackDays = lookbackDays; }

        public String getFirstUsageDate() { return firstUsageDate; }
        public void setFirstUsageDate(String firstUsageDate) { this.firstUsageDate = firstUsageDate; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
