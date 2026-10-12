package com.xammer.cloud.service.automation.workload.access;

import com.xammer.cloud.service.K8sClientFactory;
import io.fabric8.kubernetes.client.KubernetesClientException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Maps any failure encountered while opening a cluster connection onto one of the two typed
 * {@link ClusterAccessException.Kind}s.
 *
 * <p>Shared by both providers so the same underlying failure cannot classify differently depending
 * on which path hit it. The rule it encodes is a question the user can act on: <em>is this a
 * permission/configuration problem, or a reachability problem?</em>
 *
 * <p>The unclassified default is {@code CLUSTER_UNREACHABLE}, because the only honest statement
 * about a failure we cannot identify is that the connection was not established. Claiming
 * {@code CLUSTER_ACCESS_NOT_CONFIGURED} would send the customer to edit an access entry that may
 * already be correct.
 */
final class ClusterAccessFailures {

    /** AWS error codes that mean "the role is not allowed", not "the service is down". */
    private static final List<String> DENIED_ERROR_CODES = List.of(
            "accessdenied", "accessdeniedexception", "unauthorizedoperation", "unauthorized",
            "invalidclienttokenid", "unrecognizedclient", "expiredtoken", "resourcenotfound",
            "resourcenotfoundexception", "notfound", "invalidparameterexception");

    private ClusterAccessFailures() {
    }

    /**
     * @param what human-readable description of the operation that failed, used verbatim in the
     *             exception message
     */
    static ClusterAccessException classify(String what, Throwable failure) {
        for (Throwable node : flatten(failure)) {

            // A missing or unparseable CA is a configuration problem, not a network one. The B7 fix
            // raises this instead of connecting insecurely.
            if (node instanceof K8sClientFactory.TlsTrustException) {
                return notConfigured(what, node);
            }

            // Network layer: nothing answered, or not in time.
            if (node instanceof UnknownHostException || node instanceof ConnectException
                    || node instanceof SocketTimeoutException || node instanceof NoRouteToHostException
                    || node instanceof TimeoutException) {
                return unreachable(what, node);
            }

            // AWS, with a status code and an error code to read.
            if (node instanceof AwsServiceException aws) {
                if (isDenied(aws)) {
                    return notConfigured(what, node);
                }
                return unreachable(what, node);
            }
            if (node instanceof SdkClientException) {
                // Raised for connection, DNS and TLS problems reaching an AWS endpoint.
                return unreachable(what, node);
            }

            // The API server answered, and said no.
            if (node instanceof KubernetesClientException k8s) {
                int code = k8s.getCode();
                if (code == 401 || code == 403 || code == 404) {
                    return notConfigured(what, node);
                }
                if (code > 0) {
                    return unreachable(what, node);
                }
                // code 0 means the request never got an HTTP response; fall through to the
                // transport-level checks on the rest of the chain.
            }

            if (node instanceof IOException && mentionsUnreachable(node.getMessage())) {
                return unreachable(what, node);
            }
        }
        return unreachable(what, failure);
    }

    private static boolean isDenied(AwsServiceException aws) {
        if (aws.statusCode() == 403 || aws.statusCode() == 401 || aws.statusCode() == 404) {
            return true;
        }
        String code = aws.awsErrorDetails() == null ? null : aws.awsErrorDetails().errorCode();
        if (code == null) {
            return false;
        }
        String normalised = code.toLowerCase(Locale.ROOT);
        return DENIED_ERROR_CODES.stream().anyMatch(normalised::contains);
    }

    private static boolean mentionsUnreachable(String message) {
        if (message == null) {
            return false;
        }
        String normalised = message.toLowerCase(Locale.ROOT);
        return normalised.contains("timed out") || normalised.contains("timeout")
                || normalised.contains("unreachable") || normalised.contains("connection refused")
                || normalised.contains("connection reset");
    }

    private static ClusterAccessException notConfigured(String what, Throwable cause) {
        return new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                what + ": XamOps is not authorised on this cluster's API. "
                        + "Check that an EKS access entry maps the XamOps role and binds it to the "
                        + "XamOps ClusterRole.", cause);
    }

    private static ClusterAccessException unreachable(String what, Throwable cause) {
        return new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_UNREACHABLE,
                what + ": the cluster's API server could not be reached. "
                        + "A private endpoint needs a network path from XamOps, or the agent "
                        + "execution path.", cause);
    }

    /** The cause and suppressed graph, cycle-safe. */
    private static List<Throwable> flatten(Throwable root) {
        List<Throwable> all = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        List<Throwable> queue = new ArrayList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Throwable next = queue.remove(0);
            if (next == null || seen.put(next, Boolean.TRUE) != null) {
                continue;
            }
            all.add(next);
            queue.add(next.getCause());
            queue.addAll(List.of(next.getSuppressed()));
        }
        return all;
    }
}
