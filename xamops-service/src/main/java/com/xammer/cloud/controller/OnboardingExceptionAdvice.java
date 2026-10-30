package com.xammer.cloud.controller;

import com.xammer.cloud.service.onboarding.OnboardingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link OnboardingException} to {@code { "error": CODE, "message": "...", "remediation": ... }}
 * with the exception's HTTP status (design §4). Scoped to the onboarding and connect controllers.
 */
@ControllerAdvice(assignableTypes = {K8sOnboardingController.class, AgentConnectController.class})
public class OnboardingExceptionAdvice {

    @ExceptionHandler(OnboardingException.class)
    public ResponseEntity<Map<String, Object>> handle(OnboardingException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getCode());
        body.put("message", e.getMessage());
        if (e.getRemediation() != null) {
            body.put("remediation", e.getRemediation());
        }
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
