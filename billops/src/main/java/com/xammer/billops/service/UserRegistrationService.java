package com.xammer.billops.service;

import com.xammer.billops.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.UserRepository;
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
    private PasswordEncoder passwordEncoder;

    @Transactional
    public User createUser(String username, String rawPassword, String email, String role) {
        Client client = clientRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new RuntimeException("No client found"));

        String encodedPassword = passwordEncoder.encode(rawPassword);

        User newUser = new User();
        newUser.setUsername(username);
        newUser.setPassword(encodedPassword);
        newUser.setEmail(email);
        newUser.setRole(role);
        newUser.setClient(client);

        return userRepository.save(newUser);
    }
}