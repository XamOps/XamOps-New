package com.xammer.cloud.service.autospotting.aws;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsClientProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DataUnavailableException;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetReservationUtilizationRequest;
import software.amazon.awssdk.services.costexplorer.model.GetSavingsPlansUtilizationRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Reserved Instance and Savings Plans utilization for the {@code COMMITMENT_UTILIZATION} warning
 * (design-aws.md §7.3, AWS-15). Utilization, not coverage. Read through the account's existing read
 * role (Cost Explorer is billed per request, so the result is cached per account for 24 h and a list
 * never makes a Cost Explorer request per group, NFR-04). Only successes are cached.
 *
 * <p>An account with no commitments yields an empty result, which passes the check. A failed read
 * throws {@link AwsReadException}, which the evaluator turns into a BLOCKED {@code CHECK_FAILED}.
 */
@Component
public class AwsCommitmentUtilization {

    static final Duration TTL = Duration.ofHours(24);
    static final int WINDOW_DAYS = 30;

    private final AwsClientProvider clients;
    private final Clock clock;
    private final double thresholdPercent;
    private final Cache<String, Optional<Double>> cache = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(TTL).build();

    @Autowired
    public AwsCommitmentUtilization(AwsClientProvider clients,
            @Value("${autospotting.aws.commitment.utilization-threshold-percent:80}") double thresholdPercent) {
        this(clients, thresholdPercent, Clock.systemUTC());
    }

    AwsCommitmentUtilization(AwsClientProvider clients, double thresholdPercent, Clock clock) {
        this.clients = clients;
        this.thresholdPercent = thresholdPercent;
        this.clock = clock;
    }

    /** The utilization percent below which the warning shows. */
    public double thresholdPercent() {
        return thresholdPercent;
    }

    /** The lowest utilization across RI and Savings Plans over the last 30 days, or empty when none exist. */
    public Optional<Double> utilization(CloudAccount account) {
        String key = String.valueOf(account.getAwsAccountId());
        Optional<Double> cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Optional<Double> fresh = AwsErrors.read("COMMITMENT_UTILIZATION", () -> read(account));
        cache.put(key, fresh);
        return fresh;
    }

    private Optional<Double> read(CloudAccount account) {
        CostExplorerClient ce = clients.getCostExplorerClient(account);
        LocalDate end = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        DateInterval period = DateInterval.builder().start(end.minusDays(WINDOW_DAYS).toString())
                .end(end.toString()).build();
        Double ri = null;
        try {
            var total = ce.getReservationUtilization(GetReservationUtilizationRequest.builder()
                    .timePeriod(period).build()).total();
            if (total != null && positive(total.purchasedHours())) {
                ri = parse(total.utilizationPercentage());
            }
        } catch (DataUnavailableException e) {
            // no reservation data: no commitments of this kind
        }
        Double sp = null;
        try {
            var total = ce.getSavingsPlansUtilization(GetSavingsPlansUtilizationRequest.builder()
                    .timePeriod(period).build()).total();
            if (total != null && total.utilization() != null && positive(total.utilization().totalCommitment())) {
                sp = parse(total.utilization().utilizationPercentage());
            }
        } catch (DataUnavailableException e) {
            // no Savings Plans data
        }
        if (ri == null) {
            return Optional.ofNullable(sp);
        }
        return Optional.of(sp == null ? ri : Math.min(ri, sp));
    }

    private static boolean positive(String number) {
        Double d = parse(number);
        return d != null && d > 0;
    }

    private static Double parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
