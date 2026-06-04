package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.domain.CspBillingConfig;
import com.xammer.cloud.dto.gcp.CspBillingConfigDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CspBillingConfigRepository;
import com.xammer.cloud.service.gcp.CspBillingIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/xamops/gcp/csp-billing")
@RequiredArgsConstructor
public class CspBillingConfigController {

    private final CspBillingConfigRepository configRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final CspBillingIngestionService ingestionService;

    @GetMapping("/configs")
    public ResponseEntity<List<CspBillingConfigDto>> listConfigs() {
        List<CspBillingConfigDto> dtos = configRepository.findAll().stream()
            .map(this::toDtoWithoutKey)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/configs")
    public ResponseEntity<CspBillingConfigDto> createConfig(@RequestBody CspBillingConfigDto dto) {
        CspBillingConfig saved = configRepository.save(fromDto(dto));
        return ResponseEntity.ok(toDtoWithoutKey(saved));
    }

    @PutMapping("/configs/{id}")
    public ResponseEntity<CspBillingConfigDto> updateConfig(
            @PathVariable Long id, @RequestBody CspBillingConfigDto dto) {
        return configRepository.findById(id)
            .map(config -> {
                config.setName(dto.getName());
                config.setGcpProjectId(dto.getGcpProjectId());
                config.setDatasetId(dto.getDatasetId());
                config.setTableId(dto.getTableId());
                if (dto.getServiceAccountKey() != null && !dto.getServiceAccountKey().isBlank()) {
                    config.setServiceAccountKey(dto.getServiceAccountKey());
                }
                return ResponseEntity.ok(toDtoWithoutKey(configRepository.save(config)));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/configs/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        if (cloudAccountRepository.existsByCspBillingConfigId(id)) {
            return ResponseEntity.badRequest().build();
        }
        configRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sync/{accountId}")
    public ResponseEntity<String> syncAccount(@PathVariable Long accountId) {
        return cloudAccountRepository.findById(accountId)
            .map(account -> {
                if (!account.isCspManaged()) {
                    return ResponseEntity.badRequest().body("Account is not CSP-managed");
                }
                ingestionService.ingestForAccount(account);
                return ResponseEntity.ok("Sync triggered for " + account.getGcpProjectId());
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sync-all")
    public ResponseEntity<String> syncAll() {
        ingestionService.ingestAll();
        return ResponseEntity.ok("Sync triggered for all CSP-managed accounts");
    }

    private CspBillingConfigDto toDtoWithoutKey(CspBillingConfig config) {
        CspBillingConfigDto dto = new CspBillingConfigDto();
        dto.setId(config.getId());
        dto.setName(config.getName());
        dto.setGcpProjectId(config.getGcpProjectId());
        dto.setDatasetId(config.getDatasetId());
        dto.setTableId(config.getTableId());
        // serviceAccountKey intentionally omitted from list/read responses
        return dto;
    }

    private CspBillingConfig fromDto(CspBillingConfigDto dto) {
        CspBillingConfig config = new CspBillingConfig();
        config.setName(dto.getName());
        config.setGcpProjectId(dto.getGcpProjectId());
        config.setDatasetId(dto.getDatasetId());
        config.setTableId(dto.getTableId());
        config.setServiceAccountKey(dto.getServiceAccountKey());
        return config;
    }
}
