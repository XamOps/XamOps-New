package com.xammer.billops.controller;

import com.xammer.billops.config.multitenancy.TenantContext;
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.SupportMessage;
import com.xammer.billops.domain.SupportTicketStatus;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.SupportMessageRepository;
import com.xammer.billops.repository.SupportTicketStatusRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.service.EmailService;
import com.xammer.billops.service.ExternalMessagingService;
import com.xammer.cloud.domain.AppUser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Collections;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/billops/support")
@CrossOrigin(origins = "*")
public class SupportAdminController {

    private final SupportMessageRepository messageRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClientRepository clientRepository;
    private final AppUserRepository appUserRepository;
    private final UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    private final SupportTicketStatusRepository ticketStatusRepository;
    private final EmailService emailService;
    private final ExternalMessagingService externalMessagingService;

    @Value("${support.upload.dir:${user.home}/billops-uploads}")
    private String uploadDir;

    public SupportAdminController(SupportMessageRepository messageRepository,
                                   CloudAccountRepository cloudAccountRepository,
                                   ClientRepository clientRepository,
                                   AppUserRepository appUserRepository,
                                   UserCloudAccountAccessRepository userCloudAccountAccessRepository,
                                   SupportTicketStatusRepository ticketStatusRepository,
                                   EmailService emailService,
                                   ExternalMessagingService externalMessagingService) {
        this.messageRepository = messageRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientRepository = clientRepository;
        this.appUserRepository = appUserRepository;
        this.userCloudAccountAccessRepository = userCloudAccountAccessRepository;
        this.ticketStatusRepository = ticketStatusRepository;
        this.emailService = emailService;
        this.externalMessagingService = externalMessagingService;
    }

    // ── helper: build support notification email HTML ──
    private String buildSupportEmailHtml(String adminName, String message, String accountName, String portalUrl) {
        String escapedMessage = message
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<!DOCTYPE html><html><body style='margin:0;padding:0;background:#f4f6fb;font-family:Arial,sans-serif;'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f4f6fb;padding:32px 0;'>"
            + "<tr><td align='center'>"
            + "<table width='560' cellpadding='0' cellspacing='0' style='background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08);'>"
            + "<tr><td style='background:linear-gradient(135deg,#4f46e5,#7c3aed);padding:28px 32px;'>"
            + "<h1 style='margin:0;color:#ffffff;font-size:20px;font-weight:700;'>XamOps Support</h1>"
            + "<p style='margin:4px 0 0;color:rgba(255,255,255,0.75);font-size:13px;'>New message from your support team</p>"
            + "</td></tr>"
            + "<tr><td style='padding:28px 32px;'>"
            + "<p style='margin:0 0 6px;font-size:13px;color:#6b7280;'>Message from <strong style='color:#1f2937;'>" + adminName + "</strong> regarding <strong style='color:#1f2937;'>" + accountName + "</strong>:</p>"
            + "<div style='background:#f8faff;border-left:4px solid #4f46e5;border-radius:6px;padding:16px 20px;margin:16px 0;'>"
            + "<p style='margin:0;font-size:15px;color:#1f2937;line-height:1.6;'>" + escapedMessage + "</p>"
            + "</div>"
            + "<a href='" + portalUrl + "' style='display:inline-block;margin-top:8px;background:linear-gradient(135deg,#4f46e5,#7c3aed);color:#ffffff;text-decoration:none;font-size:14px;font-weight:600;padding:12px 28px;border-radius:8px;'>Reply in XamOps Portal →</a>"
            + "</td></tr>"
            + "<tr><td style='padding:16px 32px 24px;border-top:1px solid #f3f4f6;'>"
            + "<p style='margin:0;font-size:11px;color:#9ca3af;'>This is an automated notification from XamOps Support. Reply directly in the portal.</p>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    // ── helper: current admin username ──
    private String currentAdminUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getName() != null) ? auth.getName() : "BillOps Admin";
    }

    // ── helper: look up the currently authenticated AppUser ──
    private AppUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        return appUserRepository.findByUsername(auth.getName()).orElse(null);
    }

    /**
     * Returns the set of client IDs accessible to the current user, or null = "no restriction / see all".
     *
     * ROLE_BILLOPS_ADMIN (primary, createdBy == null)  → null (see ALL clients)
     * ROLE_BILLOPS_ADMIN_SUB with allAccountsAccess    → null (explicitly given full access)
     * ROLE_BILLOPS_ADMIN_SUB (no account access)       → only clients where ownerAdminId = their ID
     * ROLE_BILLOPS_ADMIN sub-user (createdBy != null)  → clients derived from assigned cloud accounts
     */
    private Set<Long> getAllowedClientIds(AppUser currentUser) {
        if (currentUser == null) return null;

        // ROLE_BILLOPS_ADMIN_SUB — isolated sub-admin scope
        if ("ROLE_BILLOPS_ADMIN_SUB".equals(currentUser.getRole())) {
            if (currentUser.isAllAccountsAccess()) {
                return null; // explicitly granted full access by the primary admin
            }
            // Scope to clients owned by this sub-admin
            List<Client> ownedClients = clientRepository.findByOwnerAdminId(currentUser.getId());
            if (ownedClients.isEmpty()) return Collections.emptySet();
            Set<Long> clientIds = new HashSet<>();
            for (Client c : ownedClients) clientIds.add(c.getId());
            return clientIds;
        }

        // Primary ROLE_BILLOPS_ADMIN (createdBy == null) — see everything
        if (currentUser.getCreatedBy() == null) {
            return null;
        }

        // ROLE_BILLOPS_ADMIN sub-user (createdBy != null) — derive from assigned accounts
        if (currentUser.isAllAccountsAccess()) {
            return null; // sub-user was given all-accounts access
        }
        List<Long> accountIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(currentUser.getId());
        if (accountIds.isEmpty()) return Collections.emptySet();

        Set<Long> clientIds = new HashSet<>();
        for (CloudAccount account : cloudAccountRepository.findAllById(accountIds)) {
            if (account.getClient() != null) {
                clientIds.add(account.getClient().getId());
            }
        }
        return clientIds;
    }

    // 1. Get Inbox — one entry per user (keyed by userId, not accountId)
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @GetMapping("/inbox")
    public List<Map<String, Object>> getInbox() {
        AppUser currentUser = getCurrentUser();
        Set<Long> allowedClientIds = getAllowedClientIds(currentUser);

        List<Object[]> results = messageRepository.findDistinctUserConversations();
        List<Map<String, Object>> inbox = new ArrayList<>();

        for (Object[] row : results) {
            // Column order: user_id[0], sender_username[1]
            Long userId = row[0] != null ? ((Number) row[0]).longValue() : null;
            if (userId == null) continue;

            // Resolve username and email from AppUser record
            AppUser appUser = appUserRepository.findById(userId).orElse(null);
            String username = (String) row[1];
            if (username == null && appUser != null) username = appUser.getUsername();

            // Sub-user access control: only show conversations from users in allowed clients
            if (allowedClientIds != null) {
                Long userClientId = (appUser != null && appUser.getClient() != null)
                        ? appUser.getClient().getId() : null;
                if (userClientId == null || !allowedClientIds.contains(userClientId)) {
                    continue; // skip — user belongs to a client this sub-admin cannot see
                }
            }

            Map<String, Object> conversation = new HashMap<>();
            conversation.put("userId", userId);
            conversation.put("username", username != null ? username : "Unknown");
            if (appUser != null && appUser.getEmail() != null) {
                conversation.put("email", appUser.getEmail());
            }

            // Unread count and last message scoped by userId
            long unreadCount = messageRepository.countByUserIdAndIsUserMessageTrueAndStatus(userId, "UNREAD");
            conversation.put("unreadCount", unreadCount);

            // Admin messages the user hasn't opened/seen yet (DELIVERED → not yet READ by user)
            long adminUnreadCount = messageRepository.countByUserIdAndIsUserMessageFalseAndStatus(userId, "DELIVERED");
            conversation.put("adminUnreadCount", adminUnreadCount);

            SupportMessage lastMsg = messageRepository.findFirstByUserIdOrderByTimestampDesc(userId);
            if (lastMsg != null) {
                conversation.put("lastMessage", lastMsg.getContent());
                conversation.put("lastMessageTime", lastMsg.getTimestamp().toString());
            }

            // Ticket status + assigned admin
            SupportTicketStatus ticketStatus = ticketStatusRepository.findByUserId(userId).orElse(null);
            if (ticketStatus != null) {
                conversation.put("ticketStatus", ticketStatus.getStatus());
                conversation.put("assignedAdminUsername", ticketStatus.getAssignedAdminUsername());
            } else {
                conversation.put("ticketStatus", "OPEN");
                conversation.put("assignedAdminUsername", null);
            }

            inbox.add(conversation);
        }
        return inbox;
    }

    // Get Chat Sessions (for notification system) — now userId-based
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @GetMapping("/chat-sessions")
    public List<Map<String, String>> getChatSessions() {
        List<Object[]> results = messageRepository.findDistinctUserConversations();
        List<Map<String, String>> sessions = new ArrayList<>();

        for (Object[] row : results) {
            // Column order: user_id[0], sender_username[1]
            Map<String, String> session = new HashMap<>();
            session.put("userId", row[0] != null ? row[0].toString() : null);
            session.put("username", (String) row[1]);
            sessions.add(session);
        }
        return sessions;
    }

    // 2a. Get Chat History by userId (primary — user-based conversations)
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @GetMapping("/chat/user/{userId}")
    public List<SupportMessage> getChatHistoryByUser(@PathVariable Long userId) {
        // Mark all unread user messages as read
        List<SupportMessage> unread = messageRepository.findByUserIdAndIsUserMessageTrueAndStatus(userId, "UNREAD");
        if (!unread.isEmpty()) {
            for (SupportMessage msg : unread) msg.setStatus("READ");
            messageRepository.saveAll(unread);
        }
        return messageRepository.findByUserIdOrderByTimestampAsc(userId);
    }

    // 2b. Get Chat History by accountId (kept for backward compat — user-side still uses accountId)
    @GetMapping("/chat/{accountId}")
    public List<SupportMessage> getChatHistory(@PathVariable String accountId,
                                                @RequestParam(required = false) Long userId) {
        List<SupportMessage> unreadMessages = (userId != null)
                ? messageRepository.findByAccountIdAndUserIdAndIsUserMessageTrueAndStatus(accountId, userId, "UNREAD")
                : messageRepository.findByAccountIdAndIsUserMessageTrueAndStatus(accountId, "UNREAD");

        if (!unreadMessages.isEmpty()) {
            for (SupportMessage msg : unreadMessages) msg.setStatus("READ");
            messageRepository.saveAll(unreadMessages);
        }

        return (userId != null)
                ? messageRepository.findByAccountIdAndUserIdOrderByTimestampAsc(accountId, userId)
                : messageRepository.findByAccountIdOrderByTimestampAsc(accountId);
    }

    // 3. Send Reply (Admin -> User)
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @PostMapping("/reply")
    public SupportMessage sendReply(@RequestBody Map<String, String> payload) {
        String accountId = payload.get("accountId"); // may be null for user-based conversations
        String messageContent = payload.getOrDefault("message", "");

        // Resolve userId from payload — the primary conversation key
        String userIdStr = payload.get("userId");
        Long payloadUserId = null;
        if (userIdStr != null) {
            try { payloadUserId = Long.parseLong(userIdStr); } catch (NumberFormatException ignored) {}
        }

        // Fetch history to carry over accountName/cloudProvider from existing thread
        List<SupportMessage> history = Collections.emptyList();
        if (payloadUserId != null) {
            history = messageRepository.findByUserIdOrderByTimestampAsc(payloadUserId);
        } else if (accountId != null) {
            history = messageRepository.findByAccountIdOrderByTimestampAsc(accountId);
        }

        String accountName = null;
        String cloudProvider = null;
        Long userId = payloadUserId;

        if (!history.isEmpty()) {
            SupportMessage firstMsg = history.get(0);
            if (accountId == null) accountId = firstMsg.getAccountId();
            accountName = firstMsg.getAccountName();
            cloudProvider = firstMsg.getCloudProvider();
            if (userId == null) userId = firstMsg.getUserId();
        } else {
            accountName = payload.get("accountName");
            cloudProvider = payload.get("cloudProvider");
        }

        if (userId == null) {
            throw new RuntimeException("userId is required");
        }

        // Get the currently logged-in admin's username
        String adminUsername = currentAdminUsername();

        SupportMessage reply = new SupportMessage();
        reply.setAccountId(accountId);
        reply.setAccountName(accountName);
        reply.setCloudProvider(cloudProvider);
        reply.setUserId(userId);
        reply.setContent(messageContent);
        reply.setUserMessage(false);
        reply.setTimestamp(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        reply.setStatus("DELIVERED");
        reply.setSenderUsername(adminUsername); // actual admin username, not hardcoded

        // Auto-ping settings (only for admin-initiated conversations)
        String autoPingStr = payload.get("autoPingEnabled");
        if ("true".equalsIgnoreCase(autoPingStr)) {
            reply.setAutoPingEnabled(true);
            reply.setAutoPingChannels(payload.get("autoPingChannels"));
        }

        // Handle optional attachment
        String attachmentUrl = payload.get("attachmentUrl");
        if (attachmentUrl != null && !attachmentUrl.isEmpty()) {
            reply.setAttachmentUrl(attachmentUrl);
            reply.setAttachmentName(payload.get("attachmentName"));
            reply.setAttachmentType(payload.get("attachmentType"));
            String sizeStr = payload.get("attachmentSize");
            if (sizeStr != null) {
                try { reply.setAttachmentSize(Long.parseLong(sizeStr)); } catch (NumberFormatException ignored) {}
            }
        }

        SupportMessage saved = messageRepository.save(reply);

        // Look up user's email (used for email notification + Slack auto-discovery)
        String userEmail = appUserRepository.findById(userId)
                .map(AppUser::getEmail)
                .orElse(null);

        // Route to external platform (Slack / Teams / Google Chat) if requested
        String externalPlatform = payload.get("externalPlatform");
        if (externalPlatform != null && !externalPlatform.isBlank()) {
            String externalMsgId = externalMessagingService.sendMessage(
                    userId, externalPlatform.toUpperCase(), messageContent, adminUsername, userEmail);
            if (externalMsgId != null) {
                saved.setExternalPlatform(externalPlatform.toUpperCase());
                saved.setExternalMessageId(externalMsgId);
                messageRepository.save(saved);
            }
        }

        // Auto-send email notification to user if they have an email on file
        if (userEmail != null && !userEmail.isBlank()) {
            try {
                String portalUrl = "https://live.xamops.com/";
                String displayName = saved.getAccountName() != null ? saved.getAccountName() : "your account";
                String htmlBody = buildSupportEmailHtml(adminUsername, messageContent, displayName, portalUrl);
                emailService.sendHtmlEmail(
                        userEmail,
                        "New message from XamOps Support – " + adminUsername,
                        htmlBody
                );
            } catch (Exception e) {
                // Don't fail the reply if email notification fails
                System.err.println("Failed to send email notification to " + userEmail + ": " + e.getMessage());
            }
        }

        // Auto-assign ticket to this admin and set status to IN_PROGRESS
        SupportTicketStatus ticketStatus = ticketStatusRepository.findByUserId(userId)
                .orElse(new SupportTicketStatus());
        ticketStatus.setUserId(userId);
        // Only move to IN_PROGRESS if currently OPEN; keep CLOSED as-is unless forced
        if (!"CLOSED".equals(ticketStatus.getStatus())) {
            ticketStatus.setStatus("IN_PROGRESS");
        }
        ticketStatus.setAssignedAdminUsername(adminUsername);
        ticketStatus.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        ticketStatus.setUpdatedByAdmin(adminUsername);
        ticketStatusRepository.save(ticketStatus);

        return saved;
    }

    // 11. Get ticket status for a user conversation
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @GetMapping("/ticket/{userId}/status")
    public Map<String, Object> getTicketStatus(@PathVariable Long userId) {
        SupportTicketStatus ts = ticketStatusRepository.findByUserId(userId).orElse(null);
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("status", ts != null ? ts.getStatus() : "OPEN");
        result.put("assignedAdminUsername", ts != null ? ts.getAssignedAdminUsername() : null);
        result.put("updatedAt", ts != null && ts.getUpdatedAt() != null ? ts.getUpdatedAt().toString() : null);
        result.put("updatedByAdmin", ts != null ? ts.getUpdatedByAdmin() : null);
        return result;
    }

    // 12. Update ticket status (OPEN / IN_PROGRESS / CLOSED)
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @PatchMapping("/ticket/{userId}/status")
    public Map<String, Object> updateTicketStatus(@PathVariable Long userId,
                                                   @RequestBody Map<String, String> payload) {
        String newStatus = payload.get("status");
        if (newStatus == null || (!newStatus.equals("OPEN") && !newStatus.equals("IN_PROGRESS") && !newStatus.equals("CLOSED"))) {
            throw new RuntimeException("Invalid status. Must be OPEN, IN_PROGRESS, or CLOSED.");
        }
        String adminUsername = currentAdminUsername();
        SupportTicketStatus ts = ticketStatusRepository.findByUserId(userId)
                .orElse(new SupportTicketStatus());
        ts.setUserId(userId);
        ts.setStatus(newStatus);
        ts.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        ts.setUpdatedByAdmin(adminUsername);
        // Keep existing assignedAdminUsername if already set
        if (ts.getAssignedAdminUsername() == null) {
            ts.setAssignedAdminUsername(adminUsername);
        }
        ticketStatusRepository.save(ts);

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("status", ts.getStatus());
        result.put("assignedAdminUsername", ts.getAssignedAdminUsername());
        result.put("updatedByAdmin", adminUsername);
        return result;
    }

    // 13. Send full chat thread as email (multi-recipient + send mode)
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @PostMapping("/ticket/{userId}/email")
    public ResponseEntity<Map<String, Object>> sendTicketEmail(@PathVariable Long userId,
                                                                @RequestBody Map<String, Object> payload) {
        // Collect recipient emails — support both new recipientEmails[] and legacy recipientEmail
        List<String> recipients = new java.util.ArrayList<>();
        Object emailsField = payload.get("recipientEmails");
        if (emailsField instanceof java.util.List) {
            ((java.util.List<?>) emailsField).forEach(e -> {
                if (e instanceof String && !((String) e).isBlank()) recipients.add(((String) e).trim());
            });
        }
        if (recipients.isEmpty()) {
            Object single = payload.get("recipientEmail");
            if (single instanceof String && !((String) single).isBlank()) recipients.add(((String) single).trim());
        }

        // Fall back to user's registered email if no recipients provided
        AppUser appUser = appUserRepository.findById(userId).orElse(null);
        if (recipients.isEmpty()) {
            if (appUser != null && appUser.getEmail() != null && !appUser.getEmail().isBlank()) {
                recipients.add(appUser.getEmail().trim());
            }
        }
        if (recipients.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No recipient email provided or found for user."));
        }

        String sendMode = payload.getOrDefault("sendMode", "separate") instanceof String
                ? (String) payload.get("sendMode") : "separate";

        Object subjectObj = payload.get("subject");
        String username = appUser != null ? appUser.getUsername() : ("User #" + userId);
        String subject = (subjectObj instanceof String && !((String) subjectObj).isBlank())
                ? (String) subjectObj
                : "Support Chat Thread – " + username;

        List<SupportMessage> messages = messageRepository.findByUserIdOrderByTimestampAsc(userId);
        String htmlBody = buildChatThreadEmail(username, messages);

        try {
            if ("combined".equalsIgnoreCase(sendMode)) {
                emailService.sendHtmlEmailToMultiple(recipients.toArray(new String[0]), subject, htmlBody);
            } else {
                for (String email : recipients) {
                    emailService.sendHtmlEmail(email, subject, htmlBody);
                }
            }
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("sentTo", String.join(", ", recipients));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to send email: " + e.getMessage()));
        }
    }

    // 4. Get all cloud accounts available for admin to start conversations with
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @GetMapping("/accounts")
    public List<Map<String, Object>> getAvailableAccounts() {
        AppUser currentUser = getCurrentUser();
        Set<Long> allowedClientIds = getAllowedClientIds(currentUser);

        List<CloudAccount> accounts;
        if (allowedClientIds == null) {
            // Primary admin — see all accounts
            accounts = cloudAccountRepository.findAll();
        } else if (allowedClientIds.isEmpty()) {
            accounts = Collections.emptyList();
        } else {
            // Sub-user — show ALL accounts belonging to their allowed clients (client-level access)
            // e.g. assigned [2,3,5] → clients [A, B] → accounts [1,2,3,4,5,6]
            accounts = new ArrayList<>();
            for (Long clientId : allowedClientIds) {
                accounts.addAll(cloudAccountRepository.findByClientId(clientId));
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (CloudAccount account : accounts) {
            String accountId = resolveExternalId(account);
            if (accountId == null) continue;

            Map<String, Object> item = new HashMap<>();
            item.put("id", account.getId());
            item.put("accountId", accountId);
            item.put("accountName", account.getAccountName());
            item.put("provider", account.getProvider());
            result.add(item);
        }
        return result;
    }

    // 5. Upload file attachment
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadAttachment(
            @RequestParam("file") MultipartFile file) throws IOException {

        String tenant = TenantContext.getCurrentTenant();
        if (tenant == null || tenant.isEmpty()) tenant = "default";

        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._\\-]", "_")
                : "file";
        String safeFilename = UUID.randomUUID().toString() + "_" + originalFilename;

        Path tenantDir = Paths.get(uploadDir, "support", tenant);
        Files.createDirectories(tenantDir);

        Path filePath = tenantDir.resolve(safeFilename);
        Files.copy(file.getInputStream(), filePath);

        String url = "/api/billops/support/files/" + tenant + "/" + safeFilename;

        Map<String, Object> response = new HashMap<>();
        response.put("url", url);
        response.put("name", file.getOriginalFilename());
        response.put("type", file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        response.put("size", file.getSize());

        return ResponseEntity.ok(response);
    }

    // 6. Serve file attachment
    @GetMapping("/files/{tenantId}/{filename:.+}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable String tenantId,
            @PathVariable String filename) throws MalformedURLException {

        Path filePath = Paths.get(uploadDir, "support", tenantId, filename);
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        String contentType = "application/octet-stream";
        try {
            String probed = Files.probeContentType(filePath);
            if (probed != null) contentType = probed;
        } catch (IOException ignored) {}

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
    }

    // 7. Get all clients
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @GetMapping("/clients")
    public List<Map<String, Object>> getClients() {
        AppUser currentUser = getCurrentUser();
        Set<Long> allowedClientIds = getAllowedClientIds(currentUser);

        List<Client> clients;
        if (allowedClientIds == null) {
            // Primary admin — see all clients
            clients = clientRepository.findAll();
        } else if (allowedClientIds.isEmpty()) {
            clients = Collections.emptyList();
        } else {
            clients = clientRepository.findAllById(allowedClientIds);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Client client : clients) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", client.getId());
            item.put("name", client.getName());
            result.add(item);
        }
        return result;
    }

    // 8. Get profiles (users + their accessible accounts) for a client
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @GetMapping("/clients/{clientId}/profiles")
    public List<Map<String, Object>> getClientProfiles(@PathVariable Long clientId) {
        // Sub-user access guard: deny if this client is not derived from their assigned accounts
        AppUser currentUser = getCurrentUser();
        Set<Long> allowedClientIds = getAllowedClientIds(currentUser);
        if (allowedClientIds != null && !allowedClientIds.contains(clientId)) {
            return Collections.emptyList();
        }

        List<AppUser> users = appUserRepository.findByClientId(clientId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (AppUser user : users) {
            List<Long> accountIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(user.getId());

            List<Map<String, Object>> accounts = new ArrayList<>();

            if (!accountIds.isEmpty()) {
                for (Long accountId : accountIds) {
                    cloudAccountRepository.findById(accountId).ifPresent(account -> {
                        String externalId = resolveExternalId(account);
                        if (externalId != null) {
                            Map<String, Object> accountItem = new HashMap<>();
                            accountItem.put("id", account.getId());
                            accountItem.put("accountId", externalId);
                            accountItem.put("accountName", account.getAccountName());
                            accountItem.put("provider", account.getProvider());
                            accounts.add(accountItem);
                        }
                    });
                }
            } else {
                List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(clientId);
                for (CloudAccount account : clientAccounts) {
                    String externalId = resolveExternalId(account);
                    if (externalId != null) {
                        Map<String, Object> accountItem = new HashMap<>();
                        accountItem.put("id", account.getId());
                        accountItem.put("accountId", externalId);
                        accountItem.put("accountName", account.getAccountName());
                        accountItem.put("provider", account.getProvider());
                        accounts.add(accountItem);
                    }
                }
            }

            Map<String, Object> profile = new HashMap<>();
            profile.put("id", user.getId());
            profile.put("username", user.getUsername());
            profile.put("email", user.getEmail());
            profile.put("contact", user.getContact());
            profile.put("accounts", accounts);
            result.add(profile);
        }
        return result;
    }

    // 9. Edit an admin message (within 15 minutes of sending)
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @PatchMapping("/messages/{id}")
    public ResponseEntity<SupportMessage> editMessage(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload) {

        return messageRepository.findById(id).map(msg -> {
            if (msg.isUserMessage()) return ResponseEntity.status(403).<SupportMessage>build();

            LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusMinutes(15);
            if (msg.getTimestamp().isBefore(cutoff)) return ResponseEntity.status(403).<SupportMessage>build();

            msg.setContent(payload.getOrDefault("content", msg.getContent()));
            msg.setEdited(true);
            msg.setEditedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            return ResponseEntity.ok(messageRepository.save(msg));
        }).orElse(ResponseEntity.notFound().build());
    }

    // 10. Delete an admin message (within 60 minutes of sending — soft delete)
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @DeleteMapping("/messages/{id}")
    public ResponseEntity<SupportMessage> deleteMessage(@PathVariable Long id) {

        return messageRepository.findById(id).map(msg -> {
            if (msg.isUserMessage()) return ResponseEntity.status(403).<SupportMessage>build();

            LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusMinutes(60);
            if (msg.getTimestamp().isBefore(cutoff)) return ResponseEntity.status(403).<SupportMessage>build();

            msg.setDeleted(true);
            return ResponseEntity.ok(messageRepository.save(msg));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Build HTML email for chat thread ──
    private String buildChatThreadEmail(String username, List<SupportMessage> messages) {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");
        StringBuilder rows = new StringBuilder();

        for (SupportMessage msg : messages) {
            if (msg.isDeleted()) continue;
            boolean isUser = msg.isUserMessage();
            String senderLabel = isUser ? username : (msg.getSenderUsername() != null ? msg.getSenderUsername() : "Support Team");
            String bgColor = isUser ? "#f1f5f9" : "#ede9fe";
            String align = isUser ? "left" : "right";
            String labelColor = isUser ? "#475569" : "#5b21b6";
            String time = msg.getTimestamp() != null ? msg.getTimestamp().format(dtf) : "";

            rows.append("<tr><td style=\"padding: 10px 0;\">")
                .append("<div style=\"text-align: ").append(align).append("; max-width: 100%;\">")
                .append("<div style=\"display: inline-block; background: ").append(bgColor)
                .append("; border-radius: 12px; padding: 10px 16px; max-width: 75%; text-align: left;\">")
                .append("<div style=\"font-size: 11px; font-weight: 700; color: ").append(labelColor)
                .append("; margin-bottom: 4px;\">").append(escapeHtml(senderLabel)).append("</div>")
                .append("<div style=\"font-size: 14px; color: #1e293b; white-space: pre-wrap; word-break: break-word;\">")
                .append(escapeHtml(msg.getContent() != null ? msg.getContent() : "")).append("</div>")
                .append("<div style=\"font-size: 10px; color: #94a3b8; margin-top: 6px;\">").append(time).append("</div>")
                .append("</div></div>")
                .append("</td></tr>");
        }

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"margin:0;padding:0;background:#f8fafc;\">"
            + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f8fafc;padding:20px 0;\">"
            + "<tr><td align=\"center\">"
            + "<table width=\"640\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-family:'Segoe UI',Arial,sans-serif;max-width:640px;width:100%;\">"
            + "<tr><td style=\"background:linear-gradient(135deg,#4f46e5 0%,#7c3aed 100%);padding:28px 32px;border-radius:12px 12px 0 0;text-align:center;\">"
            + "<h1 style=\"color:#fff;margin:0;font-size:20px;font-weight:700;\">XamOps Support Thread</h1>"
            + "<p style=\"color:rgba(255,255,255,0.8);margin:8px 0 0 0;font-size:13px;\">Customer: " + escapeHtml(username) + "</p>"
            + "</td></tr>"
            + "<tr><td style=\"background:#fff;padding:24px 32px;border:1px solid #e2e8f0;border-top:none;border-radius:0 0 12px 12px;\">"
            + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">"
            + rows.toString()
            + "</table></td></tr>"
            + "<tr><td style=\"text-align:center;padding:16px;color:#94a3b8;font-size:11px;\">Generated by XamOps Support</td></tr>"
            + "</table></td></tr></table>"
            + "</body></html>";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                   .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String resolveExternalId(CloudAccount account) {
        String provider = account.getProvider();
        if ("AWS".equalsIgnoreCase(provider)) return account.getAwsAccountId();
        if ("GCP".equalsIgnoreCase(provider)) return account.getGcpProjectId();
        if ("Azure".equalsIgnoreCase(provider)) return account.getAzureSubscriptionId();
        return account.getExternalId();
    }

    // ── Sub-admin client assignment ──

    /**
     * GET /api/billops/support/sub-admins
     * Lists all ROLE_BILLOPS_ADMIN_SUB users created under the current primary admin.
     * Only accessible by the primary ROLE_BILLOPS_ADMIN.
     */
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @GetMapping("/sub-admins")
    public ResponseEntity<List<Map<String, Object>>> getSubAdmins() {
        AppUser currentUser = getCurrentUser();
        if (currentUser == null || currentUser.getCreatedBy() != null) {
            return ResponseEntity.status(403).build(); // only primary admin
        }
        List<AppUser> subAdmins = appUserRepository.findByCreatedBy(currentUser.getId())
                .stream()
                .filter(u -> "ROLE_BILLOPS_ADMIN_SUB".equals(u.getRole()))
                .collect(java.util.stream.Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (AppUser sub : subAdmins) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", sub.getId());
            item.put("username", sub.getUsername());
            item.put("email", sub.getEmail());
            item.put("allAccountsAccess", sub.isAllAccountsAccess());
            // Clients already assigned to this sub-admin
            List<Long> assignedClientIds = clientRepository.findByOwnerAdminId(sub.getId())
                    .stream().map(Client::getId).collect(java.util.stream.Collectors.toList());
            item.put("assignedClientIds", assignedClientIds);
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * PUT /api/billops/support/sub-admins/{subAdminId}/clients
     * Assigns the given client IDs to a ROLE_BILLOPS_ADMIN_SUB user.
     * Replaces any previous assignment (pass empty list to clear).
     * Only the primary ROLE_BILLOPS_ADMIN may call this.
     *
     * Request body: { "clientIds": [1, 2, 3] }
     */
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    @PutMapping("/sub-admins/{subAdminId}/clients")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> assignClientsToSubAdmin(
            @PathVariable Long subAdminId,
            @RequestBody Map<String, Object> payload) {

        AppUser currentUser = getCurrentUser();
        if (currentUser == null || currentUser.getCreatedBy() != null) {
            return ResponseEntity.status(403).body(Map.of("error", "Only the primary ROLE_BILLOPS_ADMIN can assign clients."));
        }

        AppUser subAdmin = appUserRepository.findById(subAdminId).orElse(null);
        if (subAdmin == null || !"ROLE_BILLOPS_ADMIN_SUB".equals(subAdmin.getRole())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Target user is not a ROLE_BILLOPS_ADMIN_SUB."));
        }
        if (!currentUser.getId().equals(subAdmin.getCreatedBy())) {
            return ResponseEntity.status(403).body(Map.of("error", "You can only manage sub-admins you created."));
        }

        // Parse incoming clientIds
        Object rawIds = payload.get("clientIds");
        List<Long> newClientIds = new ArrayList<>();
        if (rawIds instanceof List) {
            for (Object id : (List<?>) rawIds) {
                if (id instanceof Number) newClientIds.add(((Number) id).longValue());
            }
        }

        // Clear existing ownership by this sub-admin
        List<Client> previouslyOwned = clientRepository.findByOwnerAdminId(subAdminId);
        for (Client c : previouslyOwned) {
            c.setOwnerAdminId(null);
        }
        clientRepository.saveAll(previouslyOwned);

        // Assign new clients
        List<Client> toAssign = clientRepository.findAllById(newClientIds);
        for (Client c : toAssign) {
            c.setOwnerAdminId(subAdminId);
        }
        clientRepository.saveAll(toAssign);

        Map<String, Object> response = new HashMap<>();
        response.put("subAdminId", subAdminId);
        response.put("assignedClientIds", newClientIds);
        response.put("message", "Client assignments updated successfully.");
        return ResponseEntity.ok(response);
    }
}
