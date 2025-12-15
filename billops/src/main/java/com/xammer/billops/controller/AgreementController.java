package com.xammer.billops.controller;

import com.xammer.billops.dto.AgreementDto;
import com.xammer.billops.service.AgreementService;
import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/billops/agreements")
public class AgreementController {

    private final AgreementService agreementService;

    public AgreementController(AgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<AgreementDto> uploadAgreement(
            @RequestParam("accountId") String accountIdentifier, // CHANGED from Long to String
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {

        Long userId = ((ClientUserDetails) authentication.getPrincipal()).getId();
        return ResponseEntity.ok(agreementService.uploadAgreement(accountIdentifier, file, userId));
    }

    @PutMapping("/{id}/finalize")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<AgreementDto> finalizeAgreement(@PathVariable Long id) {
        return ResponseEntity.ok(agreementService.finalizeAgreement(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<Void> deleteAgreement(@PathVariable Long id) {
        agreementService.deleteAgreement(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/account/{accountIdentifier}")
    public ResponseEntity<List<AgreementDto>> getAgreements(
            @PathVariable String accountIdentifier, // CHANGED from Long to String
            Authentication authentication) {

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_BILLOPS_ADMIN"));

        return ResponseEntity.ok(agreementService.getAgreementsForAccount(accountIdentifier, isAdmin));
    }
}