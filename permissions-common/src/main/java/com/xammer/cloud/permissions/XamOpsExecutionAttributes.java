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

    /**
     * Makes these attribute names unique to this load of the class.
     *
     * <p>The SDK keeps one registry of attribute names for the whole JVM and refuses a second
     * attribute under a name already taken. Spring Boot DevTools restarts the app in a fresh
     * classloader inside the same JVM, so this initializer ran again while the registry — held
     * by the SDK jar, which is not reloaded — still had the first set. It threw, and from then on
     * every AWS client built with permission tracking failed with "Could not initialize class
     * XamOpsExecutionAttributes" until the JVM itself was restarted.
     *
     * <p>The name is only a label: callers read and write these attributes through the
     * constants below, never by name. Declared first so it is set before they are built.
     */
    private static final String LOAD_ID = java.util.UUID.randomUUID().toString().substring(0, 8);

    /** Provider account id (AWS account number) the client was built for. */
    public static final ExecutionAttribute<String> ACCOUNT_ID =
            new ExecutionAttribute<>("XamOpsAccountId#" + LOAD_ID);

    /** IAM service prefix for policy actions, e.g. {@code ec2}, {@code ce}. */
    public static final ExecutionAttribute<String> IAM_SERVICE_PREFIX =
            new ExecutionAttribute<>("XamOpsIamServicePrefix#" + LOAD_ID);

    /** Region the client was pinned to, so a gap can say where it was seen. */
    public static final ExecutionAttribute<String> REGION =
            new ExecutionAttribute<>("XamOpsClientRegion#" + LOAD_ID);
}
