package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.PricingService;
import com.xammer.cloud.xampulse.dto.XpCostPerf;
import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.aws.Ec2FactsService;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.MetricSpec;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.QueryScopeFactory;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cost x performance for one instance: its price (AWS Price List, free and cached; or the current Spot price) next to
 * how much of its CPU and memory it used in the range. States what was measured; resizing is for the user, who knows
 * the peaks this range may not contain.
 */
@Service("xpCostPerformanceService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class CostPerformanceService {

    static final double HOURS_PER_MONTH = 730.0;

    private final XamPulseAccessGuard guard;
    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final Ec2FactsService ec2;
    /** Optional: the platform's price service. Without it, use is still shown and no price is stated. */
    private final org.springframework.beans.factory.ObjectProvider<PricingService> pricing;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public CostPerformanceService(XamPulseAccessGuard guard, QueryScopeFactory scopes, ClickHouseExecutor executor,
                                  Ec2FactsService ec2, org.springframework.beans.factory.ObjectProvider<PricingService> pricing) {
        this.guard = guard;
        this.scopes = scopes;
        this.executor = executor;
        this.ec2 = ec2;
        this.pricing = pricing;
    }

    public XpCostPerf view(ClientUserDetails user, Long accountId, String instanceId, Instant from, Instant to) {
        CloudAccount account = guard.require(user, accountId);
        if (account.getAwsAccountId() == null || (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider()))) {
            throw XamPulseException.notFound();
        }
        scopes.build(user, List.of(accountId), from, to);
        XpEc2Facts f = ec2.facts(account, instanceId, null);
        Double hourly = null;
        String basis = null;
        List<String> notes = new ArrayList<>();
        PricingService prices = pricing.getIfAvailable();
        if (prices == null) {
            notes.add("No price shown: pricing is not available in this deployment.");
        } else if ("spot".equalsIgnoreCase(f.lifecycle())) {
            double p = prices.getSpotInstancePrice(f.instanceType(), f.region(), account);
            if (PricingService.isPriceAvailable(p)) {
                hourly = p;
                basis = "SPOT_CURRENT";
            }
        } else if ("Linux/UNIX".equals(f.platform())) {
            double monthly = prices.getEc2InstanceMonthlyPrice(f.instanceType(), f.region());
            if (PricingService.isPriceAvailable(monthly)) {
                hourly = monthly / HOURS_PER_MONTH;
                basis = "ON_DEMAND_LIST";
            }
        } else {
            notes.add("No price shown: list prices are looked up for Linux/UNIX only, and this instance runs "
                    + (f.platform() == null ? "an unknown platform" : f.platform()) + " (licence costs differ).");
        }
        if (hourly == null && notes.isEmpty()) {
            notes.add("No price shown: the AWS price list could not be read for " + f.instanceType() + " in " + f.region() + ".");
        }
        if ("ON_DEMAND_LIST".equals(basis)) {
            notes.add("AWS on-demand list price; Savings Plans, Reserved Instances, EBS and data transfer are not included.");
        }

        QueryScope host = new QueryScope(List.of(accountId), from, to, List.of(), List.of(instanceId), List.of(), List.of());
        TenantScopedQueryBuilder.MetricPlan plan = TenantScopedQueryBuilder.planMetric(host, null);
        double[] cpu = values(host, plan, new MetricSpec("system.cpu.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", "idle"))), true);
        double[] mem = values(host, plan, new MetricSpec("system.memory.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", "used"))), false);
        Duration range = Duration.between(from, to);
        return assemble(instanceId, f, hourly, basis, cpu, mem, range, plan.stepSec(), notes);
    }

    private double[] values(QueryScope scope, TenantScopedQueryBuilder.MetricPlan plan, MetricSpec spec, boolean idle) {
        List<Double> out = new ArrayList<>();
        for (Map<String, Object> r : executor.query(builder.metricSeries(scope, spec, plan))) {
            Double v = Rows.dOrNull(r, "v");
            if (v != null && Double.isFinite(v)) {
                out.add(Math.max(0, Math.min(100, (idle ? 1 - v : v) * 100)));
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static double avg(double[] v) {
        return Arrays.stream(v).average().orElse(Double.NaN);
    }

    /** Nearest-rank p95. */
    static double p95(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[Math.max(0, (int) Math.ceil(0.95 * s.length) - 1)];
    }

    static XpCostPerf assemble(String instanceId, XpEc2Facts f, Double hourly, String basis, double[] cpu, double[] mem,
                               Duration range, int stepSec, List<String> notes) {
        Double cpuAvg = cpu.length == 0 ? null : round(avg(cpu));
        Double cpuP95 = cpu.length == 0 ? null : round(p95(cpu));
        Double memAvg = mem.length == 0 ? null : round(avg(mem));
        Double memP95 = mem.length == 0 ? null : round(p95(mem));
        Double used = cpuAvg == null || f.vcpus() == null ? null : round(f.vcpus() * cpuAvg / 100.0);
        Double perVcpu = hourly == null || used == null || used < 0.01 ? null : Math.round(hourly / used * 10000) / 10000.0;
        String verdict;
        String text;
        String step = stepSec >= 3600 ? (stepSec / 3600) + "-hour" : stepSec >= 60 ? (stepSec / 60) + "-minute" : stepSec + "-second";
        if (cpuP95 == null || memP95 == null || cpu.length < 12) {
            verdict = "NOT_ENOUGH_DATA";
            text = "Not enough CPU and memory data in this range to compare with the instance size.";
        } else if (cpuP95 >= 90 || memP95 >= 90) {
            verdict = "UNDERSIZED";
            text = String.format(Locale.ROOT, "Busy: 95%% of the time CPU was at or below %.0f%% and memory at or below %.0f%%; it runs near its limits.", cpuP95, memP95);
        } else if (f.burstable() && cpuP95 < 40 && memP95 < 50) {
            verdict = "BURSTABLE";
            text = String.format(Locale.ROOT, "Low use (CPU p95 %.0f%%, memory p95 %.0f%%), as expected for a burstable class: check its CPU credits before going smaller.", cpuP95, memP95);
        } else if (cpuP95 < 40 && memP95 < 50) {
            verdict = "OVERSIZED";
            text = String.format(Locale.ROOT, "Mostly idle: 95%% of the time CPU was at or below %.0f%% and memory at or below %.0f%%; a smaller size would likely fit this range.", cpuP95, memP95);
        } else {
            verdict = "FITS";
            text = String.format(Locale.ROOT, "Sized for its load: CPU p95 %.0f%%, memory p95 %.0f%%.", cpuP95, memP95);
        }
        List<String> all = new ArrayList<>(notes);
        all.add("p95 is of " + step + " averages; short spikes inside a step are not in it.");
        if (range.compareTo(Duration.ofDays(7)) < 0 && !"NOT_ENOUGH_DATA".equals(verdict)) {
            all.add("Based on " + human(range) + ". Before resizing, look at at least a week that includes the busiest days.");
        }
        Double memGiB = f.memoryMiB() == null ? null : Math.round(f.memoryMiB() / 1024.0 * 10) / 10.0;
        return new XpCostPerf(instanceId, f.instanceType(), f.vcpus(), memGiB, f.lifecycle(), f.platform(),
                hourly == null ? null : Math.round(hourly * 10000) / 10000.0, hourly == null ? null : Math.round(hourly * HOURS_PER_MONTH * 100) / 100.0, basis,
                cpuAvg, cpuP95, memAvg, memP95, used, perVcpu, verdict, text, all);
    }

    private static Double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 10) / 10.0 : null;
    }

    private static String human(Duration d) {
        long h = d.toHours();
        return h >= 48 ? (h / 24) + " days" : h >= 1 ? h + (h == 1 ? " hour" : " hours") : d.toMinutes() + " minutes";
    }
}
