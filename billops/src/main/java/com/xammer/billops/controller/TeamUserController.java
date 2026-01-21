package com.xammer.billops.controller;

import com.xammer.billops.dto.ChangePasswordDTO;
import com.xammer.billops.dto.ProfileDTO;
import com.xammer.billops.dto.TeamUserDTO;
import com.xammer.billops.service.TeamUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/billops/team")
@PreAuthorize("hasAuthority('ROLE_BILLOPS')")
public class TeamUserController {

    @Autowired
    private TeamUserService teamUserService;

    @GetMapping("/profile")
    public ResponseEntity<ProfileDTO> getMyProfile() {
        ProfileDTO profile = teamUserService.getMyProfile();
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/profile/password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordDTO dto) {
        try {
            teamUserService.changePassword(dto.getOldPassword(), dto.getNewPassword());
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/users")
    public ResponseEntity<List<TeamUserDTO>> getMyTeamUsers() {
        try {
            List<TeamUserDTO> users = teamUserService.getMyTeamUsers();
            return ResponseEntity.ok(users);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    @PostMapping("/users")
    public ResponseEntity<?> createTeamUser(@RequestBody TeamUserDTO dto) {
        try {
            TeamUserDTO created = teamUserService.createTeamUser(dto);
            return ResponseEntity.ok(created);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateTeamUser(@PathVariable Long id, @RequestBody TeamUserDTO dto) {
        try {
            TeamUserDTO updated = teamUserService.updateTeamUser(id, dto);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteTeamUser(@PathVariable Long id) {
        try {
            teamUserService.deleteTeamUser(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
