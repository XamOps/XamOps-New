package com.xammer.billops.controller;

import com.xammer.billops.domain.ClientIntakeSubmission;
import com.xammer.billops.dto.ClientIntakeRequestDto;
import com.xammer.billops.dto.ClientIntakeResponseDto;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientIntakeRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.security.ClientUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/billops/admin/client-intake")
@CrossOrigin(
    origins = { "http://localhost:5173", "http://127.0.0.1:5500",
                "https://uat.xamops.com", "https://live.xamops.com", "https://prep.xamops.com" },
    methods = { RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS },
    allowCredentials = "true"
)
public class ClientIntakeController {

    private static final Logger logger = LoggerFactory.getLogger(ClientIntakeController.class);

    private final ClientIntakeRepository repository;
    private final AppUserRepository appUserRepository;

    public ClientIntakeController(ClientIntakeRepository repository, AppUserRepository appUserRepository) {
        this.repository = repository;
        this.appUserRepository = appUserRepository;
    }

    /**
     * POST /api/billops/admin/client-intake
     * Called by the onboarding modal when a new XamOps user submits their details.
     * Any authenticated user may call this.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@RequestBody ClientIntakeRequestDto request) {
        logger.info("New client intake submission from: {}", request.getEmail());

        ClientIntakeSubmission submission = new ClientIntakeSubmission();
        submission.setName(request.getName());
        submission.setCompanyName(request.getCompanyName());
        submission.setEmail(request.getEmail());
        submission.setPhone(request.getPhone());
        submission.setAddress(request.getAddress());
        submission.setNotes(request.getNotes());
        submission.setAccountIdsRaw(
            request.getAccountIds() != null && !request.getAccountIds().isEmpty()
                ? String.join(",", request.getAccountIds())
                : ""
        );
        submission.setSubmittedAt(LocalDateTime.now());
        submission.setStatus(request.isOptedOut() ? "OPTED_OUT" : "PENDING");

        repository.save(submission);
        return ResponseEntity.ok(Map.of("message", "Submitted successfully"));
    }

    /**
     * GET /api/billops/admin/client-intake/has-submitted
     * Returns whether the current user (or anyone in their org) has already submitted.
     * Used by the frontend to skip the onboarding modal across devices and sub-users.
     */
    @GetMapping("/has-submitted")
    public ResponseEntity<Map<String, Boolean>> hasSubmitted(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ClientUserDetails)) {
            return ResponseEntity.ok(Map.of("submitted", false));
        }

        ClientUserDetails principal = (ClientUserDetails) authentication.getPrincipal();

        // Look up the AppUser to get their email
        Optional<AppUser> userOpt = appUserRepository.findById(principal.getId());
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("submitted", false));
        }

        String email = userOpt.get().getEmail();

        // 1. Check if this exact email has already submitted
        if (repository.existsByEmail(email)) {
            return ResponseEntity.ok(Map.of("submitted", true));
        }

        // 2. Check org-wide: if any user under the same clientId has submitted
        Long clientId = principal.getClientId();
        if (clientId != null) {
            List<String> orgEmails = appUserRepository.findByClientId(clientId)
                    .stream()
                    .map(AppUser::getEmail)
                    .collect(Collectors.toList());
            if (!orgEmails.isEmpty() && repository.existsByEmailIn(orgEmails)) {
                return ResponseEntity.ok(Map.of("submitted", true));
            }
        }

        return ResponseEntity.ok(Map.of("submitted", false));
    }

    /**
     * GET /api/billops/admin/client-intake/all
     * Returns all submissions, newest first. Admin-only.
     */
    @GetMapping("/all")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<List<ClientIntakeResponseDto>> getAllSubmissions() {
        List<ClientIntakeResponseDto> result = repository.findAllByOrderBySubmittedAtDesc()
                .stream()
                .map(ClientIntakeResponseDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }
}
