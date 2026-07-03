package com.xammer.cloud.controller;

import com.xammer.cloud.domain.TrialFeedback;
import com.xammer.cloud.repository.TrialFeedbackRepository;
import com.xammer.cloud.security.ClientUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * In-app feedback. Public path (/api/auth/feedback) so it is reachable from the
 * last-day email link whether or not the user still has an active session.
 * When a session exists, the feedback is associated with the user/client.
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class FeedbackController {

    @Autowired
    private TrialFeedbackRepository feedbackRepository;

    @PostMapping("/feedback")
    public ResponseEntity<?> submit(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "").trim();
        if (message.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Feedback message is required"));
        }

        TrialFeedback fb = new TrialFeedback();
        fb.setMessage(message);

        String ratingRaw = body.get("rating");
        if (ratingRaw != null && !ratingRaw.isBlank()) {
            try { fb.setRating(Integer.parseInt(ratingRaw.trim())); } catch (NumberFormatException ignored) {}
        }
        fb.setEmail(body.get("email"));

        ClientUserDetails principal = currentPrincipal();
        if (principal != null) {
            fb.setUserId(principal.getId());
            fb.setClientId(principal.getClientId());
            if (fb.getEmail() == null) fb.setEmail(principal.getUsername());
        }

        feedbackRepository.save(fb);
        log.info("Trial feedback received (clientId={}, userId={})", fb.getClientId(), fb.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "Thank you for your feedback."));
    }

    private ClientUserDetails currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ClientUserDetails) {
            return (ClientUserDetails) auth.getPrincipal();
        }
        return null;
    }
}
