package com.xammer.cloud.service.automation.proposal;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The nine proposal statuses (design 3.2). Stored as the enum name in {@code automation_proposal.status}.
 *
 * <p>Liveness is defined by {@code (status, refusal_stage)}, not by status alone: see
 * {@code AutomationProposalRepository#findLive}. {@link #REFUSED} is live only at the PROPOSAL stage.
 */
public enum ProposalStatus {
    PENDING, APPROVED, DISPATCHED, EXECUTED, FAILED, REJECTED, EXPIRED, STALE, REFUSED;

    public static Optional<ProposalStatus> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(s -> s.name().equals(value)).findFirst();
    }

    public static List<String> allNames() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
