package com.xammer.cloud.service;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRegistrationService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Creates a new user in the CURRENT tenant's database AND the Global Master DB.
     */
    @Transactional
    public User createUser(String username, String rawPassword, String email, String role) {
        // 1. Get Current Tenant ID
        String currentTenant = TenantContext.getCurrentTenant();
        if (currentTenant == null) {
            throw new RuntimeException("Cannot create user: No active tenant context.");
        }

        // 2. Create in Local Tenant DB
        // Ensure a client exists (or pass specific client ID logic)
        Client client = clientRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new RuntimeException("No client found for this tenant"));

        String encodedPassword = passwordEncoder.encode(rawPassword);

        User newUser = new User();
        newUser.setUsername(username);
        newUser.setPassword(encodedPassword);
        newUser.setEmail(email);
        newUser.setRole(role);
        newUser.setClient(client);
        newUser.setEnabled(true);
        
        User savedUser = userRepository.save(newUser);

        // 3. Create in Master DB (Global Directory)
        try {
            masterDatabaseService.registerGlobalUser(
                username, 
                encodedPassword, 
                email, 
                role, 
                currentTenant
            );
        } catch (Exception e) {
            // Rollback if master write fails to keep data in sync
            throw new RuntimeException("Failed to register user in Global Directory: " + e.getMessage());
        }

        return savedUser;
    }
}