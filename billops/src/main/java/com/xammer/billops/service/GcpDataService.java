package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.Client;
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
    public CloudAccount createGcpAccount(GcpAccountRequestDto request, Client client) {
        CloudAccount account = new CloudAccount();
        account.setAccountName(request.getAccountName());
        account.setClient(client);
        account.setProvider("GCP");
        account.setGcpProjectId(request.getProjectId());
        account.setGcpServiceAccountKey(request.getServiceAccountKey());
        account.setStatus("CONNECTED");

        return cloudAccountRepository.save(account);
    }
}