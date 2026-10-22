package com.xammer.billops.controller;

import com.xammer.billops.dto.AdminClientDTO;
import com.xammer.billops.dto.ClientCloudAccountDTO;
import com.xammer.billops.dto.UserDTO;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.service.AdminAccessScope;
import com.xammer.billops.service.AppUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/billops/users")
public class UserManagementController {

    private static final Logger log = LoggerFactory.getLogger(UserManagementController.class);

    @Autowired
    private AppUserService service;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private AdminAccessScope adminAccessScope;

    @GetMapping
    public List<UserDTO> getAllUsers() {
        return service.getAllUsers();
    }

    /**
     * Every client the caller may administer, with its user and cloud-account counts.
     *
     * <p>The User Manager groups the user list by client name to build its client rows, which
     * means a client with nobody in it never appears: it cannot be given its first user and has
     * no row to carry a delete action. This endpoint is what lets the page list those clients too.
     */
    @GetMapping("/clients")
    public List<AdminClientDTO> getAllClients() {
        return service.getAdminClients();
    }

    /**
     * Deletes an empty client — no users, no cloud accounts.
     *
     * <p>Answers 409 with both counts when the client still has either, because deleting a
     * populated client would cascade through {@code Client.users} / {@code Client.cloudAccounts}
     * and bypass the per-user reference cleanup in {@code AppUserService.deleteUser}. Delete the
     * users first (the rows above this one), then the client.
     *
     * <p>Restricted to full BillOps / super admins, matching the equivalent endpoint in
     * xamops-service: sub-admins can see the client list but not remove entries from it.
     */
    @DeleteMapping("/clients/{clientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> deleteClient(@PathVariable Long clientId) {
        try {
            service.deleteClient(clientId);
            return ResponseEntity.ok(Map.of("clientId", clientId, "deleted", true));
        } catch (AppUserService.ClientNotEmptyException e) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("clientId", clientId);
            conflict.put("userCount", e.getUserCount());
            conflict.put("accountCount", e.getCloudAccountCount());
            conflict.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            // Most likely an FK from a satellite table that survived an empty client — report it
            // rather than letting it surface as an opaque 500.
            log.error("Failed to delete client {}", clientId, e);
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Could not delete the client: " + e.getMessage()));
        }
    }

    /**
     * Lightweight list of a client's cloud accounts, used by the user-manager modal to render the
     * per-user cloud-account access picker. {@code accountId} is the provider-specific identifier
     * (first non-null of AWS / GCP / Azure id), matching what ClientDetailsPage already shows.
     */
    @GetMapping("/clients/{clientId}/cloud-accounts")
    public List<ClientCloudAccountDTO> getClientCloudAccounts(@PathVariable Long clientId) {
        return cloudAccountRepository.findByClientId(clientId).stream()
                .map(UserManagementController::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Every cloud account in the platform, for the BillOps admin account-access picker.
     *
     * <p>A BillOps admin works across customers, so the accounts they can be granted are not
     * limited to their own client the way a customer user's are — hence a separate, unkeyed list
     * rather than the per-client endpoint above. Each row carries its owning client, because
     * account names repeat across customers.
     *
     * <p>Scoped by {@link AdminAccessScope}: an admin who has themselves been restricted to a
     * subset of accounts can only grant from that subset, so access can be delegated downwards
     * but never widened.
     */
    @GetMapping("/cloud-accounts")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB', 'ROLE_SUPER_ADMIN')")
    public List<ClientCloudAccountDTO> getAllCloudAccounts() {
        List<CloudAccount> accounts = adminAccessScope.filterAccounts(
                cloudAccountRepository.findAll(), adminAccessScope.accountIdsForCurrentUser());

        return accounts.stream()
                .map(UserManagementController::toDto)
                .sorted(java.util.Comparator
                        .comparing(ClientCloudAccountDTO::getClientName,
                                java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(ClientCloudAccountDTO::getName,
                                java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
    }

    private static ClientCloudAccountDTO toDto(CloudAccount ca) {
        return new ClientCloudAccountDTO(
                ca.getId(),
                ca.getAccountName(),
                ca.getProvider(),
                deriveAccountId(ca),
                ca.getClient() != null ? ca.getClient().getId() : null,
                ca.getClient() != null ? ca.getClient().getName() : null);
    }

    private static String deriveAccountId(CloudAccount ca) {
        if (ca.getAwsAccountId() != null) return ca.getAwsAccountId();
        if (ca.getGcpProjectId() != null) return ca.getGcpProjectId();
        return ca.getAzureSubscriptionId();
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody UserDTO dto) {
        try {
            AppUser created = service.createUser(dto);

            UserDTO result = new UserDTO();
            result.setId(created.getId());
            result.setUsername(created.getUsername());
            result.setEmail(created.getEmail());
            result.setRole(created.getRole());
            result.setClientId(created.getClient() != null ? created.getClient().getId() : null);
            result.setClientName(created.getClient() != null ? created.getClient().getName() : "Unknown");
            result.setContact(created.getContact());
            result.setDesignation(created.getDesignation());
            result.setAllowedTabs(dto.getAllowedTabs());
            result.setAccountAccessIds(dto.getAccountAccessIds());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody UserDTO dto) {
        try {
            dto.setId(id);
            AppUser updated = service.updateUser(dto);

            UserDTO result = new UserDTO();
            result.setId(updated.getId());
            result.setUsername(updated.getUsername());
            result.setEmail(updated.getEmail());
            result.setRole(updated.getRole());
            result.setClientId(updated.getClient() != null ? updated.getClient().getId() : null);
            result.setClientName(updated.getClient() != null ? updated.getClient().getName() : "Unknown");
            result.setContact(updated.getContact());
            result.setDesignation(updated.getDesignation());
            result.setAllowedTabs(dto.getAllowedTabs());
            result.setAccountAccessIds(dto.getAccountAccessIds());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        service.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    /** Request body for moving a user to a different client. */
    public static class MoveUserRequest {
        private Long clientId;
        /**
         * Optional primary user of {@code clientId} to nest the moved user under. {@code null}
         * places them as a primary user (and detaches them if they were a sub-user).
         */
        private Long parentUserId;
        public Long getClientId() { return clientId; }
        public void setClientId(Long clientId) { this.clientId = clientId; }
        public Long getParentUserId() { return parentUserId; }
        public void setParentUserId(Long parentUserId) { this.parentUserId = parentUserId; }
    }

    /**
     * Moves a user to an existing client and places them in that client's user tree — as a primary
     * user, or as a sub-user of an existing primary user when {@code parentUserId} is given. Only
     * the client association and the parent pointer change; the user's tickets, credits,
     * agreements and access all stay with the user. Passing the client the user already belongs to
     * is how an individual user is re-parented inside their own client.
     */
    @PatchMapping("/{id}/client")
    public ResponseEntity<?> moveUserToClient(@PathVariable Long id, @RequestBody MoveUserRequest request) {
        if (request == null || request.getClientId() == null) {
            return ResponseEntity.badRequest().body("Target clientId is required.");
        }
        try {
            AppUser moved = service.moveUserToClient(id, request.getClientId(), request.getParentUserId());

            UserDTO result = new UserDTO();
            result.setId(moved.getId());
            result.setUsername(moved.getUsername());
            result.setEmail(moved.getEmail());
            result.setRole(moved.getRole());
            result.setClientId(moved.getClient() != null ? moved.getClient().getId() : null);
            result.setClientName(moved.getClient() != null ? moved.getClient().getName() : "Unknown");
            result.setContact(moved.getContact());
            result.setDesignation(moved.getDesignation());
            result.setParentId(moved.getCreatedBy());  // null = primary user, set = sub-user of that id
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
