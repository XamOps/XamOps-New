package com.xammer.cloud.security;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;

@Service
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.info("========== LOADING USER BY USERNAME ==========");
        log.info("Username: {}", username);
        
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.error("✗ User not found: {}", username);
                    return new UsernameNotFoundException("User not found: " + username);
                });
        
        log.info("✓ User found in database");
        log.info("  - User ID: {}", user.getId());
        
        // ✅ FIX: Access client ID through the relationship
        Long clientId = user.getClient() != null ? user.getClient().getId() : null;
        
        if (clientId == null) {
            log.error("✗ User has no associated client!");
            throw new UsernameNotFoundException("User has no associated client");
        }
        
        log.info("  - Client ID: {}", clientId);
        log.info("  - Role: {}", user.getRole());
        
        // Create authorities from role
        Collection<GrantedAuthority> authorities = Collections.singletonList(
            new SimpleGrantedAuthority(user.getRole())
        );
        
        // CRITICAL: Return ClientUserDetails with correct clientId
        ClientUserDetails userDetails = new ClientUserDetails(
            user.getUsername(),
            user.getPassword(),
            authorities,
            clientId,        // ✅ Now correctly gets client ID from relationship
            user.getId()
        );
        
        log.info("✓ Created ClientUserDetails successfully");
        log.info("  - Type: {}", userDetails.getClass().getName());
        log.info("  - Username: {}", userDetails.getUsername());
        log.info("  - Client ID: {}", userDetails.getClientId());
        log.info("  - User ID: {}", userDetails.getId());
        
        return userDetails;
    }
}


