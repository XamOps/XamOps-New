package com.xammer.billops.service;

import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.domain.Client;
import com.xammer.billops.dto.UserDTO;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AppUserService.class);

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Value("${app.login-url}")
    private String loginUrl;

    public AppUser createUser(UserDTO dto) {
        // Check if username already exists
        if (appUserRepository.findByUsername(dto.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists: " + dto.getUsername());
        }

        // Handle client
        Client client = clientRepository.findByName(dto.getClientName());
        if (client == null) {
            client = new Client(dto.getClientName());
            client = clientRepository.save(client); // DB generates ID
        }

        // Create user
        AppUser user = new AppUser(dto.getUsername(), passwordEncoder.encode(dto.getPassword()), dto.getEmail(),
                dto.getRole(), client);
        AppUser savedUser = appUserRepository.save(user);

        // Send welcome email with credentials
        try {
            sendWelcomeEmail(dto.getEmail(), dto.getUsername(), dto.getPassword());
            log.info("Welcome email sent successfully to: {}", dto.getEmail());
        } catch (Exception e) {
            log.error("Failed to send welcome email to: {}", dto.getEmail(), e);
            // Don't fail the user creation if email fails
        }

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
            client = new Client(dto.getClientName());
            user.setClient(client);
        } else {
            // Update client name if client already exists
            client.setName(dto.getClientName());
        }

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

    private void sendWelcomeEmail(String email, String username, String password) {
        String subject = "Welcome to XamOps - Your Account Credentials";

        String htmlBody = "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "<style>" +
                "body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }" +
                ".container { max-width: 600px; margin: 0 auto; padding: 20px; }" +
                ".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }"
                +
                ".content { background: #f9fafb; padding: 30px; border-radius: 0 0 10px 10px; }" +
                ".credentials-box { background: white; border-left: 4px solid #667eea; padding: 20px; margin: 20px 0; border-radius: 5px; }"
                +
                ".credential-item { margin: 15px 0; }" +
                ".label { font-weight: bold; color: #667eea; display: inline-block; width: 120px; }" +
                ".value { background: #f3f4f6; padding: 8px 12px; border-radius: 4px; font-family: monospace; display: inline-block; }"
                +
                ".button { display: inline-block; background: #667eea; color: white; padding: 12px 30px; text-decoration: none; border-radius: 5px; margin: 20px 0; }"
                +
                ".footer { text-align: center; padding: 20px; color: #6b7280; font-size: 12px; }" +
                ".warning { background: #fef3c7; border-left: 4px solid #f59e0b; padding: 15px; margin: 20px 0; border-radius: 5px; }"
                +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div class='container'>" +
                "<div class='header'>" +
                "<h1 style='margin: 0;'>Welcome to XamOps!</h1>" +
                "<p style='margin: 10px 0 0 0; opacity: 0.9;'>Your Cloud Management Platform</p>" +
                "</div>" +
                "<div class='content'>" +
                "<p>Hello,</p>" +
                "<p>Your XamOps account has been created by the administrator. " +
                "Below are your login credentials to access the platform:</p>" +
                "<div class='credentials-box'>" +
                "<h3 style='margin-top: 0; color: #667eea;'>Your Login Credentials</h3>" +
                "<div class='credential-item'>" +
                "<span class='label'>Username:</span>" +
                "<span class='value'>" + username + "</span>" +
                "</div>" +
                "<div class='credential-item'>" +
                "<span class='label'>Password:</span>" +
                "<span class='value'>" + password + "</span>" +
                "</div>" +
                "<div class='credential-item'>" +
                "<span class='label'>Portal URL:</span>" +
                "<span class='value'>https://live.xamops.com/</span>" +
                "</div>" +
                "</div>" +
                "<div class='warning'>" +
                "<strong>⚠️ Security Reminder:</strong> Please change your password after your first login for security purposes."
                +
                "</div>" +
                "<div style='text-align: center;'>" +
                "<a href='https://live.xamops.com/' class='button'>Login to XamOps</a>" +
                "</div>" +
                "<p>If you have any questions or need assistance, please contact your administrator.</p>" +
                "<p>Best regards,<br><strong>The XamOps Team</strong></p>" +
                "</div>" +
                "<div class='footer'>" +
                "<p>This is an automated message. Please do not reply to this email.</p>" +
                "<p>&copy; 2025 XamOps. All rights reserved.</p>" +
                "</div>" +
                "</div>" +
                "</body>" +
                "</html>";

        emailService.sendHtmlEmail(email, subject, htmlBody);
    }
}