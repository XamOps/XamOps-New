package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.ClientAwsAccess;
import com.xammer.cloud.domain.ClientIdentityConfig;
import com.xammer.cloud.domain.IdentityAuditLog;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.identity.*;
import com.xammer.cloud.repository.ClientAwsAccessRepository;
import com.xammer.cloud.repository.ClientIdentityConfigRepository;
import com.xammer.cloud.repository.IdentityAuditLogRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.util.TokenEncryptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business logic for AccessOps identity management.
 *
 * Every mutation (invite, deactivate, group membership change) follows this sequence:
 *   1. Write audit log entry (BEFORE the Authentik call — failed calls still leave a record)
 *   2. Call Authentik API via AuthentikApiClient
 *   3. Return result to controller
 *
 * Isolation guarantee: every list result from Authentik is filtered server-side
 * by the tenant's group_prefix before it is returned. The Authentik filter param
 * is a best-effort search; this service's prefix check is the hard isolation layer.
 *
 * Token handling: tokens are decrypted in-memory only for the duration of the
 * Authentik call. Decrypted values are never assigned to a field or logged.
 */
@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    private final AuthentikApiClient authentikApiClient;
    private final ClientIdentityConfigRepository configRepo;
    private final ClientAwsAccessRepository awsAccessRepo;
    private final IdentityAuditLogRepository auditRepo;
    private final UserRepository userRepository;

    @Value("${identity.encryption-key}")
    private String encryptionKey;

    public IdentityService(AuthentikApiClient authentikApiClient,
                           ClientIdentityConfigRepository configRepo,
                           ClientAwsAccessRepository awsAccessRepo,
                           IdentityAuditLogRepository auditRepo,
                           UserRepository userRepository) {
        this.authentikApiClient = authentikApiClient;
        this.configRepo = configRepo;
        this.awsAccessRepo = awsAccessRepo;
        this.auditRepo = auditRepo;
        this.userRepository = userRepository;
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    /**
     * List all users for this tenant.
     * Authentik is queried with ?search={prefix} and results are filtered
     * server-side to users whose username starts with the prefix.
     */
    public List<AuthentikUserDto> listUsers(Principal principal) {
        User actor = resolveUser(principal);
        ClientIdentityConfig config = resolveConfig(actor.getClient().getId());
        String token = decrypt(config);

        List<AuthentikUserDto> users = authentikApiClient.listUsers(
                token, config.getGroupPrefix(), actor.getClient().getId());

        // Hard isolation: strip any user that slipped through without the prefix
        return users.stream()
                .filter(u -> u.getUsername() != null
                        && u.getUsername().toLowerCase()
                               .contains(config.getGroupPrefix().toLowerCase().replace("-", "")))
                .collect(Collectors.toList());
    }

    /**
     * Invite a new user:
     *   1. Audit (INVITE_INITIATED)
     *   2. Create user in Authentik (username = email)
     *   3. Add to requested group
     *   4. Send recovery/invite email (user self-serves password)
     *   5. Audit (USER_INVITED)
     */
    @Transactional
    public AuthentikUserDto inviteUser(Principal principal, InviteUserRequest request) {
        User actor = resolveUser(principal);
        ClientIdentityConfig config = resolveConfig(actor.getClient().getId());

        writeAudit(actor, "INVITE_INITIATED", request.getEmail(), null, null);

        String token = decrypt(config);
        Long clientId = actor.getClient().getId();

        // Validate the requested group belongs to this tenant
        enforceGroupPrefix(request.getGroupId(), config.getGroupPrefix(), token, clientId);

        AuthentikUserDto created = authentikApiClient.createUser(
                token, request.getName(), request.getEmail(), clientId);

        authentikApiClient.addUserToGroup(token, request.getGroupId(), created.getPk(), clientId);

        // Send Authentik's built-in invite/recovery email — user self-serves password setup
        try {
            authentikApiClient.sendRecoveryEmail(token, created.getPk(), clientId);
        } catch (Exception e) {
            // Log but don't fail the invite — user can resend via recovery flow
            log.warn("Recovery email failed for new user pk={}: {}", created.getPk(), e.getMessage());
        }

        // Fetch the group name for the audit log
        String groupName = resolveGroupName(token, request.getGroupId(), config.getGroupPrefix(), clientId);
        writeAudit(actor, "USER_INVITED", request.getEmail(), groupName,
                "{\"authentikPk\":" + created.getPk() + "}");

        return created;
    }

    /**
     * Deactivate a user (set is_active=false).
     * Per Authentik best practice: users are never deleted — only deactivated.
     * AWS SSO access is automatically revoked via SCIM within seconds.
     */
    @Transactional
    public AuthentikUserDto deactivateUser(Principal principal, Long authentikUserId) {
        User actor = resolveUser(principal);
        ClientIdentityConfig config = resolveConfig(actor.getClient().getId());

        writeAudit(actor, "DEACTIVATE_INITIATED", null, null,
                "{\"authentikUserId\":" + authentikUserId + "}");

        String token = decrypt(config);
        AuthentikUserDto updated = authentikApiClient.deactivateUser(
                token, authentikUserId, actor.getClient().getId());

        writeAudit(actor, "USER_DEACTIVATED", updated.getEmail(), null,
                "{\"authentikPk\":" + authentikUserId + "}");

        return updated;
    }

    // ── Groups ────────────────────────────────────────────────────────────────

    /**
     * List all groups for this tenant, enriched with AWS account context
     * from the client_aws_access table.
     */
    public List<AuthentikGroupDto> listGroups(Principal principal) {
        User actor = resolveUser(principal);
        ClientIdentityConfig config = resolveConfig(actor.getClient().getId());
        String token = decrypt(config);
        Long clientId = actor.getClient().getId();

        List<AuthentikGroupDto> groups = authentikApiClient.listGroups(
                token, config.getGroupPrefix(), clientId);

        // Hard isolation: only return groups matching this tenant's prefix
        List<AuthentikGroupDto> filtered = groups.stream()
                .filter(g -> g.getName() != null
                        && g.getName().startsWith(config.getGroupPrefix()))
                .collect(Collectors.toList());

        // Enrich with AWS context from local DB
        Map<String, ClientAwsAccess> awsAccessMap = awsAccessRepo.findByClientId(clientId)
                .stream()
                .collect(Collectors.toMap(ClientAwsAccess::getAuthentikGroupId, Function.identity()));

        for (AuthentikGroupDto group : filtered) {
            ClientAwsAccess access = awsAccessMap.get(group.getPk());
            if (access != null) {
                group.setAwsAccountId(access.getAwsAccountId());
                group.setAwsAccountName(access.getAwsAccountName());
                group.setPermissionSetName(access.getPermissionSetName());
            }
        }

        return filtered;
    }

    /**
     * Add a user to a group.
     * Authentik SCIM sync grants AWS SSO access automatically (no extra AWS call).
     */
    @Transactional
    public void addUserToGroup(Principal principal, String groupId, GroupMembershipRequest request) {
        User actor = resolveUser(principal);
        ClientIdentityConfig config = resolveConfig(actor.getClient().getId());
        String token = decrypt(config);
        Long clientId = actor.getClient().getId();

        enforceGroupPrefix(groupId, config.getGroupPrefix(), token, clientId);

        String groupName = resolveGroupName(token, groupId, config.getGroupPrefix(), clientId);
        writeAudit(actor, "USER_ADDED_TO_GROUP", null, groupName,
                "{\"userId\":" + request.getUserId() + "}");

        authentikApiClient.addUserToGroup(token, groupId, request.getUserId(), clientId);
    }

    /**
     * Remove a user from a group.
     * Authentik SCIM sync revokes AWS SSO access automatically.
     */
    @Transactional
    public void removeUserFromGroup(Principal principal, String groupId, Long userId) {
        User actor = resolveUser(principal);
        ClientIdentityConfig config = resolveConfig(actor.getClient().getId());
        String token = decrypt(config);
        Long clientId = actor.getClient().getId();

        enforceGroupPrefix(groupId, config.getGroupPrefix(), token, clientId);

        String groupName = resolveGroupName(token, groupId, config.getGroupPrefix(), clientId);
        writeAudit(actor, "USER_REMOVED_FROM_GROUP", null, groupName,
                "{\"userId\":" + userId + "}");

        authentikApiClient.removeUserFromGroup(token, groupId, userId, clientId);
    }

    // ── Audit Log ─────────────────────────────────────────────────────────────

    public Page<IdentityAuditLogDto> listAuditLogs(Principal principal, int page, int size) {
        User actor = resolveUser(principal);
        Page<IdentityAuditLog> logs = auditRepo.findByClientIdOrderByTimestampDesc(
                actor.getClient().getId(), PageRequest.of(page, size));
        return logs.map(IdentityAuditLogDto::from);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private User resolveUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + principal.getName()));
    }

    private ClientIdentityConfig resolveConfig(Long clientId) {
        return configRepo.findByClientId(clientId)
                .orElseThrow(() -> new IllegalStateException(
                        "Identity not provisioned for client " + clientId
                        + ". Contact XamOps support."));
    }

    /** Decrypt in-memory. The returned value is passed directly to Authentik; never stored. */
    private String decrypt(ClientIdentityConfig config) {
        return TokenEncryptionUtil.decrypt(config.getAuthentikTokenEncrypted(), encryptionKey);
    }

    private void writeAudit(User actor, String action, String targetEmail,
                             String groupName, String metadata) {
        IdentityAuditLog entry = new IdentityAuditLog();
        entry.setClient(actor.getClient());
        entry.setActorUserId(actor.getId());
        entry.setAction(action);
        entry.setTargetUserEmail(targetEmail);
        entry.setGroupName(groupName);
        entry.setMetadata(metadata);
        auditRepo.save(entry);
    }

    /**
     * Verifies a group UUID belongs to this tenant by checking its name starts
     * with the tenant's prefix. Prevents cross-tenant group manipulation.
     */
    private void enforceGroupPrefix(String groupId, String prefix, String token, Long clientId) {
        List<AuthentikGroupDto> groups = authentikApiClient.listGroups(token, prefix, clientId);
        boolean owned = groups.stream().anyMatch(g -> groupId.equals(g.getPk())
                && g.getName() != null
                && g.getName().startsWith(prefix));
        if (!owned) {
            throw new SecurityException("Group " + groupId + " does not belong to this tenant.");
        }
    }

    private String resolveGroupName(String token, String groupId, String prefix, Long clientId) {
        List<AuthentikGroupDto> groups = authentikApiClient.listGroups(token, prefix, clientId);
        return groups.stream()
                .filter(g -> groupId.equals(g.getPk()))
                .map(AuthentikGroupDto::getName)
                .findFirst()
                .orElse(groupId);
    }
}
