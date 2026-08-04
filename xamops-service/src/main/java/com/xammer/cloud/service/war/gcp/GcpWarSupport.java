package com.xammer.cloud.service.war.gcp;

import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.GetPolicyOptions;
import com.google.iam.v1.Policy;
import com.xammer.cloud.service.gcp.GcpClientProvider;

import java.util.List;
import java.util.Optional;

/**
 * Small helpers shared by the GCP WAR check classes.
 */
final class GcpWarSupport {

    /**
     * Audit configs and conditional bindings are only returned on a version-3 policy
     * request. Asking for the default version silently omits both, which would make an
     * unaudited project look compliant.
     */
    private static final int IAM_POLICY_VERSION = 3;

    /** Evidence lines name a few principals rather than dumping a 200-member binding. */
    private static final int PREVIEW_LIMIT = 4;

    private GcpWarSupport() {
    }

    /** The project's IAM policy, including audit configs. Throws so {@code guard} records why. */
    static Policy projectIamPolicy(GcpClientProvider clients, String projectId) {
        Optional<ProjectsClient> clientOpt = clients.getProjectsClient(projectId);
        if (clientOpt.isEmpty()) {
            throw new IllegalStateException(
                    "Cloud Resource Manager client unavailable for " + projectId);
        }
        try (ProjectsClient projects = clientOpt.get()) {
            return projects.getIamPolicy(GetIamPolicyRequest.newBuilder()
                    .setResource("projects/" + projectId)
                    .setOptions(GetPolicyOptions.newBuilder()
                            .setRequestedPolicyVersion(IAM_POLICY_VERSION)
                            .build())
                    .build());
        }
    }

    /** Renders the first few members of a binding, with a count for the remainder. */
    static String preview(List<String> members) {
        if (members == null || members.isEmpty()) {
            return "none";
        }
        if (members.size() <= PREVIEW_LIMIT) {
            return String.join(", ", members);
        }
        return String.join(", ", members.subList(0, PREVIEW_LIMIT))
                + String.format(" and %d more", members.size() - PREVIEW_LIMIT);
    }
}
