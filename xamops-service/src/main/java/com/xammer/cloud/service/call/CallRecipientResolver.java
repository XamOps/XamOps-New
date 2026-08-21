package com.xammer.cloud.service.call;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    /** Upper bound on how many people one call may ring. */
    @Value("${xamops.calls.max-ring-fanout:25}")
    private int maxFanout = 25;

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
     *
     * The admin already assigned to the ticket is put first. Ordering is not just
     * cosmetic: the fan-out is capped, so ordering decides who is still in the set
     * when the cap bites. Ringing the whole pool rather than only the assignee is
     * intentional — a support call that goes unanswered because one person stepped
     * away is worse than a call several people see.
     */
    @Transactional(readOnly = true)
    public List<User> resolveAdminPool(Ticket ticket, Long excludeUserId) {
        List<User> admins = userRepository.findByRoleContainingIgnoreCase(ADMIN_ROLE_FRAGMENT).stream()
                .filter(u -> !Objects.equals(u.getId(), excludeUserId))
                .collect(Collectors.toList());

        Long assignedId = ticket != null ? ticket.getAssignedToId() : null;
        if (assignedId == null) {
            return cap(admins);
        }
        List<User> ordered = new ArrayList<>(admins.size());
        admins.stream()
                .filter(u -> Objects.equals(u.getId(), assignedId))
                .findFirst()
                .ifPresent(ordered::add);
        for (User u : admins) {
            if (!Objects.equals(u.getId(), assignedId)) {
                ordered.add(u);
            }
        }
        return cap(ordered);
    }

    /**
     * Bounds the ring set.
     *
     * The admin pool is a single global list with no upper bound, and every ring
     * writes a participant row, costs a presence lookup, and — when the call goes
     * unanswered — sends a missed-call email. Left uncapped that turns one ignored
     * call into an inbox-wide event as the team grows. The cap is generous enough
     * that a realistically sized support rota is never truncated.
     */
    private List<User> cap(List<User> candidates) {
        if (candidates.size() <= maxFanout) {
            return candidates;
        }
        log.warn("Ring set of {} exceeds the {} recipient cap; ringing the first {}",
                candidates.size(), maxFanout, maxFanout);
        return candidates.subList(0, maxFanout);
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
            return cap(prioritiseCreator(ticket, clientUsers));
        }

        Set<Long> grantedUserIds = new HashSet<>(accessRepository.findUserIdsByCloudAccountIdIn(matchedAccountIds));

        List<User> audience = new ArrayList<>();
        for (User u : clientUsers) {
            boolean isSubUser = u.getCreatedBy() != null;
            if (!isSubUser || grantedUserIds.contains(u.getId())) {
                audience.add(u);
            }
        }
        return cap(prioritiseCreator(ticket, audience));
    }

    /**
     * Puts whoever raised the ticket at the front of the ring set.
     *
     * They are the one person guaranteed to know what the call is about, and — as
     * with the admin pool — ordering decides who survives the fan-out cap.
     */
    private List<User> prioritiseCreator(Ticket ticket, List<User> audience) {
        Long creatorId = (ticket != null && ticket.getCreator() != null)
                ? ticket.getCreator().getId()
                : null;
        if (creatorId == null || audience.size() < 2) {
            return audience;
        }
        List<User> ordered = new ArrayList<>(audience.size());
        audience.stream()
                .filter(u -> Objects.equals(u.getId(), creatorId))
                .findFirst()
                .ifPresent(ordered::add);
        for (User u : audience) {
            if (!Objects.equals(u.getId(), creatorId)) {
                ordered.add(u);
            }
        }
        return ordered;
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
