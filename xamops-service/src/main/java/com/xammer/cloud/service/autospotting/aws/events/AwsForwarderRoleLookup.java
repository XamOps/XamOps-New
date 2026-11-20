package com.xammer.cloud.service.autospotting.aws.events;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsErrors;
import com.xammer.cloud.service.autospotting.aws.AwsReadException;
import com.xammer.cloud.service.autospotting.aws.AwsViewSource;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@link ForwarderRoleLookup} through the customer's read session: one {@code iam:GetRole}. */
@Component
public class AwsForwarderRoleLookup implements ForwarderRoleLookup {

    static final String ROLE_PREFIX = "xamops-as-events-fwd-role";

    private final AwsViewSource views;

    public AwsForwarderRoleLookup(AwsViewSource views) {
        this.views = views;
    }

    @Override
    public Optional<String> roleId(CloudAccount account, String region, String suffix) {
        String name = ROLE_PREFIX + (suffix == null ? "" : suffix);
        try {
            String id = AwsErrors.read("GetRole", () -> views.view(account, region).iam()
                    .getRole(r -> r.roleName(name)).role().roleId());
            return Optional.ofNullable(id);
        } catch (AwsReadException e) {
            if (e.kind() == ErrorKind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
