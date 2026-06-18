package com.xammer.cloud.service;

import org.springframework.stereotype.Component;

@Component
public class FailureCategoryResolver {

    public String resolve(String jobName, String jenkinsStatus) {
        if ("UNSTABLE".equalsIgnoreCase(jenkinsStatus)) return "TEST_FAILURE";
        if (jobName == null) return "UNKNOWN";
        String lower = jobName.toLowerCase();
        if (lower.contains("test") || lower.contains("spec")) return "TEST_FAILURE";
        if (lower.contains("build") || lower.contains("compile")) return "BUILD_ERROR";
        if (lower.contains("deploy") || lower.contains("release")) return "DEPLOY_FAILURE";
        return "UNKNOWN";
    }
}
