package com.xammer.billops.security;

import com.xammer.billops.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.User;
import com.xammer.billops.dto.GlobalUserDto;
import com.xammer.billops.repository.UserRepository;
import com.xammer.billops.service.MasterDatabaseService;
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

@Service
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.info("========== CENTRALIZED LOGIN ATTEMPT ==========");
        log.info("User: {}", username);

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

        // 3. TENANT LOOKUP: Now fetch the full user details from their specific database
        // Because we set the context above, 'userRepository' now connects to the Tenant DB.
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.error("✗ Integrity Error: User in Global DB but missing in Tenant DB ({})", tenantId);
                    return new UsernameNotFoundException("User data missing in Tenant Database");
                });

        log.info("✓ User profile loaded from Tenant DB ({})", tenantId);

        // 4. Construct UserDetails
        Long clientId = user.getClient() != null ? user.getClient().getId() : null;
        Collection<GrantedAuthority> authorities = Collections.singletonList(
            new SimpleGrantedAuthority(user.getRole())
        );

        return new ClientUserDetails(
            user.getUsername(),
            user.getPassword(),
            authorities,
            clientId,
            user.getId()
        );
    }
}