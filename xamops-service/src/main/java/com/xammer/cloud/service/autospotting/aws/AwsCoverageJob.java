package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventProperties;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventStore;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventStore.CoverageRow;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.eventbridge.model.RuleState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code coverage:aws} job (design-aws.md §9.4, PO-22, plan-phase-22.md task 22.4). For each connected AWS
 * account with a recorded stack it reads the stack outputs {@code AutoSpottingEventRegions},
 * {@code AutoSpottingEventRegionsSkipped} and {@code AutoSpottingForwarderRoleId} through
 * {@code cloudformation:DescribeStacks} (stored {@code stack_id} and {@code stack_region}), stores the role id
 * in {@code cloud_account.as_forwarder_role_id} for the sender check, confirms the interruption rule of each
 * configured region with {@code events:DescribeRule}, and writes {@code autospotting_region_coverage}:
 * {@code COVERED}, {@code MISSING} (no enabled rule), {@code SKIPPED} (the fan-out could not create it) or
 * {@code UNKNOWN} (the check failed, which blocks like missing).
 *
 * <p>The tick runs hourly under the job lease and checks an account when it is due: never checked, last
 * checked over a day ago, or its stack was diagnosed after the last check (the stack-version change trigger:
 * the diagnosis is what records a new template version). Read only; nothing here mutates a cloud.
 */
@Component
public class AwsCoverageJob {

    private static final Logger log = LoggerFactory.getLogger(AwsCoverageJob.class);

    public static final String JOB = "coverage:aws";
    static final String OUT_REGIONS = "AutoSpottingEventRegions";
    static final String OUT_SKIPPED = "AutoSpottingEventRegionsSkipped";
    static final String OUT_ROLE_ID = "AutoSpottingForwarderRoleId";
    /** The rule the fan-out creates per region for the Spot interruption warning (events nested stack). */
    static final String INTERRUPTION_RULE = "xamops-as-spot-interruption";
    static final Duration MAX_AGE = Duration.ofHours(24);

    private final CloudAccountRepository accounts;
    private final AwsViewSource views;
    private final AwsEventStore store;
    private final AwsEventProperties properties;
    private final JobRunner jobRunner;
    private final LeaseProperties leaseProperties;

    public AwsCoverageJob(CloudAccountRepository accounts, AwsViewSource views, AwsEventStore store,
                          AwsEventProperties properties, JobRunner jobRunner, LeaseProperties leaseProperties) {
        this.accounts = accounts;
        this.views = views;
        this.store = store;
        this.properties = properties;
        this.jobRunner = jobRunner;
        this.leaseProperties = leaseProperties;
    }

    /** Hourly tick; the body runs on a {@code spot-jobs-} thread under the {@value #JOB} lease. */
    @Scheduled(scheduler = "spotTaskScheduler", fixedDelayString = "${autospotting.aws.coverage.interval-ms:3600000}",
            initialDelayString = "${autospotting.aws.coverage.initial-delay-ms:600000}")
    public void scheduledTick() {
        jobRunner.runLeased(JOB, null, leaseProperties.reconcilerTtl(), leaseProperties.reconcilerHeartbeat(),
                Duration.ZERO, this::runDue);
    }

    /** Check every due account; returns how many were checked. Public for the tests. */
    public int runDue() {
        int checked = 0;
        for (CloudAccount account : accounts.findByProvider("AWS")) {
            if (!"CONNECTED".equals(account.getStatus()) || blank(account.getStackId())) {
                continue;
            }
            try {
                if (due(account, Instant.now())) {
                    checkAccount(account);
                    checked++;
                }
            } catch (RuntimeException e) {
                log.warn("coverage check failed for account {}: {}", account.getId(), e.toString());
            }
        }
        return checked;
    }

    boolean due(CloudAccount account, Instant now) {
        List<CoverageRow> rows = store.coverage(account.getId());
        if (rows.isEmpty()) {
            return true;
        }
        Instant last = rows.stream().map(CoverageRow::checkedAt).max(Instant::compareTo).orElse(Instant.EPOCH);
        return last.isBefore(now.minus(MAX_AGE))
                || (account.getStackCheckedAt() != null && account.getStackCheckedAt().isAfter(last));
    }

    /** One account: read the stack, probe each region, write the rows. */
    public void checkAccount(CloudAccount account) {
        Instant now = Instant.now();
        String stackRegion = blank(account.getStackRegion()) ? "us-east-1" : account.getStackRegion();
        Map<String, String> outputs;
        try {
            outputs = readOutputs(account, stackRegion);
        } catch (RuntimeException e) {
            // Fail closed: the stack could not be read, so nothing is verified.
            markUnknown(account, now);
            return;
        }
        String roleId = outputs.get(OUT_ROLE_ID);
        if (!blank(roleId) && !roleId.equals(account.getAsForwarderRoleId())) {
            store.storeForwarderRoleId(account.getId(), roleId);
            account.setAsForwarderRoleId(roleId);
        }
        List<String> configured = split(outputs.get(OUT_REGIONS));
        List<String> skipped = split(outputs.get(OUT_SKIPPED));
        String ruleName = INTERRUPTION_RULE + properties.suffix().orElse("");
        Set<String> seen = new LinkedHashSet<>();
        for (String region : configured) {
            seen.add(region);
            store.upsertCoverage(account.getId(), region, probe(account, region, ruleName),
                    "[\"" + ruleName + "\"]", now);
        }
        for (String region : skipped) {
            if (seen.add(region)) {
                store.upsertCoverage(account.getId(), region, "SKIPPED", null, now);
            }
        }
        store.deleteCoverageNotIn(account.getId(), new ArrayList<>(seen));
    }

    private Map<String, String> readOutputs(CloudAccount account, String stackRegion) {
        AwsReadOnlyView view = views.view(account, stackRegion);
        List<Stack> stacks = AwsErrors.read("DescribeStacks", () -> view.cloudFormation()
                .describeStacks(r -> r.stackName(account.getStackId())).stacks());
        Map<String, String> out = new HashMap<>();
        if (stacks.isEmpty()) {
            throw new AwsReadException("DescribeStacks", com.xammer.cloud.service.autospotting.spi.ErrorKind.NOT_FOUND);
        }
        for (Output o : stacks.get(0).outputs()) {
            out.put(o.outputKey(), o.outputValue());
        }
        return out;
    }

    /** COVERED when the region's interruption rule exists and is enabled, MISSING when it does not, else UNKNOWN. */
    private String probe(CloudAccount account, String region, String ruleName) {
        try {
            AwsReadOnlyView view = views.view(account, region);
            RuleState state = AwsErrors.read("DescribeRule", () -> view.eventBridge()
                    .describeRule(r -> r.name(ruleName)).state());
            return state == RuleState.ENABLED ? "COVERED" : "MISSING";
        } catch (AwsReadException e) {
            return e.kind() == com.xammer.cloud.service.autospotting.spi.ErrorKind.NOT_FOUND ? "MISSING" : "UNKNOWN";
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    private void markUnknown(CloudAccount account, Instant now) {
        for (CoverageRow row : store.coverage(account.getId())) {
            store.upsertCoverage(account.getId(), row.region(), "UNKNOWN", row.ruleNames(), now);
        }
    }

    /** A comma-separated output value; {@code none} and blanks are no regions. */
    static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        if (blank(value) || "none".equalsIgnoreCase(value.trim())) {
            return out;
        }
        for (String part : value.split(",")) {
            String region = part.trim();
            if (region.matches("[a-z]{2}(-[a-z]+)+-\\d") && !out.contains(region)) {
                out.add(region);
            }
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
