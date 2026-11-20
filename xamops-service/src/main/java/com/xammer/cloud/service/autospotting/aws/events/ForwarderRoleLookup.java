package com.xammer.cloud.service.autospotting.aws.events;

import com.xammer.cloud.domain.CloudAccount;

import java.util.Optional;

/**
 * The {@code iam:GetRole} fallback of the sender check (design-aws.md §9.5): the unique id of
 * {@code xamops-as-events-fwd-role{suffix}} in the customer account, read through the customer's
 * read role. Used only when the stack output {@code AutoSpottingForwarderRoleId} was never stored. The
 * legacy role's name is never looked up.
 */
public interface ForwarderRoleLookup {

    /**
     * @return the role id, or empty when the role does not exist
     * @throws RuntimeException on any read failure; the caller retries the message (never trusts it)
     */
    Optional<String> roleId(CloudAccount account, String region, String suffix);
}
