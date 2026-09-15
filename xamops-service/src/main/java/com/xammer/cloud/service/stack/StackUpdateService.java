package com.xammer.cloud.service.stack;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.Change;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.cloudformation.model.CreateChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ExecuteChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings a connected account's CloudFormation stack onto what this environment expects,
 * without anyone opening the AWS console.
 *
 * <p>Parameter values are stored per stack, so a template default never reaches a stack that
 * already exists — the only way to change one is an update that passes the value. Until now
 * that meant reading a URL off a screen and typing it into a console form, which is how an
 * environment ends up pointed at another environment's queue. That failure is silent: events
 * go to a queue nobody reads and resources merely appear a minute late.
 *
 * <p>Every update goes through a change set rather than {@code UpdateStack} directly. This
 * stack owns the IAM role the whole connection depends on, so showing what will change before
 * it changes is the safety story, and "no changes" comes back as a first-class answer instead
 * of an error.
 */
@Service
public class StackUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(StackUpdateService.class);

    /**
     * Named IAM because the templates create roles with fixed names, and auto-expand because
     * the connection stack nests the inventory and CUR stacks. Missing either is rejected at
     * change-set creation with a message that names neither.
     */
    private static final List<Capability> CAPABILITIES =
            List.of(Capability.CAPABILITY_NAMED_IAM, Capability.CAPABILITY_AUTO_EXPAND);

    /**
     * The role CloudFormation assumes to apply the change set, created by
     * xamops-stack-updater.yml.
     *
     * <p>Passed on every change set. Without it CloudFormation executes as the caller, which
     * would mean granting the XamOps connection role iam:CreateRole, s3:CreateBucket and the
     * rest — administrator in all but name. With it XamOps holds only change-set actions plus
     * iam:PassRole on this one role.
     *
     * <p>An account that has not deployed the updater stack fails at CreateChangeSet with a
     * message naming this role, which the caller turns into a link to deploy it.
     */
    private static final String UPDATER_ROLE_NAME = "XamOpsStackUpdaterRole";

    /** How long to wait for a change set to finish computing before giving up. */
    private static final int CHANGE_SET_POLL_ATTEMPTS = 30;
    private static final long CHANGE_SET_POLL_MS = 1000;

    private final AwsClientProvider awsClientProvider;
    private final StackDiagnosisService diagnosisService;
    private final String inventoryQueueUrl;

    public StackUpdateService(AwsClientProvider awsClientProvider,
                              StackDiagnosisService diagnosisService,
                              @Value("${xamops.inventory.events.queue-url:}") String inventoryQueueUrl) {
        this.awsClientProvider = awsClientProvider;
        this.diagnosisService = diagnosisService;
        this.inventoryQueueUrl = inventoryQueueUrl;
    }

    /** One resource the change set will touch. */
    public record ResourceChange(String logicalId, String resourceType, String action,
                                 String replacement) {
    }

    /** One parameter this update would move, so the reader sees the cause beside the effect. */
    public record ParameterChange(String name, String from, String to) {
    }

    /**
     * What executing would do.
     *
     * @param changeSetId null when there is nothing to change
     * @param noChanges   true when the stack already matches — not a failure
     */
    public record Preview(String changeSetId, String stackName, String stackId, String region,
                          boolean noChanges, List<ParameterChange> parameterChanges,
                          List<ResourceChange> resourceChanges, String detail) {
    }

    /**
     * Builds a change set and describes it without executing.
     *
     * <p>Deliberately leaves the change set in place: executing it later is what makes the
     * preview meaningful. Re-previewing creates a new one rather than reusing the old, because
     * the stack may have moved in between.
     */
    public Preview preview(CloudAccount account) {
        StackDiagnosisService.Diagnosis diagnosis = diagnosisService.diagnose(account);
        if (!diagnosis.updatable()) {
            throw new StackUpdateException(diagnosis.verdict().name(),
                    diagnosis.detail(), diagnosis.remedy());
        }

        String region = diagnosis.stackRegion();
        CloudFormationClient cfn = awsClientProvider.getCloudFormationClient(account, region);
        Stack stack = cfn.describeStacks(DescribeStacksRequest.builder()
                .stackName(diagnosis.stackId()).build()).stacks().get(0);

        Map<String, String> desired = desiredParameters();
        List<ParameterChange> parameterChanges = new ArrayList<>();
        List<Parameter> parameters = new ArrayList<>();

        for (Parameter existing : stack.parameters()) {
            String key = existing.parameterKey();
            String want = desired.get(key);
            if (want == null) {
                // Everything this environment does not dictate keeps what the stack has —
                // ExternalId above all, because rewriting it changes the role's trust policy
                // and disconnects the account with nothing failing to say so.
                parameters.add(Parameter.builder()
                        .parameterKey(key).usePreviousValue(true).build());
                continue;
            }
            if (!want.equals(existing.parameterValue())) {
                parameterChanges.add(new ParameterChange(key, existing.parameterValue(), want));
            }
            parameters.add(Parameter.builder().parameterKey(key).parameterValue(want).build());
        }

        String changeSetName = "xamops-" + Instant.now().toEpochMilli();
        cfn.createChangeSet(CreateChangeSetRequest.builder()
                .stackName(diagnosis.stackId())
                .changeSetName(changeSetName)
                .changeSetType(ChangeSetType.UPDATE)
                .usePreviousTemplate(true)
                .capabilitiesWithStrings(CAPABILITIES.stream().map(Capability::toString).toList())
                .roleARN(updaterRoleArn(account))
                .parameters(parameters)
                .build());

        DescribeChangeSetResponse described = awaitChangeSet(cfn, diagnosis.stackId(), changeSetName);

        // CloudFormation reports "nothing to do" as a FAILED change set whose reason says so.
        // Surfacing that as an error would be wrong: an account already in the desired state
        // is the good outcome, and the commonest one once a fleet has been swept.
        if (isNoChanges(described)) {
            cleanUp(cfn, described.changeSetId());
            return new Preview(null, stack.stackName(), stack.stackId(), region, true,
                    parameterChanges, List.of(),
                    "This stack already matches what this environment expects.");
        }
        if (!"CREATE_COMPLETE".equals(described.statusAsString())) {
            cleanUp(cfn, described.changeSetId());
            throw new StackUpdateException("CHANGE_SET_FAILED",
                    blankTo(described.statusReason(), "The change set could not be created."),
                    null);
        }

        List<ResourceChange> resourceChanges = described.changes().stream()
                .map(Change::resourceChange)
                .filter(java.util.Objects::nonNull)
                .map(rc -> new ResourceChange(rc.logicalResourceId(), rc.resourceType(),
                        rc.actionAsString(), rc.replacementAsString()))
                .toList();

        logger.info("[stack] Preview for {} — {} parameter change(s), {} resource change(s)",
                account.getAwsAccountId(), parameterChanges.size(), resourceChanges.size());

        return new Preview(described.changeSetId(), stack.stackName(), stack.stackId(), region,
                false, parameterChanges, resourceChanges, null);
    }

    /** Runs a change set produced by {@link #preview}. */
    public void execute(CloudAccount account, String changeSetId) {
        if (changeSetId == null || changeSetId.isBlank()) {
            throw new StackUpdateException("NO_CHANGE_SET",
                    "No change set to execute. Preview the update first.", null);
        }
        String region = account.getStackRegion() != null ? account.getStackRegion() : "us-east-1";
        awsClientProvider.getCloudFormationClient(account, region)
                .executeChangeSet(ExecuteChangeSetRequest.builder()
                        .changeSetName(changeSetId)
                        .build());
        logger.info("[stack] Executing change set {} for account {}",
                changeSetId, account.getAwsAccountId());
    }

    /** One line of the live update, newest first. */
    public record Progress(String status, boolean inProgress, boolean failed,
                           List<EventLine> events) {
    }

    public record EventLine(String timestamp, String logicalId, String status, String reason) {
    }

    /**
     * Recent stack events, for showing an update as it runs.
     *
     * <p>A stack update takes minutes and can roll back. A spinner with no detail turns a
     * recoverable rollback into a support ticket, so the events are streamed to the caller
     * rather than hidden behind a result.
     */
    public Progress progress(CloudAccount account, int limit) {
        String region = account.getStackRegion() != null ? account.getStackRegion() : "us-east-1";
        CloudFormationClient cfn = awsClientProvider.getCloudFormationClient(account, region);

        Stack stack = cfn.describeStacks(DescribeStacksRequest.builder()
                .stackName(account.getStackId() != null ? account.getStackId()
                        : account.getStackName())
                .build()).stacks().get(0);
        String status = stack.stackStatusAsString();

        List<EventLine> events = cfn.describeStackEvents(DescribeStackEventsRequest.builder()
                        .stackName(stack.stackId()).build())
                .stackEvents().stream()
                .limit(limit)
                .map(this::toLine)
                .toList();

        return new Progress(status,
                status.endsWith("_IN_PROGRESS"),
                status.contains("FAILED") || status.contains("ROLLBACK"),
                events);
    }

    private EventLine toLine(StackEvent e) {
        return new EventLine(String.valueOf(e.timestamp()), e.logicalResourceId(),
                e.resourceStatusAsString(), blankTo(e.resourceStatusReason(), ""));
    }

    /**
     * What this environment dictates, regardless of what the stack currently holds.
     *
     * <p>Kept deliberately small. Every entry here is a value the environment owns and the
     * customer cannot meaningfully choose; anything else is left alone, because an update
     * that rewrites a parameter somebody set on purpose is worse than one that skips it.
     */
    private Map<String, String> desiredParameters() {
        Map<String, String> desired = new LinkedHashMap<>();
        if (inventoryQueueUrl != null && !inventoryQueueUrl.isBlank()) {
            desired.put("XamOpsInventoryQueueUrl", inventoryQueueUrl.trim());
            String region = regionFromQueueUrl(inventoryQueueUrl.trim());
            if (region != null) {
                desired.put("XamOpsInventoryRegion", region);
            }
        }
        return desired;
    }

    private String updaterRoleArn(CloudAccount account) {
        return "arn:aws:iam::" + account.getAwsAccountId() + ":role/" + UPDATER_ROLE_NAME;
    }

    private String regionFromQueueUrl(String queueUrl) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^https://sqs\\.([a-z0-9-]+)\\.amazonaws\\.com/")
                .matcher(queueUrl);
        return m.find() ? m.group(1) : null;
    }

    private DescribeChangeSetResponse awaitChangeSet(CloudFormationClient cfn, String stackId,
                                                     String changeSetName) {
        for (int i = 0; i < CHANGE_SET_POLL_ATTEMPTS; i++) {
            DescribeChangeSetResponse r = cfn.describeChangeSet(DescribeChangeSetRequest.builder()
                    .stackName(stackId).changeSetName(changeSetName).build());
            String status = r.statusAsString();
            if (!"CREATE_PENDING".equals(status) && !"CREATE_IN_PROGRESS".equals(status)) {
                return r;
            }
            try {
                Thread.sleep(CHANGE_SET_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new StackUpdateException("INTERRUPTED",
                        "Interrupted while building the change set.", null);
            }
        }
        throw new StackUpdateException("CHANGE_SET_TIMEOUT",
                "The change set did not finish building in time.", null);
    }

    /**
     * CloudFormation has no "no changes" status — it fails the change set and says so in the
     * reason, with wording that has varied. Matching on both known phrasings beats matching on
     * one and reporting a healthy account as broken.
     */
    private boolean isNoChanges(DescribeChangeSetResponse r) {
        if (!"FAILED".equals(r.statusAsString())) {
            return false;
        }
        String reason = String.valueOf(r.statusReason());
        return reason.contains("didn't contain changes")
                || reason.contains("No updates are to be performed");
    }

    /** A change set left behind blocks nothing, but it clutters the console. Best effort. */
    private void cleanUp(CloudFormationClient cfn, String changeSetId) {
        if (changeSetId == null) {
            return;
        }
        try {
            cfn.deleteChangeSet(b -> b.changeSetName(changeSetId));
        } catch (Exception e) {
            logger.debug("[stack] Could not delete change set {}: {}", changeSetId, e.getMessage());
        }
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Carries a verdict and a remedy, so the caller can render something actionable. */
    public static class StackUpdateException extends RuntimeException {
        private final String verdict;
        private final String remedy;

        public StackUpdateException(String verdict, String message, String remedy) {
            super(message);
            this.verdict = verdict;
            this.remedy = remedy;
        }

        public String getVerdict() {
            return verdict;
        }

        public String getRemedy() {
            return remedy;
        }
    }
}
