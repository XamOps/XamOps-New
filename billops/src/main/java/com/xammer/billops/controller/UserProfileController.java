package com.xammer.billops.controller;

import com.xammer.billops.dto.UserProfileDto;
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
@RequestMapping("/api/xamops")
public class UserProfileController {

    @GetMapping("/profile")
    public ResponseEntity<UserProfileDto> getUserProfile() {
        // Get the current authentication object from the security context.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            // If no user is authenticated, return an unauthorized status.
            return ResponseEntity.status(401).build();
        }

        // Get the username from the authentication principal.
        String username = authentication.getName();

        // Get the roles (authorities) and convert them to a list of strings.
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        // Create the UserProfileDto with the actual user's data.
        UserProfileDto userProfile = new UserProfileDto(username, roles);
        
        // Return the real user profile.
        return ResponseEntity.ok(userProfile);
    }
}