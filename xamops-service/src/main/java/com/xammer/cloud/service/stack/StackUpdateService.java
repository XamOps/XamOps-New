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
 * <p>Updates move the stack onto the template this environment currently publishes, not just
 * onto new parameter values. The first version re-applied each stack's own template, which
 * made it structurally unable to deliver the change updates exist for most often: a permission
 * added to the template. {@code resource-explorer-2:UpdateIndexType} was the case that exposed
 * it — published to S3, and unreachable by every already-connected account through this path.
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

    /**
     * The roles XamOps assumes — the only ones whose trust policy carries the ExternalId.
     *
     * <p>Replacing one deletes and recreates the role the whole connection runs through. Unlike a
     * policy edit, which applies in place, that leaves the account disconnected for the duration
     * and permanently so if the create half fails and rolls back. A parameter-only update could
     * never cause it; a template upgrade can, so it is refused rather than merely shown.
     */
    private static final java.util.Set<String> CONNECTION_ROLES =
            java.util.Set.of("XamOpsReadOnlyRole", "XamOpsReadWriteRole");

    private static final java.time.Duration TEMPLATE_FETCH_TIMEOUT = java.time.Duration.ofSeconds(15);

    private final AwsClientProvider awsClientProvider;
    private final StackDiagnosisService diagnosisService;
    private final String inventoryQueueUrl;
    private final String readOnlyTemplateUrl;
    private final String readWriteTemplateUrl;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(TEMPLATE_FETCH_TIMEOUT)
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    public StackUpdateService(AwsClientProvider awsClientProvider,
                              StackDiagnosisService diagnosisService,
                              @Value("${xamops.inventory.events.queue-url:}") String inventoryQueueUrl,
                              @Value("${cloudformation.template.s3.url:}") String readOnlyTemplateUrl,
                              @Value("${cloudformation.template.s3.readwrite.url:}") String readWriteTemplateUrl) {
        this.awsClientProvider = awsClientProvider;
        this.diagnosisService = diagnosisService;
        this.inventoryQueueUrl = inventoryQueueUrl;
        this.readOnlyTemplateUrl = readOnlyTemplateUrl;
        this.readWriteTemplateUrl = readWriteTemplateUrl;
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

        // Upgrade to the template this environment currently publishes, not merely re-apply the
        // stack's own. Reusing the previous template could only ever move parameters, so a
        // permission added to the template — the commonest reason to update at all — never
        // reached a connected account through this button.
        String templateUrl = templateUrlFor(account);
        Map<String, Boolean> declared;
        try {
            declared = TemplateParameters.parse(fetchTemplate(templateUrl));
        } catch (IllegalArgumentException e) {
            throw new StackUpdateException("TEMPLATE_UNREADABLE",
                    "The latest template at " + templateUrl + " could not be parsed: " + e.getMessage(),
                    "Check the published template is valid CloudFormation.");
        }

        Map<String, String> existingValues = new LinkedHashMap<>();
        for (Parameter p : stack.parameters()) {
            existingValues.put(p.parameterKey(), p.parameterValue());
        }

        Map<String, String> desired = desiredParameters();
        List<ParameterChange> parameterChanges = new ArrayList<>();
        List<Parameter> parameters = new ArrayList<>();

        // Driven by what the NEW template declares, never by what the old stack held. Passing a
        // parameter the new template dropped fails as "does not exist in the template"; omitting
        // one it added without a default fails as missing. Walking the declared list avoids both.
        for (Map.Entry<String, Boolean> param : declared.entrySet()) {
            String key = param.getKey();
            String want = desired.get(key);

            if (want != null) {
                if (!want.equals(existingValues.get(key))) {
                    parameterChanges.add(new ParameterChange(key, existingValues.get(key), want));
                }
                parameters.add(Parameter.builder().parameterKey(key).parameterValue(want).build());
            } else if (existingValues.containsKey(key)) {
                // Everything this environment does not dictate keeps what the stack has —
                // ExternalId above all, because rewriting it changes the role's trust policy
                // and disconnects the account with nothing failing to say so.
                parameters.add(Parameter.builder()
                        .parameterKey(key).usePreviousValue(true).build());
            } else if (!param.getValue()) {
                // New in this template, with no default, and nothing here to supply it. Refused
                // before CloudFormation is asked, so the message can name the parameter.
                throw new StackUpdateException("NEW_REQUIRED_PARAMETER",
                        "The latest template adds a required parameter, " + key
                                + ", that this stack has no value for.",
                        "Give " + key + " a default in the template, or set it on the stack by hand.");
            }
            // Otherwise new with a default: left out so CloudFormation applies the default.
        }

        String changeSetName = "xamops-" + Instant.now().toEpochMilli();
        cfn.createChangeSet(CreateChangeSetRequest.builder()
                .stackName(diagnosis.stackId())
                .changeSetName(changeSetName)
                .changeSetType(ChangeSetType.UPDATE)
                .templateURL(templateUrl)
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

        // Refuse rather than preview. Showing the replacement and trusting someone to notice it
        // among a list of IAM changes is not a safety mechanism for the one change that
        // disconnects the account.
        for (ResourceChange rc : resourceChanges) {
            if (CONNECTION_ROLES.contains(rc.logicalId()) && "True".equalsIgnoreCase(rc.replacement())) {
                cleanUp(cfn, described.changeSetId());
                throw new StackUpdateException("WOULD_REPLACE_CONNECTION_ROLE",
                        "This update would replace " + rc.logicalId() + ", the role XamOps connects "
                                + "through. That deletes and recreates it, disconnecting the account "
                                + "while it runs and permanently if the recreate fails.",
                        "Change the template so " + rc.logicalId() + " is modified in place — "
                                + "replacement is usually caused by editing RoleName or Path.");
            }
        }

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

    /**
     * The template this environment publishes for the account's access level.
     *
     * <p>Chosen by access type, the same comparison {@code AwsAccountService} uses when it
     * builds the onboarding link, so an update lands on the template the account would get if
     * it connected today — never a read-write template on a read-only account.
     */
    private String templateUrlFor(CloudAccount account) {
        boolean readWrite = "read-write".equalsIgnoreCase(account.getAccessType());
        String url = readWrite ? readWriteTemplateUrl : readOnlyTemplateUrl;
        if (url == null || url.isBlank()) {
            throw new StackUpdateException("TEMPLATE_NOT_CONFIGURED",
                    "No " + (readWrite ? "read-write" : "read-only")
                            + " template is configured for this environment.",
                    "Set cloudformation.template.s3." + (readWrite ? "readwrite.url" : "url") + ".");
        }
        return url.trim();
    }

    /**
     * The template body, read from its published URL.
     *
     * <p>Fetched rather than trusted blind for two reasons. Its parameter list decides what the
     * change set may pass, and a URL that does not resolve should fail here with the URL named,
     * not deep inside CreateChangeSet with a message that omits it. That second case is real:
     * UAT's read-write template has been referenced by configuration without being published.
     */
    private String fetchTemplate(String url) {
        try {
            java.net.http.HttpResponse<String> response = http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .timeout(TEMPLATE_FETCH_TIMEOUT).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new StackUpdateException("TEMPLATE_UNAVAILABLE",
                        "The latest template could not be read (HTTP " + response.statusCode() + "): " + url,
                        "Publish the template at that URL, or correct the configured URL.");
            }
            return response.body();
        } catch (StackUpdateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StackUpdateException("INTERRUPTED", "Interrupted while reading the template.", null);
        } catch (Exception e) {
            throw new StackUpdateException("TEMPLATE_UNAVAILABLE",
                    "The latest template could not be read: " + url + " (" + e.getMessage() + ")",
                    "Check that the template URL is reachable.");
        }
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
