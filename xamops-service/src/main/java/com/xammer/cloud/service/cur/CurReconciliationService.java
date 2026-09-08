package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.Granularity;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compares what a CUR reports against what Cost Explorer reports for the same
 * account and period.
 *
 * <h2>Why this exists</h2>
 * The two sources are queried independently and have to agree, because a single
 * account is served by either depending on the range asked for — a user changing
 * a date filter must not see the total jump. The likeliest cause of a mismatch is
 * semantic rather than transient: {@code GetCostAndUsage} with no filter includes
 * credits, refunds, tax and fees, so a CUR query that filtered to
 * {@code line_item_line_item_type = 'Usage'} would sit permanently below CE by the
 * credit and tax amount on every account that has any. {@link CurCostDataSource}
 * deliberately sums every line item type for this reason, and this service is what
 * proves it.
 *
 * <h2>Always run against a closed month</h2>
 * The open month is a moving target: AWS rewrites it up to three times a day and
 * CE refreshes on its own schedule, so a difference there says nothing about
 * correctness. A closed month is stable on both sides, which makes any remaining
 * difference real.
 */
@Service
public class CurReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(CurReconciliationService.class);

    /**
     * Tolerance, as a fraction of the CE total.
     *
     * <p>Not zero: CE aggregates in its own pipeline and rounds, while a CUR sum is
     * exact to the line item, so cent-level drift on a large bill is expected. Set at
     * 0.5% — tight enough that a missing line item type (credits are routinely
     * several percent) fails, loose enough that rounding does not.
     */
    private static final double TOLERANCE = 0.005;

    private final AwsClientProvider awsClientProvider;
    private final CurCostDataSource curCostDataSource;

    public CurReconciliationService(AwsClientProvider awsClientProvider,
                                    CurCostDataSource curCostDataSource) {
        this.awsClientProvider = awsClientProvider;
        this.curCostDataSource = curCostDataSource;
    }

    /**
     * Reconciles one closed month and reports both totals and the gap.
     *
     * <p>Returns a result rather than throwing on mismatch — the caller is an
     * operator deciding whether to switch an account over, and the numbers are more
     * useful than an exception.
     */
    public Result reconcile(CurSource source, CloudAccount account, YearMonth period) {
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();

        if (YearMonth.now().isBefore(period) || YearMonth.now().equals(period)) {
            throw new IllegalArgumentException(
                    "Reconcile a closed month: " + period + " is the current or a future month, "
                            + "where both sources are still being rewritten");
        }

        double curTotal = curCostDataSource.getTotalCost(source, account, start, end);
        double ceTotal = costExplorerTotal(account, start, end);

        double difference = curTotal - ceTotal;
        double relative = ceTotal == 0.0 ? (curTotal == 0.0 ? 0.0 : 1.0) : Math.abs(difference) / Math.abs(ceTotal);
        boolean withinTolerance = relative <= TOLERANCE;

        if (withinTolerance) {
            logger.info("✅ [CUR] Reconciled {} for account {} — CUR {} vs CE {} ({}%)",
                    period, account.getAwsAccountId(), curTotal, ceTotal, relative * 100);
        } else {
            logger.warn("❌ [CUR] Reconciliation MISMATCH {} for account {} — CUR {} vs CE {} "
                            + "(difference {}, {}%). Do not switch this account until explained.",
                    period, account.getAwsAccountId(), curTotal, ceTotal, difference, relative * 100);
        }

        return new Result(account.getAwsAccountId(), period.toString(), curTotal, ceTotal,
                difference, relative, withinTolerance);
    }

    /**
     * The Cost Explorer total for the same window, built exactly as the production
     * CE path builds it — MONTHLY granularity, UnblendedCost, no filter. Any
     * deviation here would be comparing against something the application never
     * actually shows.
     */
    private double costExplorerTotal(CloudAccount account, LocalDate start, LocalDate end) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder()
                        .start(start.toString())
                        // CE treats end as exclusive.
                        .end(end.plusDays(1).toString())
                        .build())
                .granularity(Granularity.MONTHLY)
                .metrics("UnblendedCost")
                .build();

        return ce.getCostAndUsage(request).resultsByTime().stream()
                .map(r -> r.total().get("UnblendedCost").amount())
                .mapToDouble(Double::parseDouble)
                .sum();
    }

    /** Outcome of one reconciliation, shaped for a JSON response. */
    public record Result(String awsAccountId, String period, double curTotal, double costExplorerTotal,
                         double difference, double relativeDifference, boolean withinTolerance) {

        /** Flat map so the admin endpoint renders stable, readable keys. */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("awsAccountId", awsAccountId);
            map.put("period", period);
            map.put("curTotal", curTotal);
            map.put("costExplorerTotal", costExplorerTotal);
            map.put("difference", difference);
            map.put("relativeDifferencePercent", relativeDifference * 100);
            map.put("withinTolerance", withinTolerance);
            return map;
        }
    }
}
