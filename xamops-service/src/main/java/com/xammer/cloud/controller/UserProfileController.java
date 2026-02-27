package com.xammer.cloud.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.UserProfileDto;
import com.xammer.cloud.repository.UserRepository; // --- ✅ IMPORTED ---
import org.springframework.beans.factory.annotation.Autowired; // --- ✅ IMPORTED ---
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping; // --- ✅ IMPORTED ---
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/xamops/user")
public class UserProfileController {

    // --- ✅ INJECT REPOSITORY ---
    @Autowired
    private UserRepository userRepository;

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

        // --- ✅ FETCH DB STATUS ---
        boolean isTourSeen = true; // Default safe fallback
        Long userId = null;
        String tenantId = null;

        Object principal = authentication.getPrincipal();
        if (principal instanceof ClientUserDetails) {
            userId = ((ClientUserDetails) principal).getId();
            tenantId = ((ClientUserDetails) principal).getTenantId();

            // Fetch real status from DB
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                isTourSeen = user.isTourSeen();
            }
        }

        // --- ✅ PASS 3 ARGUMENTS TO CONSTRUCTOR ---
        UserProfileDto userProfile = new UserProfileDto(username, roles, isTourSeen);

        if (userId != null) {
            userProfile.setId(userId);
        }
        if (tenantId != null) {
            userProfile.setTenantId(tenantId);
        }

        return ResponseEntity.ok(userProfile);
    }

    // --- ✅ ADDED ENDPOINT TO UPDATE STATUS ---
    @PostMapping("/tour-seen")
    public ResponseEntity<Void> markTourAsSeen() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication.getPrincipal();

        if (principal instanceof ClientUserDetails) {
            Long userId = ((ClientUserDetails) principal).getId();
            User user = userRepository.findById(userId).orElse(null);
            
            if (user != null) {
                user.setTourSeen(true); // Set to true
                userRepository.save(user); // Save to DB
                return ResponseEntity.ok().build();
            }
        }
        return ResponseEntity.notFound().build();
    }
}