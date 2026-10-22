package com.xammer.billops.service;

import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single answer to "which cloud accounts may this BillOps admin see, and therefore which clients?".
 *
 * <p>The admin console is cross-tenant: its Accounts, Tickets, Billing and Credit screens start
 * from every account there is and then narrow down. Four controllers each used to work that out
 * their own way; this service is the one place that decides, so all of them agree.
 *
 * <p><b>Return contract</b>, shared with {@link AccountAccessHelper}: {@code null} means <em>no
 * restriction</em> — show everything. A set means restrict to those ids, and an empty set means
 * show nothing.
 *
 * <p>Resolution order, most specific first:
 * <ol>
 *   <li>Explicit cloud-account grants in {@code user_cloud_account_access}, assigned from the
 *       Cloud Account Access picker in the BillOps admin User Manager. Any row restricts the user
 *       to exactly those accounts.</li>
 *   <li>No grants and {@code allAccountsAccess} set — full access, handed out explicitly.</li>
 *   <li>No grants and {@code ROLE_BILLOPS_ADMIN_SUB} — the accounts of the clients they own via
 *       {@code client.owner_admin_id}, which is how sub-admins were scoped before the picker
 *       existed. A sub-admin who owns nothing sees nothing; that is the pre-existing behaviour and
 *       is deliberately <em>not</em> fail-open.</li>
 *   <li>Anything else — no restriction.</li>
 * </ol>
 *
 * <p>Note the asymmetry between rule 1 and rule 4: a user with zero grants is unrestricted. That
 * is what makes this safe to deploy — no admin's scope changes until someone assigns them
 * accounts — but it also means a grant list can never express "this admin sees nothing". Clearing
 * the list hands back full access, which is what the picker's help text says.
 */
@Service
public class AdminAccessScope {

    private static final Logger log = LoggerFactory.getLogger(AdminAccessScope.class);

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    /**
     * Cloud-account ids the given user may see, or {@code null} for no restriction.
     *
     * @param user the admin to resolve; {@code null} yields no restriction, matching the "could
     *             not identify the caller, do not silently blank their screens" stance the
     *             existing helpers take
     */
    public Set<Long> resolveAccountIds(AppUser user) {
        if (user == null || user.getId() == null) {
            return null;
        }

        List<Long> granted = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(user.getId());
        if (!granted.isEmpty()) {
            return new HashSet<>(granted);
        }

        if (user.isAllAccountsAccess()) {
            return null;
        }

        if ("ROLE_BILLOPS_ADMIN_SUB".equals(user.getRole())) {
            List<Client> owned = clientRepository.findByOwnerAdminId(user.getId());
            if (owned.isEmpty()) {
                return Collections.emptySet();
            }
            Set<Long> ownedClientIds = owned.stream().map(Client::getId).collect(Collectors.toSet());
            return cloudAccountRepository.findAll().stream()
                    .filter(a -> a.getClient() != null && ownedClientIds.contains(a.getClient().getId()))
                    .map(CloudAccount::getId)
                    .collect(Collectors.toSet());
        }

        return null;
    }

    /**
     * Client ids the given user may manage, or {@code null} for no restriction.
     *
     * <p>Derived from the account scope rather than stored: an admin granted one account of a
     * client is, by that grant, an admin of that client — which is the rule the support inbox and
     * the ticket lists already applied to admin sub-users.
     */
    public Set<Long> resolveClientIds(AppUser user) {
        Set<Long> accountIds = resolveAccountIds(user);
        if (accountIds == null) {
            return null;
        }
        if (accountIds.isEmpty()) {
            return Collections.emptySet();
        }
        return cloudAccountRepository.findAllById(accountIds).stream()
                .filter(a -> a.getClient() != null)
                .map(a -> a.getClient().getId())
                .collect(Collectors.toSet());
    }

    /** {@link #resolveAccountIds(AppUser)} for whoever is on the current request. */
    public Set<Long> accountIdsForCurrentUser() {
        return resolveAccountIds(currentUser());
    }

    /** {@link #resolveClientIds(AppUser)} for whoever is on the current request. */
    public Set<Long> clientIdsForCurrentUser() {
        return resolveClientIds(currentUser());
    }

    /**
     * The authenticated {@code AppUser}, or null when there is no usable principal.
     *
     * <p>Resolved by username rather than by the id in the JWT: a {@code ROLE_BILLOPS_ADMIN_SUB}
     * token can carry an id belonging to a different tenant database, which is why every other
     * scoping path here already falls back to the username.
     */
    public AppUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        AppUser user = appUserRepository.findByUsername(auth.getName()).orElse(null);
        if (user == null) {
            log.debug("[AdminAccessScope] no app_user row for '{}' — resolving as unrestricted", auth.getName());
        }
        return user;
    }

    /** Narrows a client list to the scope, leaving it untouched when there is no restriction. */
    public List<Client> filterClients(List<Client> clients, Set<Long> scope) {
        if (scope == null) {
            return clients;
        }
        if (scope.isEmpty()) {
            return Collections.emptyList();
        }
        return clients.stream().filter(c -> scope.contains(c.getId())).collect(Collectors.toList());
    }

    /** Narrows a cloud-account list to the scope, leaving it untouched when unrestricted. */
    public List<CloudAccount> filterAccounts(List<CloudAccount> accounts, Set<Long> scope) {
        if (scope == null) {
            return accounts;
        }
        if (scope.isEmpty()) {
            return Collections.emptyList();
        }
        return accounts.stream().filter(a -> scope.contains(a.getId())).collect(Collectors.toList());
    }
}
