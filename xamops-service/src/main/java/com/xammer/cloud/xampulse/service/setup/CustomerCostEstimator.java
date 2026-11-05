package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.dto.XpCostEstimate;
import com.xammer.cloud.xampulse.dto.XpRetention;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rough monthly AWS bill the customer takes on for the archive, shown before deploy so "no licence cost" is never
 * read as "no AWS bill". It is an estimate from stated assumptions, not a measurement.
 */
@Component("xpCustomerCostEstimator")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class CustomerCostEstimator {

    /** Compressed archive volume per instance per day, an assumption (plan capacity note). */
    static final double GB_PER_INSTANCE_DAY = 0.15;
    /** us-east-1 list prices, USD per GB-month. */
    static final double STANDARD = 0.023;
    static final double STANDARD_IA = 0.0125;
    static final double GLACIER_IR = 0.004;
    static final double KMS_KEY_MONTH = 1.0;

    public XpCostEstimate estimate(int instances, XpRetention r, boolean createsKey) {
        int n = Math.max(0, instances);
        double daily = n * GB_PER_INSTANCE_DAY;
        int std = r.iaAfterDays();
        int ia = Math.max(0, r.glacierIrAfterDays() - r.iaAfterDays());
        int gir = Math.max(0, r.expireAfterDays() - r.glacierIrAfterDays());
        double s3 = daily * (std * STANDARD + ia * STANDARD_IA + gir * GLACIER_IR);
        double kms = createsKey ? KMS_KEY_MONTH : 0.0;
        return new XpCostEstimate(round(s3), kms, List.of(
                "Assumes about " + GB_PER_INSTANCE_DAY + " GB of compressed logs per instance per day for " + n + " instance(s), at steady state.",
                "S3 prices are us-east-1 list prices per GB-month. Requests and retrieval fees are extra.",
                "A KMS key costs about $1 per month plus request charges. Systems Manager State Manager has no charge in-region.",
                "Data transfer into AWS is free; egress from your instances is not included."));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
