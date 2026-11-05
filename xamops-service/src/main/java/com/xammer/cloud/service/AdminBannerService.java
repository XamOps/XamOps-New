package com.xammer.cloud.service;

import com.xammer.cloud.domain.AdminBanner;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.AdminBannerDto;
import com.xammer.cloud.repository.AdminBannerRepository;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates, lists and deletes announcement banners, and resolves which ones a client sees.
 *
 * <p>
 * Admin scoping follows {@code BillOpsAdminController.getClients}: a BillOps sub-admin
 * ({@code ROLE_BILLOPS_ADMIN_SUB}) works only with the clients they own
 * ({@code client.owner_admin_id}). For them "all accounts" is stored as an explicit list of their
 * own clients, never as {@code target_all}, so a sub-admin cannot reach other admins' customers.
 * They also see and delete only the banners they wrote.
 */
@Service
public class AdminBannerService {

    public static final int MAX_HEADING_LENGTH = 200;
    public static final int MAX_BODY_LENGTH = 5000;
    private static final int MAX_CLIENT_BANNERS = 20;

    private final AdminBannerRepository banners;
    private final ClientRepository clients;
    private final UserRepository users;

    public AdminBannerService(AdminBannerRepository banners, ClientRepository clients, UserRepository users) {
        this.banners = banners;
        this.clients = clients;
        this.users = users;
    }

    /** Thrown when the caller may not act on a banner or client. Mapped to 403. */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    /** Who is calling and which clients they may target. {@code null} ownedClientIds = every client. */
    private record AdminScope(String username, Long userId, Set<Long> ownedClientIds) {
        boolean isSubAdmin() {
            return ownedClientIds != null;
        }
    }

    // ── Admin side ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminBannerDto.ClientOption> clientOptions() {
        AdminScope scope = currentScope();
        List<Client> visible = scope.isSubAdmin()
                ? clients.findAllById(scope.ownedClientIds())
                : clients.findAll();
        return visible.stream()
                .map(c -> new AdminBannerDto.ClientOption(c.getId(), c.getName()))
                .sorted(Comparator.comparing(AdminBannerDto.ClientOption::name,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AdminBannerDto.AdminView> list() {
        AdminScope scope = currentScope();
        List<AdminBanner> rows = scope.isSubAdmin()
                ? (scope.userId() == null ? List.of()
                        : banners.findByCreatedByUserIdOrderByCreatedAtDesc(scope.userId()))
                : banners.findAllByOrderByCreatedAtDesc();

        // One lookup for every client named by any banner, rather than one per banner.
        Set<Long> allTargetIds = rows.stream()
                .flatMap(b -> b.getTargetClientIds().stream())
                .collect(Collectors.toSet());
        Map<Long, String> names = allTargetIds.isEmpty() ? Map.of()
                : clients.findAllById(allTargetIds).stream()
                        .collect(Collectors.toMap(Client::getId,
                                c -> c.getName() != null ? c.getName() : "Client " + c.getId()));

        return rows.stream().map(b -> toAdminView(b, names)).collect(Collectors.toList());
    }

    @Transactional
    public AdminBannerDto.AdminView create(AdminBannerDto.CreateRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        String heading = req.heading() == null ? "" : req.heading().trim();
        String body = req.body() == null ? "" : req.body().trim();
        if (heading.isEmpty()) {
            throw new IllegalArgumentException("Heading is required.");
        }
        if (heading.length() > MAX_HEADING_LENGTH) {
            throw new IllegalArgumentException("Heading must be at most " + MAX_HEADING_LENGTH + " characters.");
        }
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Body is required.");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("Body must be at most " + MAX_BODY_LENGTH + " characters.");
        }

        AdminScope scope = currentScope();
        boolean wantsAll = Boolean.TRUE.equals(req.targetAll());

        AdminBanner banner = new AdminBanner();
        banner.setHeading(heading);
        banner.setBody(body);
        banner.setCreatedBy(scope.username());
        banner.setCreatedByUserId(scope.userId());
        banner.setCreatedAt(Instant.now());

        if (wantsAll && !scope.isSubAdmin()) {
            banner.setTargetAll(true);
        } else {
            Set<Long> targets = wantsAll
                    ? new HashSet<>(scope.ownedClientIds())
                    : resolveRequestedTargets(req.clientIds(), scope);
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("Select at least one account to show this banner to.");
            }
            banner.setTargetAll(false);
            banner.setTargetClientIds(targets);
        }

        AdminBanner saved = banners.save(banner);
        Map<Long, String> names = saved.getTargetClientIds().isEmpty() ? Map.of()
                : clients.findAllById(saved.getTargetClientIds()).stream()
                        .collect(Collectors.toMap(Client::getId,
                                c -> c.getName() != null ? c.getName() : "Client " + c.getId()));
        return toAdminView(saved, names);
    }

    @Transactional
    public void delete(Long id) {
        AdminBanner banner = banners.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Banner " + id + " not found."));
        AdminScope scope = currentScope();
        if (scope.isSubAdmin() && !Objects.equals(banner.getCreatedByUserId(), scope.userId())) {
            throw new ForbiddenException("You can only delete banners you created.");
        }
        banners.delete(banner);
    }

    // ── Client side ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminBannerDto.ClientView> visibleTo(Long clientId) {
        if (clientId == null) {
            return List.of();
        }
        return banners.findVisibleToClient(clientId).stream()
                .limit(MAX_CLIENT_BANNERS)
                .map(b -> new AdminBannerDto.ClientView(b.getId(), b.getHeading(), b.getBody(), b.getCreatedAt()))
                .collect(Collectors.toList());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Keeps only ids that exist and that the caller may target. An id outside the caller's scope
     * is an error rather than silently dropped, so the admin is never told a banner went to
     * accounts it did not reach.
     */
    private Set<Long> resolveRequestedTargets(List<Long> requested, AdminScope scope) {
        if (requested == null || requested.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = requested.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (scope.isSubAdmin() && !scope.ownedClientIds().containsAll(ids)) {
            throw new ForbiddenException("You can only send banners to accounts you manage.");
        }
        Set<Long> existing = clients.findAllById(ids).stream().map(Client::getId).collect(Collectors.toSet());
        if (!existing.containsAll(ids)) {
            throw new IllegalArgumentException("One or more selected accounts no longer exist. Refresh and try again.");
        }
        return ids;
    }

    private AdminBannerDto.AdminView toAdminView(AdminBanner b, Map<Long, String> names) {
        List<AdminBannerDto.ClientOption> targets = b.getTargetClientIds().stream()
                .map(id -> new AdminBannerDto.ClientOption(id, names.getOrDefault(id, "Client " + id)))
                .sorted(Comparator.comparing(AdminBannerDto.ClientOption::name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        return new AdminBannerDto.AdminView(b.getId(), b.getHeading(), b.getBody(), b.isTargetAll(),
                targets, b.getCreatedBy(), b.getCreatedAt());
    }

    private AdminScope currentScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new ForbiddenException("Not authenticated.");
        }
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        boolean fullAdmin = authorities.contains("ROLE_BILLOPS_ADMIN") || authorities.contains("ROLE_SUPER_ADMIN");
        boolean subAdmin = !fullAdmin && authorities.contains("ROLE_BILLOPS_ADMIN_SUB");

        String username = auth.getName();
        Long userId = auth.getPrincipal() instanceof ClientUserDetails cud ? cud.getId() : null;

        if (!subAdmin) {
            return new AdminScope(username, userId, null);
        }

        // Same lookup BillOpsAdminController uses to scope a sub-admin to their clients.
        if (userId == null) {
            userId = users.findByUsername(username).map(User::getId).orElse(null);
        }
        if (userId == null) {
            return new AdminScope(username, null, Set.of());
        }
        Set<Long> owned = clients.findByOwnerAdminId(userId).stream()
                .map(Client::getId)
                .collect(Collectors.toSet());
        return new AdminScope(username, userId, owned);
    }
}
