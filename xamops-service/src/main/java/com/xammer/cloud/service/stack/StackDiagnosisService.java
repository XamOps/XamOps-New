package com.xammer.cloud.service.stack;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackResource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Works out which CloudFormation stack owns an account's connection, and — when it cannot find
 * one — why.
 *
 * <p>"Missing" is not one situation. A deleted stack, a stack that never finished creating, a
 * stack in another region and a role that was never made by a stack at all each need a different
 * response, and reporting them all as "not found" sends the reader down the wrong path three
 * times out of four. Every verdict here is distinguishable from the API responses alone, so the
 * caller can name the real cause.
 *
 * <p>This class only reads. Updating a stack needs permissions the onboarding role does not have
 * and should not be given directly; that arrives separately, and this diagnosis is what decides
 * whether it can run at all.
 */
@Service
public class StackDiagnosisService {

    private static final Logger logger = LoggerFactory.getLogger(StackDiagnosisService.class);

    /**
     * Last resort when an account has no role ARN on file.
     *
     * <p>Never assume this name otherwise. The templates create at least three —
     * {@code XamOpsReadOnlyRole}, {@code XamOpsReadWriteRole} and
     * {@code XamOpsReadWriteRole-local} — so a hardcoded guess reports every read-write account
     * as having no stack, which is how this was found: a healthy
     * {@code XamOps-Connection-XammerMarketPlace-1} in CREATE_COMPLETE came back as
     * NOT_STACK_MANAGED. The account's own {@code roleArn} is authoritative and always present
     * for a connected account.
     */
    private static final String FALLBACK_ROLE_NAME = "XamOpsReadOnlyRole";

    /**
     * Where to look for a stack when the account does not say.
     *
     * <p>Ordered by likelihood, and stopping at the first hit. {@code us-east-1} leads because
     * that is what the onboarding quick-create link defaults to — and notably it is absent from
     * the application's own DEFAULT_REGIONS list, so a sweep built from that list would miss the
     * most common case entirely.
     */
    private static final List<String> PROBE_REGIONS = List.of(
            "us-east-1", "ap-south-1", "us-west-2", "eu-west-1", "ap-southeast-1",
            "us-east-2", "eu-central-1", "ap-northeast-1");

    /** Statuses from which AWS refuses an update outright, whatever permissions we hold. */
    private static final List<String> UNUPDATABLE = List.of(
            "ROLLBACK_COMPLETE", "ROLLBACK_FAILED", "CREATE_FAILED", "DELETE_FAILED");

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final String defaultRegion;

    public StackDiagnosisService(CloudAccountRepository cloudAccountRepository,
                                 AwsClientProvider awsClientProvider,
                                 @Value("${aws.region:ap-south-1}") String defaultRegion) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.defaultRegion = defaultRegion;
    }

    /** What the diagnosis concluded, and what can be done about it. */
    public enum Verdict {
        /** Stack found and in a state that accepts an update. */
        HEALTHY,
        /** Found, but mid-operation. Not broken — busy. */
        BUSY,
        /** Found, but in a state AWS will not update. Needs deleting and recreating. */
        UNUPDATABLE,
        /** Found, but its last update rolled back and the rollback failed. Needs unsticking. */
        ROLLBACK_STUCK,
        /**
         * The role works but carries no CloudFormation permission — the expected answer for
         * every account connected before this feature existed.
         */
        NO_CFN_PERMISSION,
        /** The recorded stack is gone. Its role may well still be working. */
        STACK_DELETED,
        /** A stack of the same name exists, but it is not the one we created. */
        STACK_REPLACED,
        /** The role exists but no stack claims it: made by hand, Terraform, or a StackSet. */
        NOT_STACK_MANAGED,
        /** The role could not be assumed at all. A broken connection, not a stack problem. */
        CONNECTION_BROKEN,
        /** Something else went wrong; {@code detail} carries it. */
        UNKNOWN
    }

    /**
     * The outcome of one diagnosis.
     *
     * @param stackStatus the CloudFormation status where one could be read, else null
     * @param updatable   whether an update could proceed today, permissions included
     * @param detail      a sentence naming the cause, written for the person reading the screen
     * @param remedy      the single next action, or null when none applies
     */
    public record Diagnosis(Verdict verdict, String stackId, String stackName, String stackRegion,
                            String stackStatus, boolean updatable, String detail, String remedy) {
    }

    /**
     * Diagnoses one account and caches the verdict on it.
     *
     * <p>Adopts what it learns: an account with no recorded stack that turns out to have one ends
     * this call with the id stored, so the expensive lookup happens once rather than on every
     * page load.
     */
    public Diagnosis diagnose(CloudAccount account) {
        Diagnosis diagnosis = run(account);

        account.setStackStatus(diagnosis.stackStatus() != null
                ? diagnosis.stackStatus()
                : diagnosis.verdict().name());
        account.setStackCheckedAt(Instant.now());
        if (diagnosis.stackId() != null) {
            account.setStackId(diagnosis.stackId());
            account.setStackName(diagnosis.stackName());
            account.setStackRegion(diagnosis.stackRegion());
        }
        cloudAccountRepository.save(account);

        logger.info("[stack] Account {} → {} ({})",
                account.getAwsAccountId(), diagnosis.verdict(), diagnosis.detail());
        return diagnosis;
    }

    private Diagnosis run(CloudAccount account) {
        String region = account.getStackRegion() != null ? account.getStackRegion() : defaultRegion;

        CloudFormationClient cfn;
        try {
            cfn = awsClientProvider.getCloudFormationClient(account, region);
        } catch (Exception e) {
            return new Diagnosis(Verdict.CONNECTION_BROKEN, null, null, region, null, false,
                    "The connection role could not be assumed: " + rootMessage(e),
                    "Reconnect the account.");
        }

        // A recorded id is the strongest evidence available: it survives a rename, and it fails
        // to resolve precisely when the stack we created is gone.
        if (account.getStackId() != null && !account.getStackId().isBlank()) {
            return describeById(cfn, account, region);
        }
        return discoverFromRole(account, region);
    }

    /** The path for accounts whose stack id was recorded at onboarding. */
    private Diagnosis describeById(CloudFormationClient cfn, CloudAccount account, String region) {
        try {
            List<Stack> stacks = cfn.describeStacks(
                    DescribeStacksRequest.builder().stackName(account.getStackId()).build()).stacks();
            if (stacks.isEmpty()) {
                return new Diagnosis(Verdict.STACK_DELETED, null, account.getStackName(), region,
                        null, false,
                        "The recorded stack no longer exists.",
                        "Re-onboard the account to recreate its stack.");
            }
            return classify(stacks.get(0), region);

        } catch (Exception e) {
            if (isAccessDenied(e)) {
                return noPermission(account, region);
            }
            // CloudFormation reports a deleted stack as a validation error rather than an empty
            // result, so this is the ordinary "it is gone" path, not an exceptional one.
            if (isNotFound(e)) {
                return afterDeletion(cfn, account, region);
            }
            return unknown(region, e);
        }
    }

    /**
     * The recorded stack is gone. Whether that matters depends on what replaced it.
     *
     * <p>A stack of the same name with a different ARN means the customer deleted and recreated
     * theirs, which almost always reissues the ExternalId — a connection that looks fine until
     * the next credential refresh. Adopting the new id silently would hide that, so it is
     * reported as its own verdict.
     */
    private Diagnosis afterDeletion(CloudFormationClient cfn, CloudAccount account, String region) {
        if (account.getStackName() != null && !account.getStackName().isBlank()) {
            try {
                List<Stack> byName = cfn.describeStacks(
                        DescribeStacksRequest.builder().stackName(account.getStackName()).build()).stacks();
                if (!byName.isEmpty()) {
                    Stack replacement = byName.get(0);
                    return new Diagnosis(Verdict.STACK_REPLACED, replacement.stackId(),
                            replacement.stackName(), region, replacement.stackStatusAsString(), false,
                            "A different stack now carries this name — the original was deleted and "
                                    + "recreated. Its ExternalId was probably reissued.",
                            "Verify the connection, then adopt the new stack.");
                }
            } catch (Exception ignored) {
                // Falls through to the role lookup below, which is the better question anyway.
            }
        }

        // The role outlives its stack when the stack was deleted with the role retained, so this
        // separates "gone entirely" from "gone but still working".
        Optional<FoundStack> owner = sweepForStack(account, roleNameFor(account), region);
        if (owner.isPresent()) {
            StackResource r = owner.get().resource();
            return new Diagnosis(Verdict.STACK_REPLACED, r.stackId(), r.stackName(),
                    owner.get().region(), null, false,
                    "The recorded stack is gone, but the connection role belongs to stack "
                            + r.stackName() + ".",
                    "Adopt that stack after verifying the connection.");
        }

        return new Diagnosis(Verdict.STACK_DELETED, null, account.getStackName(), region, null, false,
                "The stack was deleted. The connection role was retained, so dashboards keep "
                        + "working, but no template change can reach this account.",
                "Re-onboard the account to recreate its stack.");
    }

    /**
     * The path for every account connected before stack ids were recorded.
     *
     * <p>Rather than sweeping regions or guessing at names, this asks the role which stack made
     * it. {@code DescribeStackResources} accepts a physical resource id and answers with the
     * stack that owns it — and an empty answer is itself a finding, because it means no stack
     * ever created this role.
     */
    private Diagnosis discoverFromRole(CloudAccount account, String region) {
        String roleName = roleNameFor(account);
        try {
            Optional<FoundStack> owner = sweepForStack(account, roleName, region);
            if (owner.isEmpty()) {
                return new Diagnosis(Verdict.NOT_STACK_MANAGED, null, null, region, null, false,
                        "No CloudFormation stack in any checked region claims the role "
                                + roleName + " — it was created by hand, by Terraform, or by a "
                                + "StackSet.",
                        "Updates must be applied manually for this account.");
            }

            StackResource r = owner.get().resource();
            String found = owner.get().region();
            List<Stack> stacks = awsClientProvider.getCloudFormationClient(account, found)
                    .describeStacks(DescribeStacksRequest.builder().stackName(r.stackId()).build())
                    .stacks();
            return stacks.isEmpty()
                    ? new Diagnosis(Verdict.STACK_DELETED, null, r.stackName(), found, null, false,
                            "The stack that created the role has since been deleted.",
                            "Re-onboard the account to recreate its stack.")
                    : classify(stacks.get(0), found);

        } catch (Exception e) {
            if (isAccessDenied(e)) {
                return noPermission(account, region);
            }
            return unknown(region, e);
        }
    }

    /**
     * The role name this account actually connects through.
     *
     * <p>Taken from the stored ARN rather than assumed, because the templates create three
     * different names and the read-write ones are the majority of connected accounts.
     */
    private String roleNameFor(CloudAccount account) {
        String arn = account.getRoleArn();
        if (arn == null || !arn.contains("/")) {
            return FALLBACK_ROLE_NAME;
        }
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    private Optional<StackResource> findStackOwningRole(CloudFormationClient cfn, String roleName) {
        return cfn.describeStackResources(DescribeStackResourcesRequest.builder()
                        .physicalResourceId(roleName)
                        .build())
                .stackResources().stream()
                .findFirst();
    }

    /**
     * Looks for the owning stack across regions, nearest guess first.
     *
     * <p>An IAM role is global but a stack is regional, and {@code DescribeStackResources} only
     * sees its own region — so asking in the wrong one returns "no stack owns this role", which
     * is indistinguishable from the role genuinely not being stack-managed. Both bugs showed up
     * together: the configured region is ap-south-1 and the stack was in us-east-1.
     *
     * <p>Only runs for accounts with no recorded stack id, and the result is saved, so each
     * account pays for this sweep once.
     */
    private Optional<FoundStack> sweepForStack(CloudAccount account, String roleName,
                                               String firstRegion) {
        List<String> order = new ArrayList<>();
        order.add(firstRegion);
        PROBE_REGIONS.stream().filter(r -> !r.equals(firstRegion)).forEach(order::add);

        for (String region : order) {
            try {
                Optional<StackResource> owner = findStackOwningRole(
                        awsClientProvider.getCloudFormationClient(account, region), roleName);
                if (owner.isPresent()) {
                    logger.info("[stack] Found {} owning role {} in {}",
                            owner.get().stackName(), roleName, region);
                    return Optional.of(new FoundStack(owner.get(), region));
                }
            } catch (Exception e) {
                // A region the account cannot reach, or has not opted into, is not an error —
                // it is simply not where the stack is. Only a denial is worth stopping for,
                // since it will repeat in every other region too.
                if (isAccessDenied(e)) {
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
            }
        }
        return Optional.empty();
    }

    private record FoundStack(StackResource resource, String region) {
    }

    /** Turns a CloudFormation status into a verdict about whether an update could run. */
    private Diagnosis classify(Stack stack, String region) {
        String status = stack.stackStatusAsString();

        if (status.endsWith("_IN_PROGRESS")) {
            return new Diagnosis(Verdict.BUSY, stack.stackId(), stack.stackName(), region, status,
                    false,
                    "The stack is mid-operation (" + status + ").",
                    "Wait for it to settle, then check again.");
        }
        if ("UPDATE_ROLLBACK_FAILED".equals(status)) {
            return new Diagnosis(Verdict.ROLLBACK_STUCK, stack.stackId(), stack.stackName(), region,
                    status, false,
                    "A previous update failed and its rollback also failed. AWS will not accept "
                            + "another update until the rollback is continued.",
                    "Run ContinueUpdateRollback on the stack.");
        }
        if (UNUPDATABLE.contains(status)) {
            return new Diagnosis(Verdict.UNUPDATABLE, stack.stackId(), stack.stackName(), region,
                    status, false,
                    "The stack is in " + status + ", which AWS will not update. A stack that never "
                            + "created successfully can only be deleted.",
                    "Delete the stack, then re-onboard the account.");
        }

        return new Diagnosis(Verdict.HEALTHY, stack.stackId(), stack.stackName(), region, status,
                true,
                "The stack is healthy and accepts updates.",
                null);
    }

    private Diagnosis noPermission(CloudAccount account, String region) {
        return new Diagnosis(Verdict.NO_CFN_PERMISSION, account.getStackId(), account.getStackName(),
                region, null, false,
                "The connection is healthy, but its role predates CloudFormation permissions, so "
                        + "the stack cannot be inspected.",
                "Deploy the permission-grant stack in this account.");
    }

    private Diagnosis unknown(String region, Exception e) {
        logger.warn("[stack] Diagnosis failed: {}", e.getMessage());
        return new Diagnosis(Verdict.UNKNOWN, null, null, region, null, false,
                rootMessage(e), null);
    }

    /**
     * Matches on message text because the SDK models both of these as
     * {@code CloudFormationException} with only the message to tell them apart — there is no
     * typed exception for either.
     */
    private boolean isAccessDenied(Exception e) {
        String m = rootMessage(e);
        return m.contains("AccessDenied") || m.contains("not authorized")
                || m.contains("is not authorized to perform");
    }

    private boolean isNotFound(Exception e) {
        String m = rootMessage(e);
        return m.contains("does not exist") || m.contains("ValidationError");
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
