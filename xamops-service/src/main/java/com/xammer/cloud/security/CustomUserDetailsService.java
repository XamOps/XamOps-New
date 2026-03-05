package com.xammer.cloud.security;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

/**
 * Single-database authentication for all environments (local, UAT, prod, preprod).
 * Each environment connects to its own DB via application-{profile}.properties.
 */
@Service
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        log.info("========== LOGIN ATTEMPT ==========");
        log.info("Input: {}", usernameOrEmail);

        // Clear any existing tenant context
        TenantContext.clear();

        // Try username first, then fall back to email
        Optional<User> userOpt = userRepository.findByUsername(usernameOrEmail);
        if (userOpt.isEmpty()) {
            log.info("Username not found, trying email lookup...");
            userOpt = userRepository.findByEmail(usernameOrEmail);
        }

        User user = userOpt.orElseThrow(() -> {
            log.error("✗ User not found by username or email: {}", usernameOrEmail);
            return new UsernameNotFoundException("User not found: " + usernameOrEmail);
        });

        log.info("✓ User loaded: {} (role: {})", user.getUsername(), user.getRole());

        Long clientId = user.getClient() != null ? user.getClient().getId() : null;
        Collection<GrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority(user.getRole()));

        return new ClientUserDetails(
                user.getUsername(),
                user.getPassword(),
                authorities,
                clientId,
                user.getId(),
                "default");
    }
}
