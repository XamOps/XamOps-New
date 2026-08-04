package com.xammer.cloud.permissions;

import software.amazon.awssdk.core.interceptor.ExecutionAttribute;

/**
 * Context we attach to every AWS client we build so the permission interceptor
 * knows, at failure time, whose account the call belonged to.
 *
 * The SDK cannot tell us this — {@code AwsClientProvider} is the only place that
 * knows a client was built for a particular {@code CloudAccount}, so it stamps
 * these on the client's override configuration once at build time.
 */
public final class XamOpsExecutionAttributes {

    private XamOpsExecutionAttributes() {
    }

    /** Provider account id (AWS account number) the client was built for. */
    public static final ExecutionAttribute<String> ACCOUNT_ID =
            new ExecutionAttribute<>("XamOpsAccountId");

    /** IAM service prefix for policy actions, e.g. {@code ec2}, {@code ce}. */
    public static final ExecutionAttribute<String> IAM_SERVICE_PREFIX =
            new ExecutionAttribute<>("XamOpsIamServicePrefix");

    /** Region the client was pinned to, so a gap can say where it was seen. */
    public static final ExecutionAttribute<String> REGION =
            new ExecutionAttribute<>("XamOpsClientRegion");
}
