package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CspBillingConfig;
import com.xammer.cloud.dto.gcp.CspBillingConfigDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.CspBillingConfigRepository;
import com.xammer.cloud.service.gcp.CspBillingIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CspBillingConfigControllerTest {

    @Mock CspBillingConfigRepository configRepository;
    @Mock CloudAccountRepository cloudAccountRepository;
    @Mock CspBillingIngestionService ingestionService;

    CspBillingConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new CspBillingConfigController(configRepository, cloudAccountRepository, ingestionService);
    }

    @Test
    void listConfigs_returnsEmptyList() {
        when(configRepository.findAll()).thenReturn(List.of());

        ResponseEntity<List<CspBillingConfigDto>> response = controller.listConfigs();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void listConfigs_omitsServiceAccountKey() {
        CspBillingConfig config = new CspBillingConfig();
        config.setId(1L); config.setName("Sisl");
        config.setGcpProjectId("sisl-bq-billing");
        config.setDatasetId("Xammer_Billing_Dataset");
        config.setTableId("Xammer_Dataset");
        config.setServiceAccountKey("{\"type\":\"service_account\"}");
        when(configRepository.findAll()).thenReturn(List.of(config));

        ResponseEntity<List<CspBillingConfigDto>> response = controller.listConfigs();

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getName()).isEqualTo("Sisl");
        assertThat(response.getBody().get(0).getServiceAccountKey()).isNull();
    }

    @Test
    void createConfig_savesAndReturns() {
        CspBillingConfigDto dto = new CspBillingConfigDto();
        dto.setName("Sisl"); dto.setGcpProjectId("sisl-bq-billing");
        dto.setDatasetId("ds"); dto.setTableId("t");
        dto.setServiceAccountKey("{\"type\":\"service_account\"}");

        CspBillingConfig saved = new CspBillingConfig();
        saved.setId(1L); saved.setName("Sisl");
        saved.setGcpProjectId("sisl-bq-billing");
        saved.setDatasetId("ds"); saved.setTableId("t");
        saved.setServiceAccountKey("{\"type\":\"service_account\"}");
        when(configRepository.save(any())).thenReturn(saved);

        ResponseEntity<CspBillingConfigDto> response = controller.createConfig(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getId()).isEqualTo(1L);
        assertThat(response.getBody().getName()).isEqualTo("Sisl");
    }

    @Test
    void deleteConfig_returns400WhenAccountsExist() {
        when(cloudAccountRepository.existsByCspBillingConfigId(1L)).thenReturn(true);

        ResponseEntity<Void> response = controller.deleteConfig(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(configRepository, never()).deleteById(any());
    }

    @Test
    void syncAccount_returns404WhenNotFound() {
        when(cloudAccountRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.syncAccount(99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void syncAccount_returns400WhenNotCspManaged() {
        CloudAccount account = new CloudAccount();
        account.setCspManaged(false);
        when(cloudAccountRepository.findById(1L)).thenReturn(Optional.of(account));

        ResponseEntity<String> response = controller.syncAccount(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(ingestionService, never()).ingestForAccount(any());
    }
}
