package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.service.gcp.GcpClientProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Connect-time permission check (design-gcp.md §10.6, FR-ONB-04). One {@code projects.testIamPermissions}
 * call with the read set and the {@code xamopsAutoSpotting} set, classified into the access type stored in
 * {@code cloud_account.gcp_access_type}.
 *
 * <p>Known gap: neither script mode grants {@code storage.buckets.get}, and this check does not test it, so a
 * missing bucket read does not change the access type or refuse a connect. Tracked in the phase 29 evidence.
 */
@Component
public class GcpAccessValidator {
    public static final String READ_ONLY = "READ_ONLY";
    public static final String AUTOSPOTTING = "AUTOSPOTTING";
    public static final String READ_WRITE = "READ_WRITE";

    /** Read set: what every connected project needs. */
    public static final List<String> READ_SET = List.of(
            "compute.instanceGroupManagers.get", "compute.instanceTemplates.get", "compute.regions.get");
    /** The permissions that make a credential an AutoSpotting one. */
    public static final List<String> AUTOSPOTTING_SET = List.of(
            "compute.instanceTemplates.create", "compute.instanceTemplates.delete",
            "compute.instanceTemplates.useReadOnly", "compute.instanceGroupManagers.update");
    /** Granted by {@code xamopsComputeOps} (or the legacy project-wide instanceAdmin): marks read-write. */
    public static final String READ_WRITE_MARKER = "compute.instances.setMachineType";
    /** Version of the custom role the code expects: {@code xamops-as-role v1}, the 14-permission set. */
    public static final int CURRENT_ROLE_VERSION = 1;
    /** Permissions per role version; the last entry is the current one. */
    public static final List<List<String>> ROLE_VERSIONS = List.of(List.of(
            "compute.disks.create", "compute.images.useReadOnly", "compute.instanceGroupManagers.get",
            "compute.instanceGroupManagers.update", "compute.instanceTemplates.create",
            "compute.instanceTemplates.delete", "compute.instanceTemplates.get",
            "compute.instanceTemplates.useReadOnly", "compute.instances.create", "compute.instances.setLabels",
            "compute.instances.setMetadata", "compute.instances.setTags", "compute.subnetworks.use",
            "compute.subnetworks.useExternalIp"));

    public static List<String> currentRolePermissions() {
        return ROLE_VERSIONS.get(CURRENT_ROLE_VERSION - 1);
    }

    /** Result of a check. {@code roleOutdated}: an AutoSpotting credential missing part of the current role version. */
    public record Result(String accessType, boolean roleOutdated, Set<String> missingFromCurrentRole) { }

    /** A credential missing part of the read set; nothing is saved. */
    public static class MissingReadPermissionException extends RuntimeException {
        private final List<String> missing;

        public MissingReadPermissionException(List<String> missing) {
            super("The credential is missing read permissions on this project: " + String.join(", ", missing)
                    + ". Re-run the setup script and try again.");
            this.missing = List.copyOf(missing);
        }

        public List<String> missing() {
            return missing;
        }
    }

    private final GcpClientProvider provider;

    public GcpAccessValidator(GcpClientProvider provider) {
        this.provider = provider;
    }

    /** Runs {@code projects.testIamPermissions} with the supplied key and classifies the answer. */
    public Result validate(String serviceAccountKey, String projectId) {
        Set<String> probe = new LinkedHashSet<>(READ_SET);
        probe.addAll(AUTOSPOTTING_SET);
        probe.add(READ_WRITE_MARKER);
        probe.addAll(currentRolePermissions());
        Set<String> granted;
        try {
            granted = provider.testProjectPermissions(serviceAccountKey, projectId, new ArrayList<>(probe));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not check the credential's permissions on project '" + projectId
                    + "': " + e.getMessage(), e);
        }
        return classify(granted);
    }

    /** Pure classification of the granted permissions. Throws when part of the read set is missing. */
    public static Result classify(Set<String> granted) {
        List<String> missingRead = READ_SET.stream().filter(p -> !granted.contains(p)).toList();
        if (!missingRead.isEmpty()) {
            throw new MissingReadPermissionException(missingRead);
        }
        if (!granted.containsAll(AUTOSPOTTING_SET)) {
            return new Result(READ_ONLY, false, Set.of());
        }
        Set<String> missingCurrent = new TreeSet<>();
        for (String p : currentRolePermissions()) {
            if (!granted.contains(p)) {
                missingCurrent.add(p);
            }
        }
        String type = granted.contains(READ_WRITE_MARKER) ? READ_WRITE : AUTOSPOTTING;
        return new Result(type, !missingCurrent.isEmpty(), missingCurrent);
    }
}
