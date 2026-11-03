package com.xammer.billops.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One platform-wide XamOps page rule as the BillOps admin User Manager shows it.
 *
 * @param tabKey     XamOps module or tab key (frontend constants/xamopsTabs.ts)
 * @param mode       {@code HIDE} or {@code GRANT}
 * @param exceptions users the rule does not apply to
 */
public record GlobalTabRuleDTO(
        String tabKey,
        String mode,
        String updatedBy,
        LocalDateTime updatedAt,
        List<ExceptionUser> exceptions) {

    public record ExceptionUser(Long userId, String username, String email, String clientName) {}

    /** GET response: the rules plus whether the caller may change them. */
    public record ListResponse(boolean canEdit, List<GlobalTabRuleDTO> rules) {}

    /** PUT body. */
    public record ModeRequest(String mode) {}

    /** POST body for adding an exception. */
    public record ExceptionRequest(Long userId) {}
}
