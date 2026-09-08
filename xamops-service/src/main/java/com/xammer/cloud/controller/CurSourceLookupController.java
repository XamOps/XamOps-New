package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CurSourceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only lookup of the payer reports an account can be attached to, for the
 * onboarding flow.
 *
 * <p>Separate from {@link CurSourceAdminController} because that one is
 * {@code ROLE_SUPER_ADMIN} for the whole class — registering a report, assigning
 * accounts and reconciling against Cost Explorer all change or expose real billing
 * state. But someone connecting an AWS account has to be able to *see* which payer
 * reports exist in order to pick one, and they are not necessarily an admin. Without
 * this split the onboarding page 403s and the payer option is silently unavailable.
 *
 * <p>Returns only the three fields the picker renders — id, name and owning account
 * — rather than the whole entity: bucket names, prefixes, Athena locations and role
 * ARNs are operational detail that a non-admin has no reason to receive.
 */
@RestController
@RequestMapping("/api/xamops/cur-sources")
public class CurSourceLookupController {

    private final CurSourceRepository curSourceRepository;

    public CurSourceLookupController(CurSourceRepository curSourceRepository) {
        this.curSourceRepository = curSourceRepository;
    }

    /**
     * XamOps payer reports that are usable right now.
     *
     * <p>Filtered to ACTIVE and XamOps-owned: a PENDING or FAILED source has no
     * queryable table behind it, and offering it would let someone attach an account
     * to a report that cannot answer a query — which renders as $0 rather than an
     * error.
     */
    @GetMapping("/available")
    public ResponseEntity<List<Map<String, Object>>> available() {
        List<Map<String, Object>> payers = curSourceRepository.findByStatus("ACTIVE").stream()
                .filter(CurSource::isXamopsOwned)
                .map(source -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", source.getId());
                    row.put("name", source.getName());
                    row.put("ownerAccountId", source.getOwnerAccountId());
                    return row;
                })
                .toList();
        return ResponseEntity.ok(payers);
    }
}
