package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.Customer;
import com.xammer.billops.dto.GcpAccountRequestDto;
import com.xammer.billops.repository.CloudAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GcpDataService {

    private final CloudAccountRepository cloudAccountRepository;

    public GcpDataService(CloudAccountRepository cloudAccountRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @Transactional
    public CloudAccount createGcpAccount(GcpAccountRequestDto request, Customer customer) {
        // In a real-world application, you would add logic here to validate the
        // service account key by attempting to connect to a GCP API.
        // For this example, we will assume the key is valid.

        CloudAccount account = new CloudAccount();
        account.setAccountName(request.getAccountName());
        account.setCustomer(customer);
        account.setProvider("GCP");
        account.setGcpProjectId(request.getProjectId());
        account.setGcpServiceAccountKey(request.getServiceAccountKey());
        account.setStatus("CONNECTED"); // GCP accounts are connected directly

        return cloudAccountRepository.save(account);
    }
}