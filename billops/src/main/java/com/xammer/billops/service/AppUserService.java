package com.xammer.billops.service;

import com.xammer.billops.domain.AppUser;
import com.xammer.billops.domain.Client;
import com.xammer.billops.dto.UserDTO;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AppUserService {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Value("${app.login-url}")
    private String loginUrl;

    public AppUser createUser(UserDTO dto) {
        // Handle client
        Client client = clientRepository.findByName(dto.getClientName());
        if (client == null) {
            client = new Client(dto.getClientName());
            client = clientRepository.save(client); // DB generates ID
        }

        // Create user
        AppUser user = new AppUser(dto.getUsername(), passwordEncoder.encode(dto.getPassword()), dto.getEmail(), dto.getRole(), client);
        AppUser savedUser = appUserRepository.save(user);

        // Send email
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(dto.getEmail());
        message.setSubject("Your XamOps Account Details");
        message.setText("Username: " + dto.getUsername() + "\n" +
                        "Password: " + dto.getPassword() + "\n" +
                        "Login here: " + loginUrl);
        mailSender.send(message);

        return savedUser;
    }

    public List<UserDTO> getAllUsers() {
        List<AppUser> users = appUserRepository.findAll();
        return users.stream().map(user -> {
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setUsername(user.getUsername());
            dto.setEmail(user.getEmail());
            dto.setRole(user.getRole());
            dto.setClientId(user.getClient() != null ? user.getClient().getId() : null);
            dto.setClientName(user.getClient() != null ? user.getClient().getName() : "Unknown");
            return dto;
        }).collect(Collectors.toList());
    }

    public AppUser getUserById(Long id) {
        return appUserRepository.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Transactional
    public AppUser updateUser(UserDTO dto) {
        AppUser user = appUserRepository.findById(dto.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Update basic fields
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setRole(dto.getRole());

        // --- Handle Client ---
        Client client = user.getClient();
        if (client == null) {
            client = new Client();
            user.setClient(client);
        }

        // Update client name
        client.setName(dto.getClientName());

        // Only update password if not empty
        if (dto.getPassword() != null && !dto.getPassword().trim().isEmpty()) {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }

        return appUserRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        AppUser user = appUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Client client = user.getClient();
        Long clientId = client.getId();
        
        // Delete the user first
        appUserRepository.deleteById(id);
        
        // Check if any other users are still using this client
        long usersWithClient = appUserRepository.findAll().stream()
                .filter(u -> u.getClient() != null && u.getClient().getId().equals(clientId))
                .count();
        
        // If no other users have this client, delete it
        if (usersWithClient == 0) {
            clientRepository.deleteById(clientId);
        }
    }
}