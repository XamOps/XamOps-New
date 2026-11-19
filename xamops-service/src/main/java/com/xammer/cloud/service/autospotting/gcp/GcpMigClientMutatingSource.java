package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.springframework.stereotype.Component;

/** Builds the mutating facade for an account's {@code gcp_project_id} through {@link GcpMigClient#mutating}. */
@Component
public class GcpMigClientMutatingSource implements GcpMutatingSource {
    private final GcpMigClient client;

    public GcpMigClientMutatingSource(GcpMigClient client) {
        this.client = client;
    }

    @Override
    public GcpMigClient.Mutating mutating(CloudAccount account) {
        String project = account.getGcpProjectId();
        if (project == null || project.isBlank()) {
            throw new GcpReadException("project", ErrorKind.NOT_FOUND);
        }
        return client.mutating(project);
    }
}
