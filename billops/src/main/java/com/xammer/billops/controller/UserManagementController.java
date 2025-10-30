package com.xammer.billops.controller;

import com.xammer.billops.dto.UserDTO;
import com.xammer.billops.domain.AppUser;
import com.xammer.billops.service.AppUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/billops/users")
public class UserManagementController {

    @Autowired
    private AppUserService service;

    @GetMapping
    public List<UserDTO> getAllUsers() {
        return service.getAllUsers();
    }

    @PostMapping
    public UserDTO createUser(@RequestBody UserDTO dto) {
        AppUser created = service.createUser(dto);
        
        UserDTO result = new UserDTO();
        result.setId(created.getId());
        result.setUsername(created.getUsername());
        result.setEmail(created.getEmail());
        result.setRole(created.getRole());
        result.setClientId(created.getClient() != null ? created.getClient().getId() : null);
        result.setClientName(created.getClient() != null ? created.getClient().getName() : "Unknown");
        
        return result;
    }

    @PutMapping("/{id}")
    public UserDTO updateUser(@PathVariable Long id, @RequestBody UserDTO dto) {
        dto.setId(id);
        AppUser updated = service.updateUser(dto);

        UserDTO result = new UserDTO();
        result.setId(updated.getId());
        result.setUsername(updated.getUsername());
        result.setEmail(updated.getEmail());
        result.setRole(updated.getRole());
        result.setClientId(updated.getClient() != null ? updated.getClient().getId() : null);
        result.setClientName(updated.getClient() != null ? updated.getClient().getName() : "Unknown");

        return result;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        service.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
