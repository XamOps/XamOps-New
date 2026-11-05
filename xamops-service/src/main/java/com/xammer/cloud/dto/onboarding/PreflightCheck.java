package com.xammer.cloud.dto.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One server-side preflight check result (design §6.1, §4.2). {@code result} is one of
 * {@code PASS | WARN | FAIL | SKIPPED | UNKNOWN}. {@code UNKNOWN} on a check that blocks a phase
 * counts as blocking (fail closed).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreflightCheck(
        String id,
        String result,
        List<String> blocksPhases,
        String title,
        String detail,
        Remediation remediation) {

    public record Remediation(String text, String command) {
    }

    public static final String PASS = "PASS";
    public static final String WARN = "WARN";
    public static final String FAIL = "FAIL";
    public static final String SKIPPED = "SKIPPED";
    public static final String UNKNOWN = "UNKNOWN";
}
