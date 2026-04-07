package com.xammer.cloud.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.DirectoryScopes;
import com.google.api.services.directory.model.User;
import com.google.api.services.directory.model.Users;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.xammer.cloud.domain.WorkspaceConnection;
import com.xammer.cloud.domain.WorkspaceUser;
import com.xammer.cloud.repository.WorkspaceConnectionRepository;
import com.xammer.cloud.repository.WorkspaceUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WorkspaceService integrates with the Google Admin Directory API using a
 * service account with domain-wide delegation.
 *
 * Required Google Workspace Admin Console setup (per-tenant):
 *   1. Create a GCP Service Account and download the JSON key.
 *   2. Enable domain-wide delegation on that service account.
 *   3. In Workspace Admin Console → Security → API Controls → Domain-wide delegation,
 *      add the service account client ID with these OAuth scopes:
 *        - https://www.googleapis.com/auth/admin.directory.user.readonly
 */
@Service
@Slf4j
public class WorkspaceService {

    private static final String APP_NAME = "XamOps Workspace Manager";
    private static final int INACTIVE_DAYS_THRESHOLD = 30;

    private final WorkspaceConnectionRepository connectionRepository;
    private final WorkspaceUserRepository userRepository;

    public WorkspaceService(WorkspaceConnectionRepository connectionRepository,
                            WorkspaceUserRepository userRepository) {
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
    }

    // ─── Connect ─────────────────────────────────────────────────────────────

    /**
     * Validates the service account key by making a test API call, then persists
     * the connection and triggers an initial sync.
     *
     * @param clientId          XamOps client/tenant ID
     * @param domain            Google Workspace primary domain
     * @param adminEmail        Admin email to impersonate (domain-wide delegation)
     * @param serviceAccountKey Full service account JSON key content
     * @return The saved (and synced) WorkspaceConnection
     */
    @Transactional
    public WorkspaceConnection connect(Long clientId, String domain,
                                       String adminEmail, String serviceAccountKey) {
        // Delete any existing connection for this client
        connectionRepository.findByClientId(clientId).ifPresent(existing -> {
            userRepository.deleteByConnectionId(existing.getId());
            connectionRepository.delete(existing);
        });

        WorkspaceConnection conn = new WorkspaceConnection();
        conn.setClientId(clientId);
        conn.setDomain(domain);
        conn.setAdminEmail(adminEmail);
        conn.setServiceAccountKey(serviceAccountKey);
        conn.setStatus("DISCONNECTED");
        conn.setCreatedAt(LocalDateTime.now());
        conn = connectionRepository.save(conn);

        // Validate credentials and run initial sync
        try {
            Directory directory = buildDirectoryService(serviceAccountKey, adminEmail);
            // Quick validation — list first page of users
            directory.users().list().setDomain(domain).setMaxResults(1).execute();

            conn.setStatus("CONNECTED");
            conn.setErrorMessage(null);
            conn = connectionRepository.save(conn);

            // Full sync
            syncUsers(conn, directory);

        } catch (Exception e) {
            log.error("Workspace connection failed for client {} domain {}: {}", clientId, domain, e.getMessage());
            conn.setStatus("ERROR");
            conn.setErrorMessage(e.getMessage());
            connectionRepository.save(conn);
            throw new RuntimeException("Failed to connect Google Workspace: " + e.getMessage(), e);
        }

        return connectionRepository.findById(conn.getId()).orElse(conn);
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    /**
     * Re-syncs all users from Google Admin SDK for the given client.
     */
    @Transactional
    public Map<String, Object> sync(Long clientId) {
        WorkspaceConnection conn = requireConnection(clientId);

        try {
            Directory directory = buildDirectoryService(
                    conn.getServiceAccountKey(), conn.getAdminEmail());
            syncUsers(conn, directory);
            conn.setStatus("CONNECTED");
            conn.setErrorMessage(null);
            connectionRepository.save(conn);
        } catch (Exception e) {
            log.error("Workspace sync failed for client {}: {}", clientId, e.getMessage());
            conn.setStatus("ERROR");
            conn.setErrorMessage(e.getMessage());
            connectionRepository.save(conn);
            throw new RuntimeException("Sync failed: " + e.getMessage(), e);
        }

        WorkspaceConnection updated = connectionRepository.findByClientId(clientId).orElse(conn);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SYNCED");
        result.put("domain", updated.getDomain());
        result.put("totalUsers", updated.getTotalUsers());
        result.put("activeUsers", updated.getActiveUsers());
        result.put("inactiveUsers", updated.getInactiveUsers());
        result.put("suspendedUsers", updated.getSuspendedUsers());
        result.put("syncedAt", updated.getLastSyncedAt().toString());
        return result;
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    public Map<String, Object> getStatus(Long clientId) {
        Optional<WorkspaceConnection> opt = connectionRepository.findByClientId(clientId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (opt.isEmpty()) {
            result.put("connected", false);
            return result;
        }
        WorkspaceConnection conn = opt.get();
        result.put("connected", "CONNECTED".equals(conn.getStatus()));
        result.put("domain", conn.getDomain());
        result.put("adminEmail", conn.getAdminEmail());
        result.put("status", conn.getStatus());
        result.put("totalUsers", conn.getTotalUsers());
        result.put("activeUsers", conn.getActiveUsers());
        result.put("inactiveUsers", conn.getInactiveUsers());
        result.put("suspendedUsers", conn.getSuspendedUsers());
        result.put("lastSyncedAt", conn.getLastSyncedAt() != null ? conn.getLastSyncedAt().toString() : null);
        result.put("errorMessage", conn.getErrorMessage());
        return result;
    }

    // ─── Users ────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getUsers(Long clientId, String statusFilter) {
        WorkspaceConnection conn = requireConnection(clientId);
        List<WorkspaceUser> users;
        if (statusFilter != null && !statusFilter.equalsIgnoreCase("ALL")) {
            users = userRepository.findByConnectionIdAndUserStatus(conn.getId(), statusFilter.toUpperCase());
        } else {
            users = userRepository.findByConnectionIdOrderByPrimaryEmailAsc(conn.getId());
        }
        return users.stream().map(this::toUserDto).collect(Collectors.toList());
    }

    // ─── License Summary ──────────────────────────────────────────────────────

    public Map<String, Object> getLicenseSummary(Long clientId) {
        WorkspaceConnection conn = requireConnection(clientId);
        int total      = conn.getTotalUsers()    != null ? conn.getTotalUsers()    : 0;
        int active     = conn.getActiveUsers()   != null ? conn.getActiveUsers()   : 0;
        int inactive   = conn.getInactiveUsers() != null ? conn.getInactiveUsers() : 0;
        int suspended  = conn.getSuspendedUsers()!= null ? conn.getSuspendedUsers(): 0;

        long neverLoggedIn = userRepository.findByConnectionId(conn.getId()).stream()
                .filter(u -> u.getLastLoginTime() == null)
                .count();

        double utilization = total > 0 ? (active * 100.0 / total) : 0.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSeats",        total);
        result.put("activeSeats",       active);
        result.put("inactiveSeats",     inactive);
        result.put("suspendedSeats",    suspended);
        result.put("utilizationPercent", Math.round(utilization * 10.0) / 10.0);
        result.put("neverLoggedIn",     neverLoggedIn);
        return result;
    }

    // ─── Optimization Recommendations ─────────────────────────────────────────

    public List<Map<String, Object>> getOptimization(Long clientId, int inactiveDays) {
        WorkspaceConnection conn = requireConnection(clientId);
        List<WorkspaceUser> users = userRepository.findByConnectionId(conn.getId());

        List<Map<String, Object>> recommendations = new ArrayList<>();

        for (WorkspaceUser u : users) {
            Map<String, Object> item = null;

            if ("SUSPENDED".equals(u.getUserStatus())) {
                item = buildRecommendation(u, "SUSPENDED_WITH_LICENSE",
                        "Suspended account is still occupying a license seat — consider removing it.");
            } else if (u.getLastLoginTime() == null) {
                item = buildRecommendation(u, "NEVER_LOGGED_IN",
                        "User has never logged in — licence seat may be unused.");
            } else if (u.getDaysSinceLogin() != null && u.getDaysSinceLogin() >= inactiveDays) {
                item = buildRecommendation(u, "INACTIVE_USER",
                        "Inactive for " + u.getDaysSinceLogin() + " days — consider revoking or downgrading licence.");
            }

            if (item != null) {
                recommendations.add(item);
            }
        }

        // Sort: suspended first, then by days inactive descending
        recommendations.sort((a, b) -> {
            String typeA = (String) a.get("type");
            String typeB = (String) b.get("type");
            if ("SUSPENDED_WITH_LICENSE".equals(typeA) && !"SUSPENDED_WITH_LICENSE".equals(typeB)) return -1;
            if (!"SUSPENDED_WITH_LICENSE".equals(typeA) && "SUSPENDED_WITH_LICENSE".equals(typeB)) return 1;
            Integer daysA = (Integer) a.get("daysSinceLogin");
            Integer daysB = (Integer) b.get("daysSinceLogin");
            if (daysA == null && daysB == null) return 0;
            if (daysA == null) return -1;
            if (daysB == null) return 1;
            return Integer.compare(daysB, daysA);
        });

        return recommendations;
    }

    // ─── Disconnect ───────────────────────────────────────────────────────────

    @Transactional
    public void disconnect(Long clientId) {
        WorkspaceConnection conn = requireConnection(clientId);
        userRepository.deleteByConnectionId(conn.getId());
        connectionRepository.delete(conn);
        log.info("Workspace disconnected for client {}", clientId);
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private void syncUsers(WorkspaceConnection conn, Directory directory) throws Exception {
        log.info("Syncing workspace users for domain {} (connection {})", conn.getDomain(), conn.getId());

        // Fetch all users via paginated API
        List<User> googleUsers = new ArrayList<>();
        String pageToken = null;
        do {
            Users response = directory.users().list()
                    .setDomain(conn.getDomain())
                    .setMaxResults(500)
                    .setProjection("full")
                    .setOrderBy("email")
                    .setPageToken(pageToken)
                    .execute();

            if (response.getUsers() != null) {
                googleUsers.addAll(response.getUsers());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null);

        log.info("Fetched {} users from Google for domain {}", googleUsers.size(), conn.getDomain());

        // Drop existing cached users
        userRepository.deleteByConnectionId(conn.getId());

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<WorkspaceUser> toSave = new ArrayList<>();

        int active = 0, inactive = 0, suspended = 0;

        for (User gu : googleUsers) {
            WorkspaceUser wu = new WorkspaceUser();
            wu.setConnectionId(conn.getId());
            wu.setPrimaryEmail(gu.getPrimaryEmail() != null ? gu.getPrimaryEmail() : "");
            wu.setFullName(gu.getName() != null ? gu.getName().getFullName() : "");
            wu.setOrgUnitPath(gu.getOrgUnitPath() != null ? gu.getOrgUnitPath() : "/");
            wu.setSuspended(Boolean.TRUE.equals(gu.getSuspended()));
            wu.setAdmin(Boolean.TRUE.equals(gu.getIsAdmin()));
            wu.setSyncedAt(now);

            // Parse creation time
            if (gu.getCreationTime() != null && gu.getCreationTime().getValue() > 0) {
                wu.setCreatedTime(toLocalDateTime(gu.getCreationTime().getValue()));
            }

            // Parse last login time (epoch 0 = never logged in)
            LocalDateTime lastLogin = null;
            if (gu.getLastLoginTime() != null && gu.getLastLoginTime().getValue() > 86400000L) {
                lastLogin = toLocalDateTime(gu.getLastLoginTime().getValue());
                wu.setLastLoginTime(lastLogin);
                long days = ChronoUnit.DAYS.between(lastLogin, now);
                wu.setDaysSinceLogin((int) days);
            }

            // Derive status
            if (wu.isSuspended()) {
                wu.setUserStatus("SUSPENDED");
                suspended++;
            } else if (lastLogin == null || wu.getDaysSinceLogin() >= INACTIVE_DAYS_THRESHOLD) {
                wu.setUserStatus("INACTIVE");
                inactive++;
            } else {
                wu.setUserStatus("ACTIVE");
                active++;
            }

            toSave.add(wu);
        }

        userRepository.saveAll(toSave);

        // Update cached counts on the connection
        conn.setTotalUsers(toSave.size());
        conn.setActiveUsers(active);
        conn.setInactiveUsers(inactive);
        conn.setSuspendedUsers(suspended);
        conn.setLastSyncedAt(now);
        connectionRepository.save(conn);

        log.info("Workspace sync complete: total={} active={} inactive={} suspended={}",
                toSave.size(), active, inactive, suspended);
    }

    private Directory buildDirectoryService(String serviceAccountKeyJson, String adminEmail) throws Exception {
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new ByteArrayInputStream(serviceAccountKeyJson.getBytes(StandardCharsets.UTF_8)))
                .createScoped(Collections.singletonList(DirectoryScopes.ADMIN_DIRECTORY_USER_READONLY));

        // Impersonate the admin user (domain-wide delegation)
        credentials = credentials.createDelegated(adminEmail);

        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();

        return new Directory.Builder(transport, GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(APP_NAME)
                .build();
    }

    private WorkspaceConnection requireConnection(Long clientId) {
        return connectionRepository.findByClientId(clientId)
                .orElseThrow(() -> new IllegalStateException(
                        "No Google Workspace connection found for this account. Please connect first."));
    }

    private Map<String, Object> toUserDto(WorkspaceUser u) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("email",          u.getPrimaryEmail());
        dto.put("fullName",       u.getFullName());
        dto.put("orgUnit",        u.getOrgUnitPath());
        dto.put("status",         u.getUserStatus());
        dto.put("lastLoginTime",  u.getLastLoginTime() != null ? u.getLastLoginTime().toString() : null);
        dto.put("daysSinceLogin", u.getDaysSinceLogin());
        dto.put("isAdmin",        u.isAdmin());
        return dto;
    }

    private Map<String, Object> buildRecommendation(WorkspaceUser u, String type, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type",           type);
        item.put("email",          u.getPrimaryEmail());
        item.put("fullName",       u.getFullName());
        item.put("orgUnit",        u.getOrgUnitPath());
        item.put("daysSinceLogin", u.getDaysSinceLogin());
        item.put("recommendation", message);
        return item;
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
}
