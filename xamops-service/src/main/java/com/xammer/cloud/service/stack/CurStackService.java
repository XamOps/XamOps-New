package com.xammer.cloud.service.stack;

import com.xammer.cloud.domain.AwsBillingSource;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortIncompleteMultipartUpload;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.PutBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionConfiguration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Creates or updates the CUR report stack for an account that owns its report, from the same
 * "Update stack" action that brings the connection stack onto the current template — so a CUR
 * report never needs a second trip to the AWS console.
 *
 * <p>The CUR stack lives in us-east-1, the only region AWS::CUR::ReportDefinition exists in,
 * and references a bucket (xamops-cur-&lt;accountId&gt;) by name rather than declaring it. That
 * bucket is created out-of-band here before the stack is created or updated (see FEAT-001's
 * reuse-the-bucket template shape), because a stack that assumes a bucket it does not own would
 * fail the moment CUR tried to write to a bucket that was not there.
 *
 * <p>Everything here is best-effort from the caller's point of view: {@link #applyAsync} never
 * throws past itself. A refusal that a one-time grant would fix (an account connected before the
 * CUR grant existed) is reported as a retryable PENDING_PERMISSION rather than a hard failure,
 * so the connection change set — which carries that very grant — gets a chance to land first.
 */
@Service
public class CurStackService {

    private static final Logger logger = LoggerFactory.getLogger(CurStackService.class);

    /** The only region AWS::CUR::ReportDefinition lives in. */
    private static final String US_EAST_1 = "us-east-1";

    /** A stored stack name is trusted only if it still looks like one XamOps would mint. */
    private static final Pattern STACK_NAME_PATTERN = Pattern.compile("^XamOps-[A-Za-z0-9-]+$");

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final String curTemplateUrl;

    public CurStackService(AwsClientProvider awsClientProvider,
                           CloudAccountRepository cloudAccountRepository,
                           @Value("${cloudformation.template.s3.cur.url:}") String curTemplateUrl) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.curTemplateUrl = curTemplateUrl;
    }

    /** Where the CUR stack stands relative to what an apply would do. */
    public enum State {
        /** Not an OWN_CUR account, or no CUR template configured — nothing to create or update. */
        NOT_APPLICABLE,
        /** No stack by this name — an apply would create it. */
        ABSENT,
        /** A settled stack — an apply would update it. */
        PRESENT,
        /** A stack mid-operation — an apply must wait. */
        BUSY,
        /** The connection role cannot describe the stack yet (likely a missing grant). */
        NO_PERMISSION
    }

    /** The outcome of {@link #applyAsync}, mapped to a status the frontend renders. */
    public enum CurStatus {
        SKIPPED,
        CREATE_IN_PROGRESS,
        UPDATE_IN_PROGRESS,
        NO_CHANGES,
        BUSY,
        PENDING_PERMISSION,
        NO_PERMISSION_TERMINAL,
        FAILED
    }

    /** What {@link #detect} found, plus the stack name it resolved for the account. */
    public record DetectResult(State state, String stackName) {
    }

    /** The result of an apply: a status, the stack it touched, and a human message. */
    public record CurApplyResult(CurStatus curStatus, String curStackName, String message) {
    }

    /** A read-only snapshot of the CUR stack, for progress polling. Null when not applicable. */
    public record CurProgress(String status, boolean inProgress, boolean failed) {
    }

    /** Raised internally when the bucket exists in another region or is owned by someone else. */
    private static class CurBucketFatalException extends RuntimeException {
        CurBucketFatalException(String message) {
            super(message);
        }
    }

    /**
     * The stack name for this account's CUR report.
     *
     * <p>Prefers a previously stored name so a rename of the account cannot orphan an existing
     * stack, but only if that stored name still matches the shape XamOps mints — a stored value
     * that does not is treated as corrupt and the derived name is used instead.
     */
    String resolveStackName(CloudAccount account) {
        String stored = account.getCurStackName();
        if (stored != null && STACK_NAME_PATTERN.matcher(stored).matches()) {
            return stored;
        }
        if (stored != null && !stored.isBlank()) {
            logger.warn("[cur-stack] Stored CUR stack name '{}' for account {} does not match the expected "
                    + "pattern; falling back to a derived name", stored, account.getAwsAccountId());
        }
        return "XamOps-CUR-" + String.valueOf(account.getAccountName()).replaceAll("[^a-zA-Z0-9-]", "");
    }

    private boolean applicable(CloudAccount account) {
        return account.resolveAwsBillingSource() == AwsBillingSource.OWN_CUR
                && curTemplateUrl != null && !curTemplateUrl.isBlank();
    }

    /**
     * Where the CUR stack stands for this account, without changing anything.
     *
     * <p>Returns NOT_APPLICABLE for an account that does not own its report or an environment
     * with no CUR template. Otherwise describes the stack in us-east-1 and classifies it.
     */
    public DetectResult detect(CloudAccount account) {
        String stackName = resolveStackName(account);
        if (!applicable(account)) {
            return new DetectResult(State.NOT_APPLICABLE, stackName);
        }
        try {
            CloudFormationClient cfn = awsClientProvider.getCloudFormationClient(account, US_EAST_1);
            Stack stack = cfn.describeStacks(DescribeStacksRequest.builder()
                    .stackName(stackName).build()).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (status != null && status.endsWith("_IN_PROGRESS")) {
                return new DetectResult(State.BUSY, stackName);
            }
            return new DetectResult(State.PRESENT, stackName);
        } catch (AwsServiceException e) {
            if (isAccessDenied(e)) {
                return new DetectResult(State.NO_PERMISSION, stackName);
            }
            if (isDoesNotExist(e)) {
                return new DetectResult(State.ABSENT, stackName);
            }
            logger.warn("[cur-stack] describeStacks for {} failed: {}", stackName, e.getMessage());
            throw e;
        }
    }

    /**
     * Ensures the CUR bucket exists and is hardened, then creates or updates the CUR stack.
     *
     * <p>Never throws: every outcome, including a failure, comes back as a {@link CurApplyResult}.
     * A permission refusal is reported as retryable PENDING_PERMISSION when the connection change
     * set ran in the same apply (its grant may not have taken effect yet), and as a terminal
     * NO_PERMISSION_TERMINAL otherwise.
     *
     * @param connectionChangeSetRan whether the connection change set executed in this same apply
     */
    public CurApplyResult applyAsync(CloudAccount account, boolean connectionChangeSetRan) {
        String stackName = resolveStackName(account);
        try {
            DetectResult detected = detect(account);
            if (detected.state() == State.NOT_APPLICABLE) {
                return new CurApplyResult(CurStatus.SKIPPED, detected.stackName(),
                        "This account does not own its CUR report, so there is no CUR stack to apply.");
            }
            stackName = detected.stackName();
            if (detected.state() == State.BUSY) {
                return new CurApplyResult(CurStatus.BUSY, stackName,
                        "The CUR stack is mid-operation. Try again once it settles.");
            }
            if (detected.state() == State.NO_PERMISSION) {
                return permissionResult(stackName, connectionChangeSetRan);
            }

            // Bucket first: the template references it by name and does not own it.
            createIfAbsent(account, stackName);
            applyHardening(account);

            CloudFormationClient cfn = awsClientProvider.getCloudFormationClient(account, US_EAST_1);
            if (detected.state() == State.ABSENT) {
                cfn.createStack(CreateStackRequest.builder()
                        .stackName(stackName)
                        .templateURL(curTemplateUrl)
                        .build());
                persistIdentity(account, stackName);
                return new CurApplyResult(CurStatus.CREATE_IN_PROGRESS, stackName,
                        "Creating the CUR report stack.");
            }
            // PRESENT -> update
            cfn.updateStack(UpdateStackRequest.builder()
                    .stackName(stackName)
                    .templateURL(curTemplateUrl)
                    .build());
            persistIdentity(account, stackName);
            return new CurApplyResult(CurStatus.UPDATE_IN_PROGRESS, stackName,
                    "Updating the CUR report stack.");
        } catch (CurBucketFatalException e) {
            logger.warn("[cur-stack] Bucket precondition failed for {}: {}", stackName, e.getMessage());
            return new CurApplyResult(CurStatus.FAILED, stackName, e.getMessage());
        } catch (AwsServiceException e) {
            return mapSdkError(stackName, connectionChangeSetRan, e);
        } catch (Exception e) {
            logger.warn("[cur-stack] Unexpected failure applying CUR stack {}: {}", stackName, e.getMessage(), e);
            return new CurApplyResult(CurStatus.FAILED, stackName, rootMessage(e));
        }
    }

    /**
     * The CUR stack as it stands now, for progress polling. Null when the account does not own a
     * CUR report or the stack cannot be read — the controller omits the sub-object in that case.
     */
    public CurProgress progress(CloudAccount account) {
        if (!applicable(account)) {
            return null;
        }
        String stackName = resolveStackName(account);
        try {
            CloudFormationClient cfn = awsClientProvider.getCloudFormationClient(account, US_EAST_1);
            Stack stack = cfn.describeStacks(DescribeStacksRequest.builder()
                    .stackName(stackName).build()).stacks().get(0);
            String status = stack.stackStatusAsString();
            boolean inProgress = status != null && status.endsWith("_IN_PROGRESS");
            boolean failed = status != null && (status.contains("FAILED") || status.contains("ROLLBACK"));
            return new CurProgress(status, inProgress, failed);
        } catch (Exception e) {
            logger.debug("[cur-stack] progress for {} unavailable: {}", stackName, e.getMessage());
            return null;
        }
    }

    // ================= internals =================

    /**
     * Ensures the CUR bucket exists in us-east-1, reusing it if already present.
     *
     * <p>A 404 / NoSuchBucket means create it (no CreateBucketConfiguration in us-east-1). A
     * race that reports BucketAlreadyOwnedByYou is success. An ambiguous 403 is deliberately
     * NOT fatal — the real error surfaces later at CreateStack/UpdateStack — but a definitive
     * foreign-owner 403 or a 301/PermanentRedirect (the bucket lives in another region) is fatal
     * for the CUR side only, raised as a {@link CurBucketFatalException}.
     */
    private void createIfAbsent(CloudAccount account, String stackName) {
        String bucket = "xamops-cur-" + account.getAwsAccountId();
        S3Client s3 = awsClientProvider.getS3Client(account, US_EAST_1);
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            // Present and reachable — reuse it.
            return;
        } catch (AwsServiceException e) {
            int status = e.statusCode();
            if (status == 404 || isNoSuchBucket(e)) {
                createBucket(s3, bucket);
                return;
            }
            if (status == 301 || isPermanentRedirect(e)) {
                throw new CurBucketFatalException("The CUR bucket " + bucket + " already exists in another "
                        + "region. It must live in us-east-1 for the CUR report. Delete or move it, then retry.");
            }
            if (status == 403 && isForeignOwner(e)) {
                throw new CurBucketFatalException("The CUR bucket " + bucket + " is owned by another AWS "
                        + "account and cannot be used. The report requires a bucket owned by this account.");
            }
            // Ambiguous 403 (and anything else): not fatal here — let CreateStack/UpdateStack
            // surface the real, actionable error instead.
            logger.debug("[cur-stack] headBucket on {} was inconclusive ({}): {}", bucket, status, e.getMessage());
        }
    }

    private void createBucket(S3Client s3, String bucket) {
        try {
            // us-east-1: no CreateBucketConfiguration / LocationConstraint allowed.
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (AwsServiceException e) {
            if (isAlreadyOwnedByYou(e)) {
                // Lost a race with ourselves — the bucket is here and ours.
                return;
            }
            throw e;
        }
    }

    /**
     * The three idempotent protections put on the CUR bucket, applied on EVERY apply whether the
     * bucket was just created or reused: block all public access, default AES256 encryption, and
     * a lifecycle rule that expires Athena results after 7 days and aborts stalled multipart
     * uploads after 1 day.
     */
    private void applyHardening(CloudAccount account) {
        String bucket = "xamops-cur-" + account.getAwsAccountId();
        S3Client s3 = awsClientProvider.getS3Client(account, US_EAST_1);

        s3.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                .bucket(bucket)
                .publicAccessBlockConfiguration(c -> c
                        .blockPublicAcls(true)
                        .ignorePublicAcls(true)
                        .blockPublicPolicy(true)
                        .restrictPublicBuckets(true))
                .build());

        s3.putBucketEncryption(PutBucketEncryptionRequest.builder()
                .bucket(bucket)
                .serverSideEncryptionConfiguration(ServerSideEncryptionConfiguration.builder()
                        .rules(ServerSideEncryptionRule.builder()
                                .applyServerSideEncryptionByDefault(ServerSideEncryptionByDefault.builder()
                                        .sseAlgorithm(ServerSideEncryption.AES256)
                                        .build())
                                .build())
                        .build())
                .build());

        s3.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
                .bucket(bucket)
                .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                        .rules(LifecycleRule.builder()
                                .id("ExpireAthenaResults")
                                .status(ExpirationStatus.ENABLED)
                                .filter(LifecycleRuleFilter.builder().prefix("xamops-athena-results/").build())
                                .expiration(LifecycleExpiration.builder().days(7).build())
                                .abortIncompleteMultipartUpload(AbortIncompleteMultipartUpload.builder()
                                        .daysAfterInitiation(1).build())
                                .build())
                        .build())
                .build());
    }

    private void persistIdentity(CloudAccount account, String stackName) {
        account.setCurStackName(stackName);
        account.setCurStackRegion(US_EAST_1);
        cloudAccountRepository.save(account);
    }

    private CurApplyResult permissionResult(String stackName, boolean connectionChangeSetRan) {
        if (connectionChangeSetRan) {
            return new CurApplyResult(CurStatus.PENDING_PERMISSION, stackName,
                    "The CUR stack could not be reached yet. The connection update just added the grant it "
                            + "needs — retry in a moment.");
        }
        return new CurApplyResult(CurStatus.NO_PERMISSION_TERMINAL, stackName,
                "XamOps is not permitted to manage the CUR stack in this account. Update the connection "
                        + "stack first so the CUR grant is in place.");
    }

    /**
     * The design's error table: maps a raw SDK failure from the bucket-ensure step or
     * CreateStack/UpdateStack to a {@link CurStatus}.
     */
    private CurApplyResult mapSdkError(String stackName, boolean connectionChangeSetRan, AwsServiceException e) {
        String message = rootMessage(e);
        String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("no updates are to be performed")) {
            return new CurApplyResult(CurStatus.NO_CHANGES, stackName,
                    "The CUR stack already matches the current template.");
        }
        if (lower.contains("_in_progress") || lower.contains("is in")) {
            return new CurApplyResult(CurStatus.BUSY, stackName,
                    "The CUR stack is mid-operation. Try again once it settles.");
        }
        if (lower.contains("templateurl") && lower.contains("url")) {
            return new CurApplyResult(CurStatus.FAILED, stackName,
                    "The configured CUR template URL is not a supported S3 URL: " + curTemplateUrl);
        }
        if (isAccessDenied(e)) {
            return permissionResult(stackName, connectionChangeSetRan);
        }
        // A first-run that rolled back (R4): retryable once the connection grant lands.
        if (lower.contains("rollback_complete") || lower.contains("rollback_in_progress")
                || lower.contains("rollback")) {
            return permissionResult(stackName, connectionChangeSetRan);
        }
        logger.warn("[cur-stack] Apply of {} failed: {}", stackName, message);
        return new CurApplyResult(CurStatus.FAILED, stackName, message);
    }

    private static boolean isAccessDenied(AwsServiceException e) {
        String code = errorCode(e);
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return (code != null && code.toLowerCase(java.util.Locale.ROOT).contains("accessdenied"))
                || msg.contains("AccessDenied") || msg.contains("not authorized")
                || e.statusCode() == 403 && code != null
                        && code.toLowerCase(java.util.Locale.ROOT).contains("access");
    }

    private static boolean isDoesNotExist(AwsServiceException e) {
        String code = errorCode(e);
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return ("ValidationError".equals(code) && msg.contains("does not exist"))
                || msg.contains("does not exist");
    }

    private static boolean isNoSuchBucket(AwsServiceException e) {
        String code = errorCode(e);
        return code != null && (code.contains("NoSuchBucket") || code.contains("NotFound"));
    }

    private static boolean isPermanentRedirect(AwsServiceException e) {
        String code = errorCode(e);
        return code != null && code.contains("PermanentRedirect");
    }

    private static boolean isForeignOwner(AwsServiceException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return msg.contains("another account") || msg.contains("not owned")
                || msg.contains("AccessDenied") && msg.contains("owner");
    }

    private static boolean isAlreadyOwnedByYou(AwsServiceException e) {
        String code = errorCode(e);
        return code != null && code.contains("BucketAlreadyOwnedByYou");
    }

    private static String errorCode(AwsServiceException e) {
        return e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
