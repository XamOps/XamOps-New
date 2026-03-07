package com.xammer.billops.security;

import com.xammer.billops.repository.UserRepository;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.security.ClientUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Service
public class CustomUserDetailsService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.info("========== LOGIN ATTEMPT: {} ==========", username);

        // Try username first, fall back to email — same for all environments
        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            found = userRepository.findByEmail(username);
        }

        User user = found.orElseThrow(() -> {
            log.error("✗ User not found: {}", username);
            return new UsernameNotFoundException("User not found: " + username);
        });

        log.info("✓ User loaded: {} (role: {})", user.getUsername(), user.getRole());
        return buildUserDetails(user);
    }

    private UserDetails buildUserDetails(User user) {
        Long clientId = user.getClient() != null ? user.getClient().getId() : null;
        Collection<GrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority(user.getRole()));

        return new ClientUserDetails(
                user.getUsername(),
                user.getPassword(),
                authorities,
                clientId,
                user.getId());
    }
}