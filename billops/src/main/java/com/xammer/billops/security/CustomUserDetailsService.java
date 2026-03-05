package com.xammer.billops.security;

import com.xammer.billops.config.multitenancy.TenantContext;
import com.xammer.billops.dto.GlobalUserDto;
import com.xammer.billops.repository.UserRepository;
import com.xammer.billops.service.MasterDatabaseService;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.security.ClientUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
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
 * Profile-Aware User Authentication Service for Billops
 * 
 * LOCAL/UAT MODE: Authenticates directly against users table in single database
 * PRODUCTION MODE: Uses global_users for tenant routing, then loads from
 * tenant's users table
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CustomUserDetailsService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    @Autowired
    private Environment environment;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.info("========== CENTRALIZED LOGIN ATTEMPT ==========");
        log.info("User: {}", username);

        // Check active profile
        String[] activeProfiles = environment.getActiveProfiles();
        boolean isProduction = java.util.Arrays.asList(activeProfiles).contains("prod")
                || java.util.Arrays.asList(activeProfiles).contains("preprod");

        if (isProduction) {
            // PRODUCTION MODE: Use global_users table for multi-tenant routing
            return loadUserInProductionMode(username);
        } else {
            // LOCAL/UAT MODE: Direct authentication from users table (single DB)
            return loadUserInSingleDatabaseMode(username);
        }
    }

    /**
     * Resolve a User by username OR email (email login support).
     */
    private Optional<User> findByUsernameOrEmail(String input) {
        Optional<User> found = userRepository.findByUsername(input);
        if (found.isEmpty()) {
            found = userRepository.findByEmail(input);
        }
        return found;
    }

    /**
     * PRODUCTION MODE: Multi-tenant authentication
     * 1. Query global_users to find tenant (by username or email)
     * 2. Switch to tenant database
     * 3. Load full user profile from tenant's users table
     */
    private UserDetails loadUserInProductionMode(String usernameOrEmail) {
        log.info("🔒 PRODUCTION MODE: Multi-tenant authentication");

        // 1. GLOBAL LOOKUP: Check Master DB for this user (try username, then email)
        Optional<GlobalUserDto> globalUserOpt = masterDatabaseService.findGlobalUser(usernameOrEmail);

        if (globalUserOpt.isEmpty()) {
            log.error("✗ User not found in Global Directory: {}", usernameOrEmail);
            throw new UsernameNotFoundException("User not found in Global Directory");
        }

        GlobalUserDto globalUser = globalUserOpt.get();
        String tenantId = globalUser.getTenantId();
        log.info("✓ User found in Global Directory. Tenant: {}", tenantId);

        // 2. CONTEXT SWITCH: Force the application to talk to the correct Tenant DB
        TenantContext.setCurrentTenant(tenantId);

        // 3. TENANT LOOKUP: fetch user by username or email from their tenant DB
        User user = findByUsernameOrEmail(usernameOrEmail)
                .orElseThrow(() -> {
                    log.error("✗ Integrity Error: User in Global DB but missing in Tenant DB ({})", tenantId);
                    return new UsernameNotFoundException("User data missing in Tenant Database");
                });

        log.info("✓ User profile loaded from Tenant DB ({})", tenantId);

        return buildUserDetails(user);
    }

    /**
     * LOCAL/UAT MODE: Single database authentication (username OR email)
     */
    private UserDetails loadUserInSingleDatabaseMode(String usernameOrEmail) {
        log.info("🔓 LOCAL/UAT MODE: Single database authentication");

        // Clear any existing tenant context
        TenantContext.clear();

        // Try username first, fall back to email
        User user = findByUsernameOrEmail(usernameOrEmail)
                .orElseThrow(() -> {
                    log.error("✗ User not found: {}", usernameOrEmail);
                    return new UsernameNotFoundException("User not found: " + usernameOrEmail);
                });

        log.info("✓ User profile loaded: {} (role: {})", user.getUsername(), user.getRole());

        return buildUserDetails(user);
    }

    /**
     * Common method to build UserDetails from User entity
     */
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