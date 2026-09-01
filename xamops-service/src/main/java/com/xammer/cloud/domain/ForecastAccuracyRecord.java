package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * ForecastAccuracyRecord
 *
 * Stores a single day's ML cost prediction so it can later be compared against
 * the actual realised cost. A daily scheduled job fills in {@code actualCost}
 * and the derived error metrics once the {@code targetDate} has closed.
 *
 * One row = one predicted day for one (provider, account, service) made on a
 * specific forecast date. The unique constraint keeps recording idempotent.
 */
@Entity
@Table(
        name = "forecast_accuracy_record",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_forecast_accuracy_dedup",
                columnNames = {"provider", "account_id", "service_name", "forecast_date", "target_date"}
        ),
        indexes = {
                @Index(name = "idx_fa_status_target", columnList = "status, target_date"),
                @Index(name = "idx_fa_lookup", columnList = "provider, account_id, service_name, target_date")
        }
)
@Data
@NoArgsConstructor
public class ForecastAccuracyRecord {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_EVALUATED = "EVALUATED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String PROVIDER_AWS = "AWS";
    public static final String PROVIDER_GCP = "GCP";
    public static final String PROVIDER_AZURE = "AZURE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String provider; // AWS, GCP, AZURE

    @Column(name = "account_id", nullable = false, length = 128)
    private String accountId;

    /** Normalized service name. "ALL" means an all-services (unfiltered) forecast. */
    @Column(name = "service_name", nullable = false, length = 256)
    private String serviceName;

    /** Date the forecast was generated. */
    @Column(name = "forecast_date", nullable = false)
    private LocalDate forecastDate;

    /** The future day this prediction is for. */
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    /** targetDate - forecastDate, in days (the forecast horizon for this point). */
    @Column(name = "horizon_days", nullable = false)
    private int horizonDays;

    @Column(name = "predicted_cost", nullable = false)
    private double predictedCost;

    @Column(name = "predicted_lower")
    private double predictedLower;

    @Column(name = "predicted_upper")
    private double predictedUpper;

    /** Realised cost for targetDate. Null until evaluated. */
    @Column(name = "actual_cost")
    private Double actualCost;

    /** |predicted - actual|. Null until evaluated. */
    @Column(name = "absolute_error")
    private Double absoluteError;

    /**
     * Absolute Percentage Error = |predicted - actual| / actual * 100.
     * Null when actual is ~0 (undefined) — excluded from MAPE but still counted in MAE.
     */
    @Column(name = "percentage_error")
    private Double percentageError;

    /** Whether actual fell within [predictedLower, predictedUpper]. Null until evaluated. */
    @Column(name = "within_confidence")
    private Boolean withinConfidence;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    /** How many times the evaluation job tried and failed to find an actual. */
    @Column(name = "eval_attempts", nullable = false)
    private int evalAttempts = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    public ForecastAccuracyRecord(String provider, String accountId, String serviceName,
                                  LocalDate forecastDate, LocalDate targetDate,
                                  double predictedCost, double predictedLower, double predictedUpper) {
        this.provider = provider;
        this.accountId = accountId;
        this.serviceName = serviceName;
        this.forecastDate = forecastDate;
        this.targetDate = targetDate;
        this.horizonDays = (int) java.time.temporal.ChronoUnit.DAYS.between(forecastDate, targetDate);
        this.predictedCost = predictedCost;
        this.predictedLower = predictedLower;
        this.predictedUpper = predictedUpper;
        this.status = STATUS_PENDING;
        this.evalAttempts = 0;
        this.createdAt = Instant.now();
    }

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = STATUS_PENDING;
        }
    }
}
