package com.xammer.billops.service;

import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.UserCloudAccountAccess;
import com.xammer.billops.domain.UserTabAccess;
import com.xammer.billops.dto.AdminClientDTO;
import com.xammer.billops.dto.UserDTO;
import com.xammer.billops.repository.AgreementRepository;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.SupportMessageRepository;
import com.xammer.billops.repository.SupportTicketStatusRepository;
import com.xammer.billops.repository.TicketReplyRepository;
import com.xammer.billops.repository.TicketRcaRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.repository.UserPlatformConnectionRepository;
import com.xammer.billops.repository.UserTabAccessRepository;
import com.xammer.cloud.messaging.MessagingProperties;
import com.xammer.cloud.messaging.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AppUserService {

    private static final Logger log = LoggerFactory.getLogger(AppUserService.class);

    /**
     * Key prefix of the admin client list cached by xamops-service
     * ({@code BillOpsAdminController.CLIENTS_CACHE_KEY}, plus a per-sub-admin {@code :sub:{id}}
     * suffix). Deleting a client here has to evict both tiers or the other screens keep showing it.
     */
    private static final String ADMIN_CLIENTS_CACHE_PREFIX = "billops:admin:clients";

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private UserTabAccessRepository userTabAccessRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TicketReplyRepository ticketReplyRepository;

    @Autowired
    private TicketRcaRepository ticketRcaRepository;

    @Autowired
    private AgreementRepository agreementRepository;

    @Autowired
    private CreditRequestRepository creditRequestRepository;

    @Autowired
    private SupportMessageRepository supportMessageRepository;

    @Autowired
    private SupportTicketStatusRepository supportTicketStatusRepository;

    @Autowired
    private UserPlatformConnectionRepository userPlatformConnectionRepository;

    @Autowired
    private AdminAccessScope adminAccessScope;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Autowired
    private MessagingProperties messagingProperties;

    @Value("${app.login-url}")
    private String loginUrl;

    /**
     * Roles whose sidebar can be narrowed with {@code user_tab_access} rows.
     *
     * <p>The key vocabulary differs per role family and the two must never mix: ROLE_XAMOPS stores
     * XamOps module/tab keys (frontend {@code constants/xamopsTabs.ts}), the admin roles store
     * admin section keys ({@code constants/billopsAdminTabs.ts}). Both land in the same table, so
     * the UI clears the selection when the role is switched between families — a leftover
     * {@code finops-cost} row on an admin user would simply never match anything and would read as
     * "restricted to nothing visible".
     */
    private static final java.util.Set<String> UI_TAB_SCOPED_ROLES = java.util.Set.of(
            "ROLE_XAMOPS", "ROLE_BILLOPS_ADMIN", "ROLE_BILLOPS_ADMIN_SUB");

    private static boolean isTabScopedRole(String role) {
        return role != null && UI_TAB_SCOPED_ROLES.contains(role);
    }

    /**
     * Narrows a requested cloud-account grant list to what the caller may actually hand out.
     *
     * <p>The account picker for a BillOps admin user offers every account in the platform, and
     * {@code GET /api/billops/users/cloud-accounts} scopes that catalog to the caller — but the
     * catalog is only a suggestion, and {@code accountAccessIds} arrives on the request body. An
     * admin restricted to three accounts could otherwise POST any id and mint a user who sees
     * accounts they cannot see themselves, then sign in as them. Access has to be delegable
     * downwards only, so the request is intersected with the caller's own scope here, on the write
     * path, rather than trusted because the UI offered a short list.
     *
     * <p>Silently dropping rather than rejecting: a caller whose own access was narrowed between
     * opening the form and saving it should not get an error naming accounts they can no longer
     * see, and the result — only the accounts they hold — is the same either way. Dropped ids are
     * logged.
     */
    private List<Long> grantableAccountIds(List<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            return requested;
        }
        java.util.Set<Long> callerScope = adminAccessScope.accountIdsForCurrentUser();
        if (callerScope == null) {
            return requested; // caller is unrestricted — anything in the platform is theirs to give
        }
        List<Long> allowed = requested.stream().filter(callerScope::contains).collect(Collectors.toList());
        if (allowed.size() != requested.size()) {
            List<Long> dropped = requested.stream().filter(id -> !callerScope.contains(id)).collect(Collectors.toList());
            log.warn("Dropped cloud-account grants {} — outside the granting admin's own access", dropped);
        }
        return allowed;
    }

    /**
     * Rejects a contact number the notification sender would not be able to dial.
     *
     * <p>Checked with {@link PhoneNumbers#toE164} rather than a regex because that is the exact
     * code the send path runs: anything this accepts will normalise identically at send time, and
     * anything it rejects would otherwise be stored, silently skipped at dispatch, and surface only
     * as "the customer never received their alerts".
     *
     * <p>Blank is allowed — a user with no mobile number receives email only. Throws
     * {@link IllegalArgumentException}, which the controller already converts to a 400 carrying this
     * message, matching how the duplicate-username check surfaces.
     */
    private void validateContact(String contact) {
        if (contact == null || contact.isBlank()) {
            return;
        }
        if (contact.trim().length() > 20) {
            // app_user.contact is varchar(20); a longer value fails at the DB with an opaque error.
            throw new IllegalArgumentException("Contact number must be 20 characters or fewer.");
        }
        if (PhoneNumbers.toE164(contact, messagingProperties.getDefaultCountryCode()) == null) {
            throw new IllegalArgumentException(
                    "'" + contact + "' is not a usable mobile number. Enter digits only, "
                    + "optionally with a country code — for example 9876543210 or +91 98765 43210.");
        }
    }

    public AppUser createUser(UserDTO dto) {
        // Check if username already exists
        if (appUserRepository.findByUsername(dto.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists: " + dto.getUsername());
        }
        validateContact(dto.getContact());

        // Resolve the caller to detect ROLE_BILLOPS_ADMIN_SUB (for client ownership + createdBy tagging)
        // Use SecurityContext authority as authoritative role (DB role field may be null for sub-admins)
        org.springframework.security.core.Authentication callerAuth =
                SecurityContextHolder.getContext().getAuthentication();
        String callerUsername = callerAuth != null ? callerAuth.getName() : null;
        boolean callerIsSubAdmin = callerAuth != null && callerAuth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_BILLOPS_ADMIN_SUB".equals(a.getAuthority()));
        AppUser callerUser = (callerUsername != null)
                ? appUserRepository.findByUsername(callerUsername).orElse(null)
                : null;

        // Handle client
        Client client = clientRepository.findByName(dto.getClientName());
        if (client == null) {
            client = new Client(dto.getClientName());
            // If a ROLE_BILLOPS_ADMIN_SUB is creating this client, tag it with their ID
            if (callerIsSubAdmin && callerUser != null) {
                client.setOwnerAdminId(callerUser.getId());
            }
            client = clientRepository.save(client); // DB generates ID
        } else if (callerIsSubAdmin && callerUser != null && client.getOwnerAdminId() == null) {
            // Existing client not yet owned — claim it for this sub-admin
            client.setOwnerAdminId(callerUser.getId());
            client = clientRepository.save(client);
        }

        // Create user
        AppUser user = new AppUser(dto.getUsername(), resolveCreatePasswordHash(dto.getPassword()), dto.getEmail(),
                dto.getRole(), client);
        user.setContact(dto.getContact());
        user.setDesignation(dto.getDesignation());
        // Set parent relationship: use explicit parentId from form, or auto-derive from calling sub-admin
        if (dto.getParentId() != null) {
            user.setCreatedBy(dto.getParentId());
        } else if (callerIsSubAdmin && callerUser != null) {
            // Sub-admin is creating this user — auto-tag createdBy so ticket/data scoping works
            user.setCreatedBy(callerUser.getId());
            log.info("Auto-set createdBy={} for new user '{}' created by SUB_ADMIN '{}'",
                    callerUser.getId(), dto.getUsername(), callerUser.getUsername());
        }
        AppUser savedUser = appUserRepository.save(user);

        // Assign sidebar tab access. Stored for every role whose sidebar can be scoped — XamOps
        // modules for ROLE_XAMOPS, admin sections for the BillOps admin roles — using the key
        // vocabulary the UI sent for that role (see UI_TAB_SCOPED_ROLES).
        if (isTabScopedRole(savedUser.getRole())
                && dto.getAllowedTabs() != null && !dto.getAllowedTabs().isEmpty()) {
            for (String tabName : dto.getAllowedTabs()) {
                userTabAccessRepository.save(new UserTabAccess(savedUser, tabName));
            }
        }

        // Assign cloud-account access (any role). A null list means "none specified" (→ all
        // accounts on read); an explicit list stores one row per resolvable cloud account.
        if (dto.getAccountAccessIds() != null) {
            for (Long accountId : grantableAccountIds(dto.getAccountAccessIds())) {
                cloudAccountRepository.findById(accountId).ifPresent(cloudAccount ->
                        userCloudAccountAccessRepository.save(new UserCloudAccountAccess(savedUser, cloudAccount)));
            }
        }

        // Send welcome email (passwordless — the user signs in with email + OTP)
        try {
            sendWelcomeEmail(dto.getEmail(), dto.getUsername());
            log.info("Welcome email sent successfully to: {}", dto.getEmail());
        } catch (Exception e) {
            log.error("Failed to send welcome email to: {}", dto.getEmail(), e);
            // Don't fail the user creation if email fails
        }

        return savedUser;
    }

    public List<UserDTO> getAllUsers() {
        // Narrow the list to the clients the caller may manage, derived from their granted cloud
        // accounts (AdminAccessScope). Null means no restriction, which is every admin who has
        // not been given an explicit account list.
        java.util.Set<Long> allowedClientIds = adminAccessScope.clientIdsForCurrentUser();

        List<AppUser> users;
        if (allowedClientIds == null) {
            users = appUserRepository.findAll();
        } else if (allowedClientIds.isEmpty()) {
            return java.util.Collections.emptyList();
        } else {
            users = appUserRepository.findAll().stream()
                    .filter(u -> u.getClient() != null && allowedClientIds.contains(u.getClient().getId()))
                    .collect(java.util.stream.Collectors.toList());
            log.info("Scoped user list — returning {} users from {} managed clients",
                    users.size(), allowedClientIds.size());
        }

        return users.stream().map(user -> {
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setUsername(user.getUsername());
            dto.setEmail(user.getEmail());
            dto.setRole(user.getRole());
            dto.setClientId(user.getClient() != null ? user.getClient().getId() : null);
            dto.setClientName(user.getClient() != null ? user.getClient().getName() : "Unknown");
            dto.setContact(user.getContact());
            dto.setDesignation(user.getDesignation());
            dto.setParentId(user.getCreatedBy());  // null = primary user, non-null = sub-user
            // The User Manager edits these in place, so they have to round-trip for every role
            // whose sidebar can be scoped — otherwise Edit User opens with an empty tree and
            // saving silently wipes the user's existing access.
            if (isTabScopedRole(user.getRole())) {
                dto.setAllowedTabs(userTabAccessRepository.findTabNamesByUserId(user.getId()));
            }
            dto.setAccountAccessIds(userCloudAccountAccessRepository.findCloudAccountIdsByUserId(user.getId()));
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * Every client the caller may administer, with the two counts the delete guard checks.
     *
     * <p>The User Manager derives its client rows from the user list, so a client with no users
     * has no row there: it cannot be given a first user and cannot be deleted. This list is what
     * lets the page show those clients too. Scoped exactly as {@link #getAllUsers()} is, so an
     * admin with a managed-client list sees only those.
     *
     * <p>Doubles as the catalog behind the Client Access picker in the user-manager modal, which
     * is why the scoping matters twice over: an admin can only delegate clients they manage
     * themselves.
     */
    public List<AdminClientDTO> getAdminClients() {
        java.util.Set<Long> allowedClientIds = adminAccessScope.clientIdsForCurrentUser();
        if (allowedClientIds != null && allowedClientIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<Client> clients = adminAccessScope.filterClients(
                clientRepository.findAll(), allowedClientIds);

        // Two grouped queries rather than a count pair per client: this list is every client there is.
        java.util.Map<Long, Long> userCounts = toCountMap(appUserRepository.countUsersGroupedByClient());
        java.util.Map<Long, Long> accountCounts = toCountMap(cloudAccountRepository.countAccountsGroupedByClient());

        return clients.stream()
                .map(c -> new AdminClientDTO(
                        c.getId(),
                        c.getName(),
                        c.getEmail(),
                        userCounts.getOrDefault(c.getId(), 0L),
                        accountCounts.getOrDefault(c.getId(), 0L)))
                .sorted(java.util.Comparator.comparing(
                        AdminClientDTO::getName,
                        java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
    }

    /** Collapses {@code [clientId, count]} projection rows into a lookup map. */
    private static java.util.Map<Long, Long> toCountMap(List<Object[]> rows) {
        java.util.Map<Long, Long> counts = new java.util.HashMap<>();
        for (Object[] row : rows) {
            if (row.length >= 2 && row[0] != null && row[1] != null) {
                counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }
        return counts;
    }

    /**
     * Thrown when a client cannot be deleted because something still belongs to it. Carries the
     * counts so the caller can say exactly what is in the way.
     */
    public static class ClientNotEmptyException extends RuntimeException {
        private final long userCount;
        private final long cloudAccountCount;

        ClientNotEmptyException(String message, long userCount, long cloudAccountCount) {
            super(message);
            this.userCount = userCount;
            this.cloudAccountCount = cloudAccountCount;
        }

        public long getUserCount() { return userCount; }
        public long getCloudAccountCount() { return cloudAccountCount; }
    }

    /**
     * Deletes a client that has no users and no cloud accounts.
     *
     * <p>Guarded, never a cascade. {@link Client} maps {@code users} and {@code cloudAccounts}
     * with {@code cascade=ALL, orphanRemoval=true}, and {@code CloudAccount} cascades on down to
     * invoices — so a bare delete of a populated client would take its users out through
     * Hibernate's cascade, which bypasses every reference-clearing step in {@link #deleteUser}
     * (tickets, RCAs, credit requests, agreements, per-user access rows). That ends either as an
     * FK violation or as silently destroyed history. This refuses with
     * {@link ClientNotEmptyException} whenever either count is non-zero, naming both, so the only
     * client that can be removed here is one that is genuinely empty.
     *
     * <p>Mirrors {@code BillOpsAdminController.deleteClient} in xamops-service, including the
     * cache eviction: the admin client list is cached under {@code billops:admin:clients} plus a
     * per-sub-admin {@code :sub:{id}} key, and leaving those would keep the deleted client on
     * screen elsewhere.
     *
     * @param clientId the client to delete
     * @throws IllegalArgumentException  if no such client exists
     * @throws ClientNotEmptyException   if the client still has users or cloud accounts
     */
    @Transactional
    public void deleteClient(Long clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientId));

        long userCount = appUserRepository.countByClientId(clientId);
        long accountCount = cloudAccountRepository.countByClientId(clientId);
        if (userCount > 0 || accountCount > 0) {
            log.info("Refused delete of client {} ('{}'): {} user(s), {} cloud account(s)",
                    clientId, client.getName(), userCount, accountCount);
            throw new ClientNotEmptyException(
                    "Cannot delete client '" + client.getName() + "': it still has " + userCount
                    + " user(s) and " + accountCount + " cloud account(s). Remove them first.",
                    userCount, accountCount);
        }

        clientRepository.delete(client);
        redisCacheService.evictByPrefix(ADMIN_CLIENTS_CACHE_PREFIX);

        // Deliberately at info: the audit trail for who removed a client and when.
        log.info("Client {} ('{}') deleted by admin", clientId, client.getName());
    }

    public AppUser getUserById(Long id) {
        return appUserRepository.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Transactional
    public AppUser updateUser(UserDTO dto) {
        AppUser user = appUserRepository.findById(dto.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        validateContact(dto.getContact());

        // Read before the role is overwritten: the access-sync blocks at the bottom need to know
        // whether this save moved the user out of a role that owned tab or client grants.
        String previousRole = user.getRole();

        // Update basic fields
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setRole(dto.getRole());

        // --- Handle Client ---
        Client client = user.getClient();
        if (client == null) {
            client = new Client(dto.getClientName());
            user.setClient(client);
        } else {
            // Update client name if client already exists
            client.setName(dto.getClientName());
        }

        user.setContact(dto.getContact());
        user.setDesignation(dto.getDesignation());

        // Only update password if not empty
        if (dto.getPassword() != null && !dto.getPassword().trim().isEmpty()) {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }

        AppUser savedUser = appUserRepository.save(user);

        // Sync sidebar tab access — only when the tab list is provided on the request.
        // A null allowedTabs means "leave tab access unchanged"; an explicit (possibly empty)
        // list replaces the current assignment.
        if (isTabScopedRole(savedUser.getRole()) && dto.getAllowedTabs() != null) {
            userTabAccessRepository.deleteByUserId(savedUser.getId());
            for (String tabName : dto.getAllowedTabs()) {
                userTabAccessRepository.save(new UserTabAccess(savedUser, tabName));
            }
        } else if (isTabScopedRole(previousRole) && !isTabScopedRole(savedUser.getRole())) {
            // This save moved the user out of a role with a scoped sidebar. Leaving the old rows
            // behind would hide them from the UI while they still sat in the table, ready to take
            // effect again the moment the user was switched back — and in the other role's key
            // vocabulary, which matches nothing. Narrowed to an actual transition so a save on a
            // role that never had a scoped sidebar touches no rows at all.
            userTabAccessRepository.deleteByUserId(savedUser.getId());
        }

        // Sync cloud-account access — same null/empty contract as tab access above.
        // A null accountAccessIds means "leave unchanged"; an explicit (possibly empty) list
        // replaces the current assignment (empty → zero rows → "all access" on read).
        if (dto.getAccountAccessIds() != null) {
            userCloudAccountAccessRepository.deleteByUserId(savedUser.getId());
            for (Long accountId : grantableAccountIds(dto.getAccountAccessIds())) {
                cloudAccountRepository.findById(accountId).ifPresent(cloudAccount ->
                        userCloudAccountAccessRepository.save(new UserCloudAccountAccess(savedUser, cloudAccount)));
            }
        }

        return savedUser;
    }

    /**
     * Moves a user to an existing client as a standalone primary user.
     *
     * @see #moveUserToClient(Long, Long, Long)
     */
    @Transactional
    public AppUser moveUserToClient(Long userId, Long targetClientId) {
        return moveUserToClient(userId, targetClientId, null);
    }

    /**
     * Moves a user to an existing client and decides where they sit in that client's user tree,
     * keeping the tree consistent in every case.
     *
     * <p>Only client membership and the parent pointer change — tickets, credit requests,
     * agreements and access rows stay with each user, so nothing is lost. The three outcomes are:
     * <ul>
     *   <li><b>{@code newParentUserId} given:</b> the user is nested under that primary user
     *       ({@code created_by} set). The parent must be a primary user <em>of the destination
     *       client</em>, and the user being moved must not own sub-users itself — the tree is
     *       deliberately kept two levels deep. Because the destination client may be the one the
     *       user is already on, this is also how an individual user is re-parented inside its own
     *       client.</li>
     *   <li><b>No parent, user owns sub-users:</b> the primary and <em>all</em> of its sub-users
     *       move together, so the family is never split across two clients.</li>
     *   <li><b>No parent, individual user:</b> it lands as a standalone primary user; if it was a
     *       sub-user it is detached from its parent ({@code created_by} cleared), so a sub-user can
     *       never end up pointing at a parent that lives in another client.</li>
     * </ul>
     *
     * <p>If the move leaves the old client with no users <em>and</em> no cloud accounts, that
     * client is cleaned up, same as {@link #deleteUser}. One that still owns accounts is kept, so
     * this can never cascade away billing history the way an unguarded delete would. A request
     * that would not change anything is a no-op.
     *
     * <p>Note for callers that cache the client list: a successful move can therefore remove a
     * client, which is easy to miss when the moved user was the client's whole population — as it
     * is whenever a primary user takes its sub-users along. Refresh clients, not just users.
     *
     * @param userId           the user to move
     * @param targetClientId   the client to move them to (must already exist)
     * @param newParentUserId  primary user in the destination client to nest them under, or
     *                         {@code null} to place them as a primary user
     * @return the updated user
     * @throws IllegalArgumentException if the destination client does not exist, or if the
     *                                  requested parent would produce an invalid tree
     */
    @Transactional
    public AppUser moveUserToClient(Long userId, Long targetClientId, Long newParentUserId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        // Phrased for the admin, not the log: the usual way to reach this is a stale destination
        // list in the user manager, because a client disappears the moment its last user is moved
        // out (see the cleanup at the end of this method). The raw "Target client not found: 47"
        // this used to throw was surfaced verbatim by the UI and told them nothing actionable.
        Client target = clientRepository.findById(targetClientId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The destination client no longer exists (id " + targetClientId + "). It was most likely "
                        + "removed when its last user was moved out. Refresh the page and pick from the updated "
                        + "client list."));

        Client oldClient = user.getClient();
        Long oldClientId = oldClient != null ? oldClient.getId() : null;
        boolean clientChanged = oldClientId == null || !oldClientId.equals(targetClientId);

        // Sub-users this user owns. Loaded up front because it decides both whether the user may
        // be nested under someone else and whether a client change has to drag a family along.
        List<AppUser> subUsers = appUserRepository.findByCreatedBy(userId);
        boolean hasSubUsers = !subUsers.isEmpty();
        Long currentParentId = user.getCreatedBy();

        if (newParentUserId != null) {
            // --- Place as a sub-user of an existing primary user in the destination client ---
            if (hasSubUsers) {
                throw new IllegalArgumentException(
                        "'" + user.getUsername() + "' already has " + subUsers.size() + " sub-user(s), so it cannot "
                        + "become a sub-user itself. Move it as a primary user instead.");
            }
            if (newParentUserId.equals(userId)) {
                throw new IllegalArgumentException("A user cannot be made a sub-user of themselves.");
            }
            AppUser parent = appUserRepository.findById(newParentUserId)
                    .orElseThrow(() -> new RuntimeException("Parent user not found: " + newParentUserId));
            Long parentClientId = parent.getClient() != null ? parent.getClient().getId() : null;
            if (parentClientId == null || !parentClientId.equals(targetClientId)) {
                throw new IllegalArgumentException(
                        "'" + parent.getUsername() + "' does not belong to '" + target.getName() + "', so it cannot be "
                        + "the parent there. Pick a primary user of that client.");
            }
            if (parent.getCreatedBy() != null) {
                // Nesting under a sub-user would make a third level, which the UI cannot render
                // and which every "primary = created_by IS NULL" check in the app would misread.
                throw new IllegalArgumentException(
                        "'" + parent.getUsername() + "' is itself a sub-user. Pick a primary user as the parent.");
            }

            if (!clientChanged && newParentUserId.equals(currentParentId)) {
                return user; // Already a sub-user of this parent on this client — nothing to do.
            }
            user.setClient(target);
            user.setCreatedBy(newParentUserId);
            log.info("Moved user {} ('{}') from client {} to client {} ('{}') as a sub-user of {} ('{}')",
                    userId, user.getUsername(), oldClientId, targetClientId, target.getName(),
                    parent.getId(), parent.getUsername());
        } else if (hasSubUsers) {
            // --- Primary user with a family: move it and bring the sub-users along ---
            if (!clientChanged && currentParentId == null) {
                return user; // Already a primary user on this client — nothing to do.
            }
            user.setClient(target);
            user.setCreatedBy(null);
            for (AppUser sub : subUsers) {
                sub.setClient(target);
            }
            appUserRepository.saveAll(subUsers);
            log.info("Moved primary user {} ('{}') and {} sub-user(s) from client {} to client {} ('{}')",
                    userId, user.getUsername(), subUsers.size(), oldClientId, targetClientId, target.getName());
        } else {
            // --- Individual user placed as a standalone primary user ---
            if (!clientChanged && currentParentId == null) {
                return user; // Already a standalone primary user on this client — nothing to do.
            }
            user.setClient(target);
            user.setCreatedBy(null);
            log.info("Moved user {} ('{}') from client {} to client {} ('{}') as a primary user (previous parent: {})",
                    userId, user.getUsername(), oldClientId, targetClientId, target.getName(), currentParentId);
        }

        AppUser saved = appUserRepository.save(user);

        // If the move left the old client with no users, remove it (same housekeeping as
        // deleteUser). Skipped on a same-client re-parent, where the "old" client is the
        // destination and still has this user.
        //
        // Guarded on cloud accounts as well as users, for the reason {@link #deleteClient} spells
        // out: Client maps cloudAccounts with cascade=ALL + orphanRemoval, and Invoice and
        // Agreement hang off cloud_account_id as NOT NULL, so deleting a client that still owns
        // accounts would cascade straight through that billing history. Moving the last user out
        // must not become a back door around the guard deleteClient enforces. A client with
        // accounts but no users is a legitimate state the user manager already renders — it is the
        // row a first user gets added back to — so it is left in place and can only be removed
        // through the explicit, guarded delete.
        if (clientChanged && oldClientId != null && appUserRepository.countByClientId(oldClientId) == 0) {
            long remainingAccounts = cloudAccountRepository.countByClientId(oldClientId);
            if (remainingAccounts == 0) {
                clientRepository.deleteById(oldClientId);
                log.info("Removed now-empty client {} after moving its last user(s)", oldClientId);
            } else {
                log.info("Client {} has no users left after the move but still owns {} cloud account(s), "
                        + "so it was kept; delete it explicitly once its accounts are gone",
                        oldClientId, remainingAccounts);
            }
        }
        return saved;
    }

    @Transactional
    public void deleteUser(Long id) {
        AppUser user = appUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Client client = user.getClient();
        Long clientId = client != null ? client.getId() : null;

        // Every table below carries a foreign key back to app_user. PostgreSQL rejects the
        // user delete while any of them still references the row (constraint violation ->
        // DataIntegrityViolationException -> HTTP 500), so each reference must be released
        // first. This is the bug behind "Delete Failed / HTTP 500" when deleting a user who
        // had raised tickets: ticket.creator_user_id still pointed at the user.

        // 1. Soft references (nullable FKs) — detach so the history/content is preserved.
        //    ticket.creator_user_id and ticket_reply.user_id allow NULL, so we keep the
        //    tickets and replies but drop the link to the departing user. For tickets we first
        //    snapshot the creator's username into ticket.creator_username so the UI can still
        //    show who originally raised the ticket after this user is deleted.
        ticketRepository.snapshotAndDetachCreator(id);
        ticketReplyRepository.detachAuthor(id);

        //    app_user.created_by self-references the parent admin; detaching keeps any
        //    sub-users this user created (they simply become primary users).
        appUserRepository.detachChildrenCreatedBy(id);

        // 2. Hard references (NOT NULL FKs) — these rows cannot exist without the user,
        //    so they are removed outright.
        ticketRcaRepository.deleteByGeneratedByUserId(id);
        agreementRepository.deleteByUploadedByUserId(id);
        creditRequestRepository.deleteByUserId(id);

        // 3. Integration tables owned by xamops-service (shared DB, no JPA entity here).
        //    They FK to app_user, so they must be cleared or the delete fails.
        appUserRepository.deleteFinOpsReportSchedulesByUserId(id);
        appUserRepository.deleteGithubIntegrationConfigByUserId(id);
        appUserRepository.deleteJenkinsIntegrationConfigByUserId(id);
        appUserRepository.deleteSonarqubeProjectsByUserId(id);

        // 4. Per-user access / mapping / support rows that belong solely to this user.
        userTabAccessRepository.deleteByUserId(id);
        userCloudAccountAccessRepository.deleteByUserId(id);
        userPlatformConnectionRepository.deleteByUserId(id);
        supportMessageRepository.deleteByUserId(id);
        supportTicketStatusRepository.deleteByUserId(id);

        // Use direct JPQL delete to bypass Hibernate entity lifecycle nullability checks
        appUserRepository.deleteByIdDirect(id);

        // Check if any other users are still using this client
        if (clientId != null) {
            long usersWithClient = appUserRepository.findByClientId(clientId).size();
            if (usersWithClient == 0) {
                clientRepository.deleteById(clientId);
            }
        }
    }

    /**
     * Returns a BCrypt hash to store for a newly created account.
     *
     * <p>Accounts authenticate only via email + OTP, so no usable password is ever set on create.
     * The {@code app_user.password} column is non-null and Spring Security needs a non-null hash,
     * so when no password is supplied we encode a random UUID — a deliberately unusable secret that
     * nobody knows, mirroring the OAuth user creation in xamops-service's GoogleAuthController.
     */
    private String resolveCreatePasswordHash(String rawPassword) {
        String raw = (rawPassword == null || rawPassword.isBlank())
                ? UUID.randomUUID().toString()
                : rawPassword;
        return passwordEncoder.encode(raw);
    }

    private void sendWelcomeEmail(String email, String username) {
        String subject = "Welcome to XamOps - Your Account is Ready";

        String htmlBody = "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "<style>" +
                "body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }" +
                ".container { max-width: 600px; margin: 0 auto; padding: 20px; }" +
                ".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }"
                +
                ".content { background: #f9fafb; padding: 30px; border-radius: 0 0 10px 10px; }" +
                ".credentials-box { background: white; border-left: 4px solid #667eea; padding: 20px; margin: 20px 0; border-radius: 5px; }"
                +
                ".credential-item { margin: 15px 0; }" +
                ".label { font-weight: bold; color: #667eea; display: inline-block; width: 120px; }" +
                ".value { background: #f3f4f6; padding: 8px 12px; border-radius: 4px; font-family: monospace; display: inline-block; }"
                +
                ".button { display: inline-block; background: #667eea; color: white; padding: 12px 30px; text-decoration: none; border-radius: 5px; margin: 20px 0; }"
                +
                ".footer { text-align: center; padding: 20px; color: #6b7280; font-size: 12px; }" +
                ".warning { background: #fef3c7; border-left: 4px solid #f59e0b; padding: 15px; margin: 20px 0; border-radius: 5px; }"
                +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div class='container'>" +
                "<div class='header'>" +
                "<h1 style='margin: 0;'>Welcome to XamOps!</h1>" +
                "<p style='margin: 10px 0 0 0; opacity: 0.9;'>Your Cloud Management Platform</p>" +
                "</div>" +
                "<div class='content'>" +
                "<p>Hello,</p>" +
                "<p>Your XamOps account has been created by the administrator. " +
                "To access the platform, sign in with your email below — we'll email you a one-time passcode (OTP) each time you log in. No password is required.</p>" +
                "<div class='credentials-box'>" +
                "<h3 style='margin-top: 0; color: #667eea;'>Your Login Details</h3>" +
                "<div class='credential-item'>" +
                "<span class='label'>Email (Login):</span>" +
                "<span class='value'>" + email + "</span>" +
                "</div>" +
                "<div class='credential-item'>" +
                "<span class='label'>Portal URL:</span>" +
                "<span class='value'>https://live.xamops.com/</span>" +
                "</div>" +
                "</div>" +
                "<div class='warning'>" +
                "<strong>🔐 How to sign in:</strong> Enter your email on the login page and we'll send a one-time passcode to that address. Enter the passcode to complete sign-in."
                +
                "</div>" +
                "<div style='text-align: center;'>" +
                "<a href='https://live.xamops.com/' class='button'>Login to XamOps</a>" +
                "</div>" +
                "<p>If you have any questions or need assistance, please contact your administrator.</p>" +
                "<p>Best regards,<br><strong>The XamOps Team</strong></p>" +
                "</div>" +
                "<div class='footer'>" +
                "<p>This is an automated message. Please do not reply to this email.</p>" +
                "<p>&copy; 2025 XamOps. All rights reserved.</p>" +
                "</div>" +
                "</div>" +
                "</body>" +
                "</html>";

        emailService.sendHtmlEmail(email, subject, htmlBody);
    }
}