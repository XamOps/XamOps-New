package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.springframework.stereotype.Component;

/** Builds the read-only view for an account's {@code gcp_project_id} through {@link GcpMigClient#readOnly}. */
@Component
public class GcpViewFactory implements GcpViewSource {
    private final GcpMigClient client;

    public GcpViewFactory(GcpMigClient client) {
        this.client = client;
    }

    @Override
    public GcpReadOnlyView view(CloudAccount account) {
        String project = account.getGcpProjectId();
        if (project == null || project.isBlank()) {
            throw new GcpReadException("project", ErrorKind.NOT_FOUND);
        }
        return new GcpReadOnlyView(project, client.readOnly(project));
    }
}
