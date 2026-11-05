package com.xammer.cloud.controller;

import com.xammer.cloud.dto.AdminBannerDto;
import com.xammer.cloud.service.AdminBannerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * BillOps admin management of the announcement banners shown on XamOps client dashboards.
 * Same prefix and role gate as {@link BillOpsAdminController}; sub-admin scoping is in
 * {@link AdminBannerService}.
 */
@RestController
@RequestMapping("/api/xamops/billops-admin/banners")
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_BILLOPS_ADMIN_SUB') or hasAuthority('ROLE_SUPER_ADMIN')")
public class AdminBannerController {

    private final AdminBannerService service;

    public AdminBannerController(AdminBannerService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminBannerDto.AdminView> list() {
        return service.list();
    }

    /** Accounts (clients) the caller can target, for the picker in the create dialog. */
    @GetMapping("/client-options")
    public List<AdminBannerDto.ClientOption> clientOptions() {
        return service.clientOptions();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody AdminBannerDto.CreateRequest body) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (AdminBannerService.ForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (AdminBannerService.ForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }
}
