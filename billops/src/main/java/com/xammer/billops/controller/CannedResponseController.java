package com.xammer.billops.controller;

import com.xammer.billops.dto.CannedResponseDto;
import com.xammer.billops.repository.CannedResponseRepository;
import com.xammer.billops.util.TicketHtmlSanitizer;
import com.xammer.cloud.domain.CannedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CRUD for the shared library of canned (saved) reply templates. Admin-only.
 */
@RestController
@RequestMapping("/api/billops/canned-responses")
@PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
public class CannedResponseController {

    private static final Logger logger = LoggerFactory.getLogger(CannedResponseController.class);

    private final CannedResponseRepository repository;

    public CannedResponseController(CannedResponseRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<CannedResponseDto>> list() {
        return ResponseEntity.ok(repository.findAllByOrderByTitleAsc().stream()
                .map(this::toDto).collect(Collectors.toList()));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<CannedResponseDto> create(@RequestBody CannedResponseDto payload, Authentication auth) {
        if (payload.getTitle() == null || payload.getTitle().isBlank()
                || payload.getBody() == null || payload.getBody().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        CannedResponse cr = new CannedResponse();
        cr.setTitle(payload.getTitle().trim());
        // Bodies are rich text and get inserted into reply drafts, so sanitize on write.
        cr.setBody(TicketHtmlSanitizer.sanitize(payload.getBody()));
        cr.setCreatedBy(auth != null ? auth.getName() : null);
        cr.setCreatedAt(LocalDateTime.now());
        cr.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(toDto(repository.save(cr)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<CannedResponseDto> update(@PathVariable Long id, @RequestBody CannedResponseDto payload) {
        return repository.findById(id).map(cr -> {
            if (payload.getTitle() != null && !payload.getTitle().isBlank()) cr.setTitle(payload.getTitle().trim());
            if (payload.getBody() != null) cr.setBody(TicketHtmlSanitizer.sanitize(payload.getBody()));
            cr.setUpdatedAt(LocalDateTime.now());
            return ResponseEntity.ok(toDto(repository.save(cr)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private CannedResponseDto toDto(CannedResponse cr) {
        CannedResponseDto dto = new CannedResponseDto();
        dto.setId(cr.getId());
        dto.setTitle(cr.getTitle());
        dto.setBody(cr.getBody());
        dto.setCreatedBy(cr.getCreatedBy());
        dto.setCreatedAt(cr.getCreatedAt());
        dto.setUpdatedAt(cr.getUpdatedAt());
        return dto;
    }
}
