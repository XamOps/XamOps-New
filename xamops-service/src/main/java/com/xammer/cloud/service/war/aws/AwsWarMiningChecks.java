package com.xammer.cloud.service.war.aws;

import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Rule;
import software.amazon.awssdk.services.guardduty.GuardDutyClient;
import software.amazon.awssdk.services.networkfirewall.NetworkFirewallClient;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.PolicyType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cryptocurrency mining detection and prevention (advisory rows 79-86).
 *
 * <p>
 * The other groups ask "could someone get in?". This one asks "are they already
 * here, and if they are, what stops the bill from growing?" That distinction matters
 * because mining is the most common monetisation of a compromised cloud account and
 * it is unusually detectable: the workload has a signature — flat near-maximum CPU,
 * outbound Stratum connections, sudden GPU instance launches — that legitimate
 * production traffic does not have.
 */
@Component
public class AwsWarMiningChecks {

    /**
     * Stratum protocol ports from the advisory. Egress on these to an arbitrary
     * destination has essentially no legitimate use in a production VPC.
     */
    private static final List<Integer> STRATUM_PORTS = List.of(
            3333, 4444, 5555, 7777, 8333, 9999, 14444, 45700);

    /**
     * Instance family prefixes that are both the most expensive per hour and the most
     * attractive to mine on. An unexpected launch here is the fastest-moving billing
     * event in the whole threat model.
     */
    private static final List<String> HIGH_COST_FAMILIES = List.of(
            "p2", "p3", "p4", "p5", "g3", "g4", "g5", "g6", "trn1", "trn2", "inf1", "inf2", "dl1");

    /** Sustained utilisation above this for a full day looks like mining, not production. */
    private static final double SUSTAINED_CPU_THRESHOLD = 90.0;

    /** Instances sampled for CPU analysis per region — one CloudWatch call each. */
    private static final int CPU_SAMPLE_LIMIT = 40;

    private final AwsClientProvider clients;

    public AwsWarMiningChecks(AwsClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        String primaryRegion = ctx.regions().isEmpty() ? "us-east-1" : ctx.regions().get(0);

        checkInstanceTypeGuardrail(ctx);
        checkBudgetsAndAnomalyDetection(ctx, primaryRegion);
        for (String region : ctx.regions()) {
            checkGuardDutyCryptoFindings(ctx, region);
            checkSustainedUtilisation(ctx, region);
            checkEgressControls(ctx, region);
            checkAutomatedResponse(ctx, region);
            checkFlowLogVisibility(ctx, region);
        }
    }

    /**
     * AWS-79: has GuardDuty already reported mining on this account?
     *
     * <p>
     * This is the one check in WAR that reports an active incident rather than a
     * configuration weakness, so it is deliberately loud.
     */
    private void checkGuardDutyCryptoFindings(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "guardduty:ListFindings",
                List.of("AWS-79"), () -> {
            GuardDutyClient gd = clients.getGuardDutyClient(ctx.account(), region);
            List<String> detectors = gd.listDetectors(r -> {
            }).detectorIds();

            if (detectors.isEmpty()) {
                ctx.merge("AWS-79", WarCheckResult.fail("GuardDuty is not enabled in " + region + ", so "
                        + "CryptoCurrency findings cannot be produced at all").withRegion(region));
                return;
            }

            String detectorId = detectors.get(0);
            var findingIds = gd.listFindings(r -> r
                    .detectorId(detectorId)
                    .findingCriteria(fc -> fc.criterion(java.util.Map.of(
                            "type", software.amazon.awssdk.services.guardduty.model.Condition.builder()
                                    .equalsValue("CryptoCurrency:EC2/BitcoinTool.B",
                                            "CryptoCurrency:EC2/BitcoinTool.B!DNS")
                                    .build(),
                            "service.archived", software.amazon.awssdk.services.guardduty.model.Condition
                                    .builder().equalsValue("false").build())))
                    .maxResults(50)).findingIds();

            if (findingIds.isEmpty()) {
                ctx.merge("AWS-79", WarCheckResult.pass(
                        "GuardDuty is active in " + region + " with no open CryptoCurrency findings"));
                return;
            }

            var findings = gd.getFindings(r -> r.detectorId(detectorId).findingIds(findingIds)).findings();
            List<String> evidence = findings.stream()
                    .map(f -> String.format("%s on %s (severity %.1f, last seen %s)",
                            f.type(),
                            f.resource() == null || f.resource().instanceDetails() == null
                                    ? "unknown resource"
                                    : f.resource().instanceDetails().instanceId(),
                            f.severity() == null ? 0.0 : f.severity(),
                            f.updatedAt()))
                    .toList();

            ctx.merge("AWS-79", WarCheckResult.fail(String.format(
                            "GuardDuty has %d open cryptocurrency finding(s) in %s — treat this as an "
                                    + "active incident, not a configuration gap", findingIds.size(), region))
                    .withEvidence(evidence).withRegion(region));
        });
    }

    /**
     * AWS-80: alarms on sustained utilisation, plus instances that already look like
     * they are mining.
     *
     * <p>
     * The advisory's signature is flat near-maximum CPU over a long window. Production
     * load is bursty; a miner pins the core. Checking average CPU over 24 hours
     * separates the two without needing agent-level visibility.
     */
    private void checkSustainedUtilisation(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "cloudwatch:GetMetricStatistics",
                List.of("AWS-80"), () -> {
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);
            CloudWatchClient cw = clients.getCloudWatchClient(ctx.account(), region);

            List<Instance> running = new ArrayList<>();
            for (var reservation : ec2.describeInstancesPaginator(r -> r.filters(List.of(
                    Filter.builder().name("instance-state-name").values("running").build())))
                    .reservations()) {
                running.addAll(reservation.instances());
            }

            if (running.isEmpty()) {
                ctx.merge("AWS-80", WarCheckResult.notApplicable("No running instances in " + region));
                return;
            }

            Instant end = Instant.now();
            Instant start = end.minus(Duration.ofHours(24));
            List<String> suspicious = new ArrayList<>();

            for (Instance instance : running.stream().limit(CPU_SAMPLE_LIMIT).toList()) {
                Double average = ctx.quiet(null, () -> {
                    var response = cw.getMetricStatistics(r -> r
                            .namespace("AWS/EC2")
                            .metricName("CPUUtilization")
                            .dimensions(Dimension.builder()
                                    .name("InstanceId").value(instance.instanceId()).build())
                            .startTime(start)
                            .endTime(end)
                            .period(3600)
                            .statistics(Statistic.AVERAGE));
                    return response.datapoints().isEmpty() ? null
                            : response.datapoints().stream()
                                    .mapToDouble(d -> d.average() == null ? 0.0 : d.average())
                                    .average().orElse(0.0);
                });

                if (average != null && average >= SUSTAINED_CPU_THRESHOLD) {
                    suspicious.add(String.format(
                            "%s (%s) in %s averaged %.1f%% CPU over 24h — flat near-maximum load is the "
                                    + "mining signature, confirm this is expected",
                            instance.instanceId(), instance.instanceType(), region, average));
                }
            }

            // The alarm matters as much as the current reading: without it, nobody is
            // watching tomorrow.
            boolean cpuAlarm = ctx.quiet(false, () -> cw.describeAlarmsPaginator().metricAlarms().stream()
                    .anyMatch(a -> "CPUUtilization".equals(a.metricName())));

            if (!suspicious.isEmpty()) {
                ctx.merge("AWS-80", WarCheckResult.fail(
                                "Instances show sustained near-maximum CPU consistent with mining")
                        .withEvidence(suspicious).withScanned(running.size()).withRegion(region));
            } else if (!cpuAlarm) {
                ctx.merge("AWS-80", WarCheckResult.fail("No CloudWatch alarm watches CPUUtilization in "
                                + region + " — sustained mining load would go unreported")
                        .withScanned(running.size()).withRegion(region));
            } else {
                ctx.merge("AWS-80", WarCheckResult.pass(String.format(
                        "CPU alarms exist and no instance shows sustained near-maximum load (%d sampled)",
                        Math.min(running.size(), CPU_SAMPLE_LIMIT))));
            }
        });
    }

    /** AWS-82 and AWS-84: can a compromised instance reach a mining pool at all? */
    private void checkEgressControls(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "network-firewall:ListFirewalls",
                List.of("AWS-82", "AWS-84"), () -> {
            NetworkFirewallClient nfw = clients.getNetworkFirewallClient(ctx.account(), region);
            int firewalls = nfw.listFirewalls(r -> {
            }).firewalls().size();

            ctx.merge("AWS-82", firewalls > 0
                    ? WarCheckResult.pass(firewalls + " Network Firewall(s) in " + region + " can enforce "
                            + "Stratum egress denial")
                    : WarCheckResult.fail("No Network Firewall in " + region + " — nothing blocks outbound "
                            + "Stratum ports " + STRATUM_PORTS.stream().map(String::valueOf)
                                    .reduce((a, b) -> a + ", " + b).orElse("")
                            + " to mining pools").withRegion(region));

            // Egress restriction is only half of AWS-84; the other half is whether any
            // security group leaves those ports open outbound in the first place.
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);
            List<String> openEgress = new ArrayList<>();
            ctx.quiet(null, () -> {
                for (var sg : ec2.describeSecurityGroupsPaginator().securityGroups()) {
                    for (var perm : sg.ipPermissionsEgress()) {
                        boolean toWorld = perm.ipRanges().stream()
                                .anyMatch(r -> "0.0.0.0/0".equals(r.cidrIp()));
                        if (!toWorld) {
                            continue;
                        }
                        boolean allPorts = perm.fromPort() == null || perm.toPort() == null;
                        if (allPorts) {
                            openEgress.add(String.format("%s (%s) in %s allows unrestricted outbound to "
                                            + "0.0.0.0/0 on all ports",
                                    sg.groupId(), sg.groupName(), region));
                            break;
                        }
                        for (int port : STRATUM_PORTS) {
                            if (perm.fromPort() <= port && perm.toPort() >= port) {
                                openEgress.add(String.format("%s in %s allows outbound Stratum port %d",
                                        sg.groupId(), region, port));
                            }
                        }
                    }
                }
                return null;
            });

            ctx.merge("AWS-84", WarCheckResult.of(openEgress,
                    "Compromised instances have an unrestricted outbound path to mining pool "
                            + "command-and-control",
                    "Outbound traffic is scoped rather than open to the internet").withRegion(region));
        });
    }

    /** AWS-83: nothing should be able to launch a GPU fleet unnoticed. */
    private void checkInstanceTypeGuardrail(WarScanContext ctx) {
        ctx.guard("organizations:ListPolicies", List.of("AWS-83"), () -> {
            OrganizationsClient org = clients.getOrganizationsClient(ctx.account());
            var policies = org.listPolicies(r -> r.filter(PolicyType.SERVICE_CONTROL_POLICY)).policies();

            boolean restrictsInstanceType = policies.stream().anyMatch(p -> {
                String content = ctx.quiet("", () -> org
                        .describePolicy(r -> r.policyId(p.id())).policy().content());
                return content.contains("ec2:InstanceType");
            });

            // Running GPU instances are not themselves a finding, but combined with no
            // guardrail they are the exact exposure the advisory rates Critical.
            List<String> gpuRunning = new ArrayList<>();
            for (String region : ctx.regions()) {
                ctx.quiet(null, () -> {
                    Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);
                    for (var reservation : ec2.describeInstancesPaginator(r -> r.filters(List.of(
                            Filter.builder().name("instance-state-name").values("running").build())))
                            .reservations()) {
                        for (Instance i : reservation.instances()) {
                            String type = i.instanceTypeAsString() == null ? ""
                                    : i.instanceTypeAsString().toLowerCase(Locale.ROOT);
                            String family = type.contains(".") ? type.substring(0, type.indexOf('.')) : type;
                            if (HIGH_COST_FAMILIES.contains(family)) {
                                gpuRunning.add(String.format("%s is a %s instance in %s",
                                        i.instanceId(), i.instanceTypeAsString(), region));
                            }
                        }
                    }
                    return null;
                });
            }

            if (restrictsInstanceType) {
                ctx.put("AWS-83", WarCheckResult.pass(
                        "An SCP constrains which instance types can be launched"));
            } else if (!gpuRunning.isEmpty()) {
                ctx.put("AWS-83", WarCheckResult.fail(String.format(
                                "No SCP restricts instance types and %d high-cost GPU/accelerator "
                                        + "instance(s) are already running — verify each one is yours",
                                gpuRunning.size()))
                        .withEvidence(gpuRunning));
            } else {
                ctx.put("AWS-83", WarCheckResult.fail("No SCP restricts instance families — an attacker "
                        + "can launch the most expensive GPU instances available"));
            }
        });
    }

    /** AWS-85: an automated containment path, not a human reading email. */
    private void checkAutomatedResponse(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "events:ListRules",
                List.of("AWS-85"), () -> {
            EventBridgeClient events = clients.getEventBridgeClient(ctx.account(), region);
            List<String> matching = new ArrayList<>();

            for (Rule rule : events.listRules(r -> r.limit(100)).rules()) {
                if (!"ENABLED".equalsIgnoreCase(rule.stateAsString())) {
                    continue;
                }
                String pattern = rule.eventPattern() == null ? "" : rule.eventPattern();
                if (pattern.contains("aws.guardduty") || pattern.contains("GuardDuty Finding")) {
                    int targets = ctx.quiet(0, () -> events
                            .listTargetsByRule(r -> r.rule(rule.name())).targets().size());
                    if (targets > 0) {
                        matching.add(String.format("%s in %s routes GuardDuty findings to %d target(s)",
                                rule.name(), region, targets));
                    }
                }
            }

            ctx.merge("AWS-85", matching.isEmpty()
                    ? WarCheckResult.fail("No enabled EventBridge rule routes GuardDuty findings to an "
                            + "automated response in " + region + " — containment waits on a human")
                            .withRegion(region)
                    : WarCheckResult.pass("GuardDuty findings trigger automated response")
                            .withEvidence(matching));
        });
    }

    /** AWS-81: flow logs are the substrate for Athena mining-pool queries. */
    private void checkFlowLogVisibility(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "ec2:DescribeFlowLogs",
                List.of("AWS-81"), () -> {
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);
            var flowLogs = ec2.describeFlowLogs().flowLogs();

            if (flowLogs.isEmpty()) {
                ctx.merge("AWS-81", WarCheckResult.fail("No VPC flow logs in " + region + " — outbound "
                        + "connections to mining pools cannot be queried after the fact")
                        .withRegion(region));
                return;
            }

            // ACCEPT-only logging hides nothing useful for mining detection, but REJECT-only
            // hides the successful pool connection, which is the one that matters.
            List<String> partial = flowLogs.stream()
                    .filter(fl -> "REJECT".equalsIgnoreCase(fl.trafficTypeAsString()))
                    .map(fl -> String.format("%s in %s logs REJECT traffic only, so successful mining-pool "
                            + "connections are not recorded", fl.flowLogId(), region))
                    .toList();

            ctx.merge("AWS-81", partial.isEmpty()
                    ? WarCheckResult.pass(flowLogs.size() + " flow log(s) in " + region
                            + " capture the traffic Athena needs")
                    : WarCheckResult.warn("Flow log coverage is partial").withEvidence(partial)
                            .withRegion(region));
        });
    }

    /** AWS-86: budget and anomaly alerts, the last line of defence on spend. */
    private void checkBudgetsAndAnomalyDetection(WarScanContext ctx, String region) {
        ctx.guard("budgets:DescribeBudgets", List.of("AWS-86"), () -> {
            BudgetsClient budgets = clients.getBudgetsClient(ctx.account());
            int budgetCount = ctx.quiet(0, () -> budgets.describeBudgets(r -> r
                    .accountId(ctx.accountId())).budgets().size());

            int monitors = ctx.quiet(0, () -> {
                CostExplorerClient ce = clients.getCostExplorerClient(ctx.account());
                return ce.getAnomalyMonitors(r -> {
                }).anomalyMonitors().size();
            });

            List<String> gaps = new ArrayList<>();
            if (budgetCount == 0) {
                gaps.add("No AWS Budgets are configured — nothing fires at 50%, 90% or 110% of expected "
                        + "compute spend");
            }
            if (monitors == 0) {
                gaps.add("No Cost Anomaly Detection monitors — a 500-2000% compute spike would not be "
                        + "flagged automatically");
            }

            ctx.put("AWS-86", WarCheckResult.of(gaps,
                    "Spend alerting is incomplete, so mining runs until the invoice arrives",
                    String.format("%d budget(s) and %d anomaly monitor(s) are watching spend",
                            budgetCount, monitors)));
        });
    }
}
