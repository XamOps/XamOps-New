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
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

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
     * PRODUCTION MODE: Multi-tenant authentication
     * 1. Query global_users to find tenant
     * 2. Switch to tenant database
     * 3. Load full user profile from tenant's users table
     */
    private UserDetails loadUserInProductionMode(String username) {
        log.info("🔒 PRODUCTION MODE: Multi-tenant authentication");

        // 1. GLOBAL LOOKUP: Check Master DB for this user
        Optional<GlobalUserDto> globalUserOpt = masterDatabaseService.findGlobalUser(username);

        if (globalUserOpt.isEmpty()) {
            log.error("✗ User not found in Global Directory: {}", username);
            throw new UsernameNotFoundException("User not found in Global Directory");
        }

        GlobalUserDto globalUser = globalUserOpt.get();
        String tenantId = globalUser.getTenantId();
        log.info("✓ User found in Global Directory. Tenant: {}", tenantId);

        // 2. CONTEXT SWITCH: Force the application to talk to the correct Tenant DB
        TenantContext.setCurrentTenant(tenantId);

        // 3. TENANT LOOKUP: Now fetch the full user details from their specific
        // database
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.error("✗ Integrity Error: User in Global DB but missing in Tenant DB ({})", tenantId);
                    return new UsernameNotFoundException("User data missing in Tenant Database");
                });

        log.info("✓ User profile loaded from Tenant DB ({})", tenantId);

        return buildUserDetails(user);
    }

    /**
     * LOCAL/UAT MODE: Single database authentication
     * Directly query users table in the single database (no tenant switching)
     */
    private UserDetails loadUserInSingleDatabaseMode(String username) {
        log.info("🔓 LOCAL/UAT MODE: Single database authentication");

        // Clear any existing tenant context (shouldn't be set, but just in case)
        TenantContext.clear();

        // Directly query the users table in the current database
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.error("✗ User not found: {}", username);
                    return new UsernameNotFoundException("User not found: " + username);
                });

        log.info("✓ User profile loaded: {} (role: {})", username, user.getRole());

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