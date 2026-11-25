package com.xammer.billops.dto;

import java.time.LocalDateTime;

/**
 * One row of the BillOps admin "Demo Users" view
 * ({@code GET /api/billops/admin/demo-users}): a user of the {@code is_demo=true} client, with
 * their login history derived from {@code user_activity_event} LOGIN rows.
 *
 * <p>The billops {@code AppUser} mapping is minimal (no {@code authProvider}/{@code lastLoginAt}
 * column), so {@code authProvider}, {@code firstSeen}, {@code lastLogin}, {@code loginCount} are all sourced from the aggregated LOGIN rows — no column is added to any billops
 * mapping (NFR2). {@code displayName} is {@code AppUser.username}, which currently equals the email
 * because the auto-provision path sets {@code username = email}; the field is kept because AC-28
 * requires a display-name column in the returned shape.
 *
 * <p>Nullability contract (for a demo-client user with zero LOGIN rows):
 * <ul>
 *   <li>{@code email} — never null ({@code NOT NULL} on {@code app_user}).</li>
 *   <li>{@code displayName} — never null ({@code AppUser.username} is {@code NOT NULL}).</li>
 *   <li>{@code authProvider} — {@code null} when no LOGIN row; otherwise the provider label.</li>
 *   <li>{@code firstSeen} / {@code lastLogin} — {@code null} when no LOGIN row.</li>
 *   <li>{@code loginCount} — {@code 0} when no LOGIN row.</li>
 * </ul>
 */
public record DemoUserDto(
        String email,
        String displayName,
        String authProvider,
        LocalDateTime firstSeen,
        LocalDateTime lastLogin,
        long loginCount
) {
}
