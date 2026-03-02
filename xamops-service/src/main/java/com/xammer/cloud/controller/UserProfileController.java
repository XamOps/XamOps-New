package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.dto.GlobalUserDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.UserProfileDto;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.MasterDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/xamops/user")
public class UserProfileController {

    private static final Logger logger = LoggerFactory.getLogger(UserProfileController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MasterDatabaseService masterDatabaseService;

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

        // Resolve tenant context if missing — same fallback as AccountManagerController
        String currentTenant = TenantContext.getCurrentTenant();
        if (currentTenant == null || "default".equals(currentTenant)) {
            try {
                logger.warn("Tenant context is '{}' for user '{}'. Attempting master DB resolution.", currentTenant, username);
                Optional<GlobalUserDto> globalUser = masterDatabaseService.findGlobalUser(username);
                if (globalUser.isPresent()) {
                    TenantContext.setCurrentTenant(globalUser.get().getTenantId());
                    logger.info("Resolved tenant '{}' for user '{}'.", globalUser.get().getTenantId(), username);
                }
            } catch (Exception e) {
                logger.error("Tenant resolution failed for user '{}': {}", username, e.getMessage());
            }
        }

        // Always include tenantId in response so the frontend can store it in sessionStorage
        // and send X-Tenant-ID on all subsequent requests (multi-tenant header routing).
        String tenantId = TenantContext.getCurrentTenant();

        boolean isTourSeen = true; // Safe default if DB is unavailable
        Long userId = null;
        tenantId = null;

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

        UserProfileDto userProfile = new UserProfileDto(username, roles, isTourSeen);
        if (userId != null) {
            userProfile.setId(userId);
        }
        if (tenantId != null) {
            userProfile.setTenantId(tenantId);
        }

        return ResponseEntity.ok(userProfile);
    }

    @PostMapping("/tour-seen")
    public ResponseEntity<Void> markTourAsSeen() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication.getPrincipal();

        if (principal instanceof ClientUserDetails) {
            Long userId = ((ClientUserDetails) principal).getId();
            try {
                User user = userRepository.findById(userId).orElse(null);
                if (user != null) {
                    user.setTourSeen(true);
                    userRepository.save(user);
                    return ResponseEntity.ok().build();
                }
            } catch (Exception e) {
                logger.error("Could not mark tour as seen for userId={}: {}", userId, e.getMessage());
                return ResponseEntity.status(500).build();
            }
        }
        return ResponseEntity.notFound().build();
    }
}