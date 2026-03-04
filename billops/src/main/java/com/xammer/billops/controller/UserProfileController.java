package com.xammer.billops.controller;

import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.UserTabAccessRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.billops.dto.UserProfileDto;
import org.springframework.beans.factory.annotation.Autowired;
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
@RequestMapping("/api/billops")
public class UserProfileController {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private UserTabAccessRepository userTabAccessRepository;

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

        Object principal = authentication.getPrincipal();
        if (principal instanceof ClientUserDetails) {
            Long userId = ((ClientUserDetails) principal).getId();
            userProfile.setId(userId);

            appUserRepository.findById(userId).ifPresent(user -> {
                boolean isPrimary = user.getCreatedBy() == null;
                userProfile.setPrimaryUser(isPrimary);

                // For ROLE_BILLOPS_ADMIN sub-users, return their allowed tabs
                if (!isPrimary && roles.contains("ROLE_BILLOPS_ADMIN")) {
                    List<String> tabs = userTabAccessRepository.findTabNamesByUserId(userId);
                    userProfile.setAllowedTabs(tabs);
                }
            });
        }

        return ResponseEntity.ok(userProfile);
    }
}