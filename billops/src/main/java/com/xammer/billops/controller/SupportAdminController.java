package com.xammer.billops.controller;

import com.xammer.billops.config.multitenancy.TenantContext;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.SupportMessage;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.SupportMessageRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/billops/support")
@CrossOrigin(origins = "*")
public class SupportAdminController {

    private final SupportMessageRepository messageRepository;
    private final CloudAccountRepository cloudAccountRepository;

    @Value("${support.upload.dir:${user.home}/billops-uploads}")
    private String uploadDir;

    public SupportAdminController(SupportMessageRepository messageRepository,
                                   CloudAccountRepository cloudAccountRepository) {
        this.messageRepository = messageRepository;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    // 1. Get Inbox (List of all conversations)
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    @GetMapping("/inbox")
    public List<Map<String, Object>> getInbox() {
        List<Object[]> results = messageRepository.findDistinctConversations();
        List<Map<String, Object>> inbox = new ArrayList<>();

        for (Object[] row : results) {
            String accountId = (String) row[0];
            Map<String, Object> conversation = new HashMap<>();
            conversation.put("accountId", accountId);
            conversation.put("accountName", (String) row[1]);
            conversation.put("provider", (String) row[2]);
            conversation.put("senderUsername", (String) row[3]);

            long unreadCount = messageRepository.countByAccountIdAndIsUserMessageTrueAndStatus(accountId, "UNREAD");
            conversation.put("unreadCount", unreadCount);

            SupportMessage lastMsg = messageRepository.findFirstByAccountIdOrderByTimestampDesc(accountId);
            if (lastMsg != null) {
                conversation.put("lastMessage", lastMsg.getContent());
                conversation.put("lastMessageTime", lastMsg.getTimestamp().toString());
            }

            inbox.add(conversation);
        }
        return inbox;
    }

    // Get Chat Sessions (for notification system)
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    @GetMapping("/chat-sessions")
    public List<Map<String, String>> getChatSessions() {
        List<Object[]> results = messageRepository.findDistinctConversations();
        List<Map<String, String>> sessions = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, String> session = new HashMap<>();
            session.put("accountId", (String) row[0]);
            session.put("accountName", (String) row[1]);
            session.put("provider", (String) row[2]);
            sessions.add(session);
        }
        return sessions;
    }

    // 2. Get Chat History for a specific Account
    @GetMapping("/chat/{accountId}")
    public List<SupportMessage> getChatHistory(@PathVariable String accountId) {
        List<SupportMessage> unreadMessages = messageRepository.findByAccountIdAndIsUserMessageTrueAndStatus(accountId, "UNREAD");
        if (!unreadMessages.isEmpty()) {
            for (SupportMessage msg : unreadMessages) {
                msg.setStatus("READ");
            }
            messageRepository.saveAll(unreadMessages);
        }
        return messageRepository.findByAccountIdOrderByTimestampAsc(accountId);
    }

    // 3. Send Reply (Admin -> User) — also handles admin-initiated conversations + attachments
    @PostMapping("/reply")
    public SupportMessage sendReply(@RequestBody Map<String, String> payload) {
        String accountId = payload.get("accountId");
        String messageContent = payload.getOrDefault("message", "");
        String payloadAccountName = payload.get("accountName");
        String payloadProvider = payload.get("cloudProvider");

        List<SupportMessage> history = messageRepository.findByAccountIdOrderByTimestampAsc(accountId);

        String accountName;
        String cloudProvider;
        Long userId;

        if (!history.isEmpty()) {
            SupportMessage firstMsg = history.get(0);
            accountName = firstMsg.getAccountName();
            cloudProvider = firstMsg.getCloudProvider();
            userId = firstMsg.getUserId();
        } else if (payloadAccountName != null && payloadProvider != null) {
            // Admin initiating a brand-new conversation
            accountName = payloadAccountName;
            cloudProvider = payloadProvider;
            userId = null;
        } else {
            throw new RuntimeException("Conversation not found");
        }

        SupportMessage reply = new SupportMessage();
        reply.setAccountId(accountId);
        reply.setAccountName(accountName);
        reply.setCloudProvider(cloudProvider);
        reply.setUserId(userId);
        reply.setContent(messageContent);
        reply.setUserMessage(false);
        reply.setTimestamp(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        reply.setStatus("DELIVERED");
        reply.setSenderUsername("BillOps Admin");

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

        return messageRepository.save(reply);
    }

    // 4. Get all cloud accounts available for admin to start conversations with
    // ROLE_BILLOPS_ADMIN is a platform super-admin — sees ALL accounts across all clients
    // Individual client users see only their own accounts (handled separately by client_id filtering)
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    @GetMapping("/accounts")
    public List<Map<String, Object>> getAvailableAccounts() {
        List<CloudAccount> accounts = cloudAccountRepository.findAll();

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
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
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

    private String resolveExternalId(CloudAccount account) {
        String provider = account.getProvider();
        if ("AWS".equalsIgnoreCase(provider)) return account.getAwsAccountId();
        if ("GCP".equalsIgnoreCase(provider)) return account.getGcpProjectId();
        if ("Azure".equalsIgnoreCase(provider)) return account.getAzureSubscriptionId();
        return account.getExternalId();
    }
}
