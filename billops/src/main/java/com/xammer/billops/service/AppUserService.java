package com.xammer.billops.service;

import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.UserTabAccess;
import com.xammer.billops.dto.UserDTO;
import com.xammer.billops.repository.AgreementRepository;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.SupportMessageRepository;
import com.xammer.billops.repository.SupportTicketStatusRepository;
import com.xammer.billops.repository.TicketReplyRepository;
import com.xammer.billops.repository.TicketRcaRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.repository.UserPlatformConnectionRepository;
import com.xammer.billops.repository.UserTabAccessRepository;
import com.xammer.cloud.messaging.MessagingProperties;
import com.xammer.cloud.messaging.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private UserTabAccessRepository userTabAccessRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TicketReplyRepository ticketReplyRepository;

    @Autowired
    private TicketRcaRepository ticketRcaRepository;

    @Autowired
    private AgreementRepository agreementRepository;

    @Autowired
    private CreditRequestRepository creditRequestRepository;

    @Autowired
    private SupportMessageRepository supportMessageRepository;

    @Autowired
    private SupportTicketStatusRepository supportTicketStatusRepository;

    @Autowired
    private UserPlatformConnectionRepository userPlatformConnectionRepository;

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Autowired
    private MessagingProperties messagingProperties;

    @Value("${app.login-url}")
    private String loginUrl;

    /**
     * Rejects a contact number the notification sender would not be able to dial.
     *
     * <p>Checked with {@link PhoneNumbers#toE164} rather than a regex because that is the exact
     * code the send path runs: anything this accepts will normalise identically at send time, and
     * anything it rejects would otherwise be stored, silently skipped at dispatch, and surface only
     * as "the customer never received their alerts".
     *
     * <p>Blank is allowed — a user with no mobile number receives email only. Throws
     * {@link IllegalArgumentException}, which the controller already converts to a 400 carrying this
     * message, matching how the duplicate-username check surfaces.
     */
    private void validateContact(String contact) {
        if (contact == null || contact.isBlank()) {
            return;
        }
        if (contact.trim().length() > 20) {
            // app_user.contact is varchar(20); a longer value fails at the DB with an opaque error.
            throw new IllegalArgumentException("Contact number must be 20 characters or fewer.");
        }
        if (PhoneNumbers.toE164(contact, messagingProperties.getDefaultCountryCode()) == null) {
            throw new IllegalArgumentException(
                    "'" + contact + "' is not a usable mobile number. Enter digits only, "
                    + "optionally with a country code — for example 9876543210 or +91 98765 43210.");
        }
    }

    public AppUser createUser(UserDTO dto) {
        // Check if username already exists
        if (appUserRepository.findByUsername(dto.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists: " + dto.getUsername());
        }
        validateContact(dto.getContact());

        // Resolve the caller to detect ROLE_BILLOPS_ADMIN_SUB (for client ownership + createdBy tagging)
        // Use SecurityContext authority as authoritative role (DB role field may be null for sub-admins)
        org.springframework.security.core.Authentication callerAuth =
                SecurityContextHolder.getContext().getAuthentication();
        String callerUsername = callerAuth != null ? callerAuth.getName() : null;
        boolean callerIsSubAdmin = callerAuth != null && callerAuth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_BILLOPS_ADMIN_SUB".equals(a.getAuthority()));
        AppUser callerUser = (callerUsername != null)
                ? appUserRepository.findByUsername(callerUsername).orElse(null)
                : null;

        // Handle client
        Client client = clientRepository.findByName(dto.getClientName());
        if (client == null) {
            client = new Client(dto.getClientName());
            // If a ROLE_BILLOPS_ADMIN_SUB is creating this client, tag it with their ID
            if (callerIsSubAdmin && callerUser != null) {
                client.setOwnerAdminId(callerUser.getId());
            }
            client = clientRepository.save(client); // DB generates ID
        } else if (callerIsSubAdmin && callerUser != null && client.getOwnerAdminId() == null) {
            // Existing client not yet owned — claim it for this sub-admin
            client.setOwnerAdminId(callerUser.getId());
            client = clientRepository.save(client);
        }

        // Create user
        AppUser user = new AppUser(dto.getUsername(), passwordEncoder.encode(dto.getPassword()), dto.getEmail(),
                dto.getRole(), client);
        user.setContact(dto.getContact());
        user.setDesignation(dto.getDesignation());
        // Set parent relationship: use explicit parentId from form, or auto-derive from calling sub-admin
        if (dto.getParentId() != null) {
            user.setCreatedBy(dto.getParentId());
        } else if (callerIsSubAdmin && callerUser != null) {
            // Sub-admin is creating this user — auto-tag createdBy so ticket/data scoping works
            user.setCreatedBy(callerUser.getId());
            log.info("Auto-set createdBy={} for new user '{}' created by SUB_ADMIN '{}'",
                    callerUser.getId(), dto.getUsername(), callerUser.getUsername());
        }
        AppUser savedUser = appUserRepository.save(user);

        // Assign XamOps sidebar tab access (only relevant for ROLE_XAMOPS users)
        if ("ROLE_XAMOPS".equals(savedUser.getRole())
                && dto.getAllowedTabs() != null && !dto.getAllowedTabs().isEmpty()) {
            for (String tabName : dto.getAllowedTabs()) {
                userTabAccessRepository.save(new UserTabAccess(savedUser, tabName));
            }
        }

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
        // Scope to owned clients for ROLE_BILLOPS_ADMIN_SUB
        org.springframework.security.core.Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();
        boolean isSubAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_BILLOPS_ADMIN_SUB".equals(a.getAuthority()));

        List<AppUser> users;
        if (isSubAdmin && auth != null) {
            // Find sub-admin by username (JWT ID may differ from tenant DB ID)
            AppUser subAdmin = appUserRepository.findByUsername(auth.getName()).orElse(null);
            if (subAdmin == null) {
                log.warn("SUB_ADMIN '{}' not found in app_user table — returning empty user list", auth.getName());
                return java.util.Collections.emptyList();
            }
            List<Client> ownedClients = clientRepository.findByOwnerAdminId(subAdmin.getId());
            if (ownedClients.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            java.util.Set<Long> ownedClientIds = ownedClients.stream()
                    .map(Client::getId)
                    .collect(java.util.stream.Collectors.toSet());
            users = appUserRepository.findAll().stream()
                    .filter(u -> u.getClient() != null && ownedClientIds.contains(u.getClient().getId()))
                    .collect(java.util.stream.Collectors.toList());
            log.info("SUB_ADMIN {} — returning {} scoped users from {} owned clients",
                    subAdmin.getUsername(), users.size(), ownedClients.size());
        } else {
            users = appUserRepository.findAll();
        }

        return users.stream().map(user -> {
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setUsername(user.getUsername());
            dto.setEmail(user.getEmail());
            dto.setRole(user.getRole());
            dto.setClientId(user.getClient() != null ? user.getClient().getId() : null);
            dto.setClientName(user.getClient() != null ? user.getClient().getName() : "Unknown");
            dto.setContact(user.getContact());
            dto.setDesignation(user.getDesignation());
            dto.setParentId(user.getCreatedBy());  // null = primary user, non-null = sub-user
            if ("ROLE_XAMOPS".equals(user.getRole())) {
                dto.setAllowedTabs(userTabAccessRepository.findTabNamesByUserId(user.getId()));
            }
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
        validateContact(dto.getContact());

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

        user.setContact(dto.getContact());
        user.setDesignation(dto.getDesignation());

        // Only update password if not empty
        if (dto.getPassword() != null && !dto.getPassword().trim().isEmpty()) {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }

        AppUser savedUser = appUserRepository.save(user);

        // Sync XamOps sidebar tab access — only when the tab list is provided on the request.
        // A null allowedTabs means "leave tab access unchanged"; an explicit (possibly empty)
        // list replaces the current assignment.
        if ("ROLE_XAMOPS".equals(savedUser.getRole()) && dto.getAllowedTabs() != null) {
            userTabAccessRepository.deleteByUserId(savedUser.getId());
            for (String tabName : dto.getAllowedTabs()) {
                userTabAccessRepository.save(new UserTabAccess(savedUser, tabName));
            }
        }

        return savedUser;
    }

    @Transactional
    public void deleteUser(Long id) {
        AppUser user = appUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Client client = user.getClient();
        Long clientId = client != null ? client.getId() : null;

        // Every table below carries a foreign key back to app_user. PostgreSQL rejects the
        // user delete while any of them still references the row (constraint violation ->
        // DataIntegrityViolationException -> HTTP 500), so each reference must be released
        // first. This is the bug behind "Delete Failed / HTTP 500" when deleting a user who
        // had raised tickets: ticket.creator_user_id still pointed at the user.

        // 1. Soft references (nullable FKs) — detach so the history/content is preserved.
        //    ticket.creator_user_id and ticket_reply.user_id allow NULL, so we keep the
        //    tickets and replies but drop the link to the departing user. For tickets we first
        //    snapshot the creator's username into ticket.creator_username so the UI can still
        //    show who originally raised the ticket after this user is deleted.
        ticketRepository.snapshotAndDetachCreator(id);
        ticketReplyRepository.detachAuthor(id);

        //    app_user.created_by self-references the parent admin; detaching keeps any
        //    sub-users this user created (they simply become primary users).
        appUserRepository.detachChildrenCreatedBy(id);

        // 2. Hard references (NOT NULL FKs) — these rows cannot exist without the user,
        //    so they are removed outright.
        ticketRcaRepository.deleteByGeneratedByUserId(id);
        agreementRepository.deleteByUploadedByUserId(id);
        creditRequestRepository.deleteByUserId(id);

        // 3. Integration tables owned by xamops-service (shared DB, no JPA entity here).
        //    They FK to app_user, so they must be cleared or the delete fails.
        appUserRepository.deleteFinOpsReportSchedulesByUserId(id);
        appUserRepository.deleteGithubIntegrationConfigByUserId(id);
        appUserRepository.deleteJenkinsIntegrationConfigByUserId(id);
        appUserRepository.deleteSonarqubeProjectsByUserId(id);

        // 4. Per-user access / mapping / support rows that belong solely to this user.
        userTabAccessRepository.deleteByUserId(id);
        userCloudAccountAccessRepository.deleteByUserId(id);
        userPlatformConnectionRepository.deleteByUserId(id);
        supportMessageRepository.deleteByUserId(id);
        supportTicketStatusRepository.deleteByUserId(id);

        // Use direct JPQL delete to bypass Hibernate entity lifecycle nullability checks
        appUserRepository.deleteByIdDirect(id);

        // Check if any other users are still using this client
        if (clientId != null) {
            long usersWithClient = appUserRepository.findByClientId(clientId).size();
            if (usersWithClient == 0) {
                clientRepository.deleteById(clientId);
            }
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
                "<span class='label'>Email (Login):</span>" +
                "<span class='value'>" + email + "</span>" +
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