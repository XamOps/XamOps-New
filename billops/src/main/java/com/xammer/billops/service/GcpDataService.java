package com.xammer.billops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.Client;
import com.xammer.billops.dto.GcpAccountRequestDto;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GcpDataService {

    private static final Logger log = LoggerFactory.getLogger(GcpDataService.class);
    private final CloudAccountRepository cloudAccountRepository;
    private final ObjectMapper objectMapper;

    public GcpDataService(CloudAccountRepository cloudAccountRepository, ObjectMapper objectMapper) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CloudAccount createGcpAccount(GcpAccountRequestDto request, Client client) {
        log.info("Creating GCP account: {} for client: {}", request.getAccountName(), client.getId());

        try {
            String serviceAccountEmail = extractServiceAccountEmail(request.getServiceAccountKey());

            if (request.getBillingExportTable() != null && !request.getBillingExportTable().isEmpty()) {
                validateBillingTableFormat(request.getBillingExportTable());
            }

            CloudAccount account = new CloudAccount();
            account.setAccountName(request.getAccountName());
            account.setClient(client);
            account.setProvider("GCP");
            
            // GCP specific fields
            String projectId = request.getGcpProjectId() != null ? request.getGcpProjectId() : request.getProjectId();
            account.setGcpProjectId(projectId);
            account.setGcpServiceAccountEmail(serviceAccountEmail);
            account.setGcpServiceAccountKey(request.getServiceAccountKey());
            account.setGcpWorkloadIdentityPoolId(request.getGcpWorkloadIdentityPoolId());
            account.setGcpWorkloadIdentityProviderId(request.getGcpWorkloadIdentityProviderId());
            
            // Set external_id to satisfy constraints
            account.setExternalId(projectId);
            account.setStatus("CONNECTED");
            account.setAccessType("read-only");

            // Set billing export table
            account.setBillingExportTable(request.getBillingExportTable());

            CloudAccount savedAccount = cloudAccountRepository.save(account);
            log.info("✅ GCP account created successfully with ID: {}", savedAccount.getId());

            return savedAccount;

        } catch (Exception e) {
            log.error("❌ Failed to create GCP account: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create GCP account: " + e.getMessage());
        }
    }

    private String extractServiceAccountEmail(String serviceAccountKey) {
        try {
            JsonNode jsonNode = objectMapper.readTree(serviceAccountKey);
            String email = jsonNode.get("client_email").asText();

            if (email == null || email.isEmpty()) {
                throw new RuntimeException("Service account email not found in JSON key");
            }
            return email;
        } catch (Exception e) {
            log.error("Failed to parse service account key", e);
            throw new RuntimeException("Invalid service account key format: " + e.getMessage());
        }
    }

    private void validateBillingTableFormat(String billingTable) {
        String[] parts = billingTable.split("\\.");

        if (parts.length != 3) {
            throw new RuntimeException(
                    "Invalid BigQuery table format. Expected format: project.dataset.table " +
                            "(e.g., my-project.billing_export.gcp_billing_export_v1_XXXXXX_XXXXXX_XXXXXX)"
            );
        }

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].trim().isEmpty()) {
                throw new RuntimeException("Invalid BigQuery table format: Part " + (i + 1) + " is empty");
            }
        }
    }
}