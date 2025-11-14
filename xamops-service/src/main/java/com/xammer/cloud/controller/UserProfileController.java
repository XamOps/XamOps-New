package com.xammer.cloud.controller;
import com.xammer.cloud.security.ClientUserDetails;

import com.xammer.cloud.domain.User; // --- IMPORT User ---
import com.xammer.cloud.dto.UserProfileDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/xamops/user") // --- FIX: Changed path to /api/xamops/user ---
public class UserProfileController {

@GetMapping("/profile")
    public ResponseEntity<UserProfileDto> getUserProfile() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(401).build();
        }

        String username = authentication.getName();
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        UserProfileDto userProfile = new UserProfileDto(username, roles);

        // ✅ THIS IS THE FIX:
        // Change the check from 'User' to 'ClientUserDetails'
        Object principal = authentication.getPrincipal();
        if (principal instanceof ClientUserDetails) {
            // Get the ID from the ClientUserDetails object
            userProfile.setId(((ClientUserDetails) principal).getId());
        }
        // --- END OF FIX ---
        
        return ResponseEntity.ok(userProfile);
    }
}