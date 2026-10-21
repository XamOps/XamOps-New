package com.xammer.billops.controller;

import com.xammer.billops.dto.ClientCloudAccountDTO;
import com.xammer.billops.dto.UserDTO;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.service.AppUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/billops/users")
public class UserManagementController {

    @Autowired
    private AppUserService service;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @GetMapping
    public List<UserDTO> getAllUsers() {
        return service.getAllUsers();
    }

    /**
     * Lightweight list of a client's cloud accounts, used by the user-manager modal to render the
     * per-user cloud-account access picker. {@code accountId} is the provider-specific identifier
     * (first non-null of AWS / GCP / Azure id), matching what ClientDetailsPage already shows.
     */
    @GetMapping("/clients/{clientId}/cloud-accounts")
    public List<ClientCloudAccountDTO> getClientCloudAccounts(@PathVariable Long clientId) {
        return cloudAccountRepository.findByClientId(clientId).stream()
                .map(ca -> new ClientCloudAccountDTO(
                        ca.getId(),
                        ca.getAccountName(),
                        ca.getProvider(),
                        deriveAccountId(ca)))
                .collect(Collectors.toList());
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
        public Long getClientId() { return clientId; }
        public void setClientId(Long clientId) { this.clientId = clientId; }
    }

    /**
     * Moves a user to a different (existing) client. Only the client association changes; the
     * user's tickets, credits, agreements and access all stay with the user.
     */
    @PatchMapping("/{id}/client")
    public ResponseEntity<?> moveUserToClient(@PathVariable Long id, @RequestBody MoveUserRequest request) {
        if (request == null || request.getClientId() == null) {
            return ResponseEntity.badRequest().body("Target clientId is required.");
        }
        try {
            AppUser moved = service.moveUserToClient(id, request.getClientId());

            UserDTO result = new UserDTO();
            result.setId(moved.getId());
            result.setUsername(moved.getUsername());
            result.setEmail(moved.getEmail());
            result.setRole(moved.getRole());
            result.setClientId(moved.getClient() != null ? moved.getClient().getId() : null);
            result.setClientName(moved.getClient() != null ? moved.getClient().getName() : "Unknown");
            result.setContact(moved.getContact());
            result.setDesignation(moved.getDesignation());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
