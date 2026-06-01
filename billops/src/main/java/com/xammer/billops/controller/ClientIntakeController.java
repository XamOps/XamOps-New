package com.xammer.billops.controller;

import com.xammer.billops.domain.ClientIntakeSubmission;
import com.xammer.billops.dto.ClientIntakeRequestDto;
import com.xammer.billops.dto.ClientIntakeResponseDto;
import com.xammer.billops.repository.ClientIntakeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    public ClientIntakeController(ClientIntakeRepository repository) {
        this.repository = repository;
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
        submission.setStatus("PENDING");

        repository.save(submission);
        return ResponseEntity.ok(Map.of("message", "Submitted successfully"));
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
