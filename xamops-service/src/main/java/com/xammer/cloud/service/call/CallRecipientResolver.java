package com.xammer.cloud.service.call;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Works out who a call should ring.
 *
 * Two directions, two rules. A customer calling rings the whole BillOps admin
 * pool. An admin calling rings the people who can actually see that ticket's
 * cloud account — which is not simply "everyone at the client", because sub-users
 * are scoped to specific accounts.
 */
@Service
public class CallRecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(CallRecipientResolver.class);

    /** Matches ROLE_BILLOPS_ADMIN and ROLE_BILLOPS_ADMIN_SUB alike. */
    private static final String ADMIN_ROLE_FRAGMENT = "BILLOPS_ADMIN";

    private final UserRepository userRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final UserCloudAccountAccessRepository accessRepository;

    public CallRecipientResolver(UserRepository userRepository,
            CloudAccountRepository cloudAccountRepository,
            UserCloudAccountAccessRepository accessRepository) {
        this.userRepository = userRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.accessRepository = accessRepository;
    }

    /**
     * The BillOps admin pool. Everyone is rung; the first to accept wins and the
     * rest are offered a join instead.
     */
    @Transactional(readOnly = true)
    public List<User> resolveAdminPool(Long excludeUserId) {
        List<User> admins = userRepository.findByRoleContainingIgnoreCase(ADMIN_ROLE_FRAGMENT);
        return admins.stream()
                .filter(u -> !Objects.equals(u.getId(), excludeUserId))
                .collect(Collectors.toList());
    }

    /**
     * Users on the customer side who can see this ticket's account.
     *
     * Primary users hold access implicitly through their client; sub-users only
     * through explicit rows in user_cloud_account_access. If the ticket's account
     * cannot be matched to a cloud account row we fall back to the whole client
     * rather than ringing nobody — a slightly wide ring is recoverable, a silent
     * one looks like the call feature is broken.
     */
    @Transactional(readOnly = true)
    public List<User> resolveTicketAudience(Ticket ticket, Long excludeUserId) {
        Long clientId = ticket.getClient() != null ? ticket.getClient().getId() : null;
        if (clientId == null) {
            log.warn("Ticket {} has no client; cannot resolve call audience", ticket.getId());
            return Collections.emptyList();
        }

        List<User> clientUsers = userRepository.findByClientId(clientId).stream()
                .filter(u -> !Objects.equals(u.getId(), excludeUserId))
                .collect(Collectors.toList());
        if (clientUsers.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> matchedAccountIds = resolveCloudAccountIds(ticket, clientId);
        if (matchedAccountIds.isEmpty()) {
            log.warn("Ticket {} account '{}' matched no cloud account for client {}; "
                    + "ringing all client users", ticket.getId(), ticket.getAccountId(), clientId);
            return clientUsers;
        }

        Set<Long> grantedUserIds = new HashSet<>(accessRepository.findUserIdsByCloudAccountIdIn(matchedAccountIds));

        List<User> audience = new ArrayList<>();
        for (User u : clientUsers) {
            boolean isSubUser = u.getCreatedBy() != null;
            if (!isSubUser || grantedUserIds.contains(u.getId())) {
                audience.add(u);
            }
        }
        return audience;
    }

    /**
     * Maps the ticket's provider-side account string (an AWS account number, GCP
     * project id, or Azure subscription id) onto cloud_account primary keys.
     *
     * Scoped to the ticket's own client on purpose: the same provider account
     * string can legitimately exist under more than one client, and matching
     * globally would leak a call invitation across tenants.
     */
    private List<Long> resolveCloudAccountIds(Ticket ticket, Long clientId) {
        String accountId = ticket.getAccountId();
        if (accountId == null || accountId.isBlank()) {
            return Collections.emptyList();
        }
        String wanted = accountId.trim();

        List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(clientId);
        return clientAccounts.stream()
                .filter(ca -> wanted.equalsIgnoreCase(ca.getAwsAccountId())
                        || wanted.equalsIgnoreCase(ca.getGcpProjectId())
                        || wanted.equalsIgnoreCase(ca.getAzureSubscriptionId()))
                .map(CloudAccount::getId)
                .collect(Collectors.toList());
    }
}
