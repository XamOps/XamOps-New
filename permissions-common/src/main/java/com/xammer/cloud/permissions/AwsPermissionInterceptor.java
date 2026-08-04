package com.xammer.cloud.permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

/**
 * Watches every AWS call XamOps makes on a customer's behalf and records the ones
 * that come back "not authorised".
 *
 * This sits at the SDK layer rather than in each service because the alternative
 * is a try/catch around several hundred call sites, which would be missed the
 * moment anyone adds a new one. Attaching here means any client built by
 * {@code AwsClientProvider} is covered for free, including background refreshes
 * that run on scheduler threads with no HTTP request in scope.
 */
@Component
public class AwsPermissionInterceptor implements ExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AwsPermissionInterceptor.class);

    private final PermissionGapRegistry registry;

    public AwsPermissionInterceptor(PermissionGapRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
        try {
            String accountId = attributes.getAttribute(XamOpsExecutionAttributes.ACCOUNT_ID);
            if (accountId == null) {
                return; // Not a customer-account client (host/internal call) — nothing to report.
            }

            AwsDenialClassifier.Result denial = AwsDenialClassifier.classify(context.exception());
            if (denial == null) {
                return;
            }

            registry.record(buildGap(accountId, attributes, denial));
        } catch (Exception e) {
            // An interceptor must never turn a failed AWS call into a different failure.
            log.debug("Permission tracking skipped for a failed call: {}", e.getMessage());
        }
    }

    @Override
    public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
        try {
            String accountId = attributes.getAttribute(XamOpsExecutionAttributes.ACCOUNT_ID);
            if (accountId == null) {
                return;
            }
            registry.resolveIfKnown(accountId, actionFor(attributes));
        } catch (Exception e) {
            log.debug("Permission clearing skipped for a successful call: {}", e.getMessage());
        }
    }

    private PermissionGap buildGap(String accountId, ExecutionAttributes attributes,
            AwsDenialClassifier.Result denial) {

        // A denial raised while assuming the role is about sts:AssumeRole, whatever
        // service the caller was actually trying to reach.
        if (denial.isFromSts()) {
            return PermissionGaps.roleUnreachable(accountId, denial.getErrorCode(), denial.getMessage());
        }

        return PermissionGaps.denied(
                accountId,
                servicePrefixFor(attributes),
                attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME),
                attributes.getAttribute(XamOpsExecutionAttributes.REGION),
                denial.getErrorCode(),
                denial.getMessage(),
                denial.getKind());
    }

    private String actionFor(ExecutionAttributes attributes) {
        String operation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
        return servicePrefixFor(attributes) + ":" + (operation != null ? operation : "*");
    }

    private String servicePrefixFor(ExecutionAttributes attributes) {
        String prefix = attributes.getAttribute(XamOpsExecutionAttributes.IAM_SERVICE_PREFIX);
        if (prefix != null) {
            return prefix;
        }
        return AwsPermissionCatalog.iamPrefixForClient(null,
                attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME));
    }
}
