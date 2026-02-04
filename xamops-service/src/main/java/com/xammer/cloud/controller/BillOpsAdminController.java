package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.CostService;
import com.xammer.cloud.service.gcp.GcpCostService;
import com.xammer.cloud.service.azure.AzureCostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

// --- CONSOLIDATED DTOS TO RESOLVE COMPILATION ISSUES ---

class BillOpsAccountDto {
    private Long id;
    private String name;
    private String providerAccountId;
    private String provider;
    private Double consumption;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProviderAccountId() {
        return providerAccountId;
    }

    public void setProviderAccountId(String providerAccountId) {
        this.providerAccountId = providerAccountId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Double getConsumption() {
        return consumption;
    }

    public void setConsumption(Double consumption) {
        this.consumption = consumption;
    }
}

class BillOpsUserDto {
    private Long id;
    private String username;
    private String email;
    private List<Long> accountAccessIds;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<Long> getAccountAccessIds() {
        return accountAccessIds;
    }

    public void setAccountAccessIds(List<Long> accountAccessIds) {
        this.accountAccessIds = accountAccessIds;
    }
}

class BillOpsClientDetailsDto {
    private Long id;
    private String name;
    private String email;
    private List<BillOpsAccountDto> accounts;
    private List<BillOpsUserDto> users;
    private Double totalConsumption;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<BillOpsAccountDto> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<BillOpsAccountDto> accounts) {
        this.accounts = accounts;
    }

    public List<BillOpsUserDto> getUsers() {
        return users;
    }

    public void setUsers(List<BillOpsUserDto> users) {
        this.users = users;
    }

    public Double getTotalConsumption() {
        return totalConsumption;
    }

    public void setTotalConsumption(Double totalConsumption) {
        this.totalConsumption = totalConsumption;
    }
}

@RestController
@RequestMapping("/api/xamops/billops-admin")
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
public class BillOpsAdminController {

    private static final Logger log = LoggerFactory.getLogger(BillOpsAdminController.class);

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private CostService costService;

    @Autowired
    private GcpCostService gcpCostService;

    @Autowired
    private AzureCostService azureCostService;

    @GetMapping("/clients")
    public List<BillOpsClientDetailsDto> getClients() {
        log.info("Fetching client details for BillOps Admin (Consolidated)");
        List<Client> clients = clientRepository.findAll();
        List<BillOpsClientDetailsDto> result = new ArrayList<>();

        for (Client client : clients) {
            BillOpsClientDetailsDto clientDto = new BillOpsClientDetailsDto();
            clientDto.setId(client.getId());
            clientDto.setName(client.getName());
            clientDto.setEmail(client.getEmail());

            // Fetch accounts for this client
            List<CloudAccount> accounts = cloudAccountRepository.findByClientId(client.getId());
            List<BillOpsAccountDto> accountDtos = new ArrayList<>();
            double totalConsumption = 0;

            for (CloudAccount account : accounts) {
                BillOpsAccountDto accDto = new BillOpsAccountDto();
                accDto.setId(account.getId());
                accDto.setName(account.getAccountName());
                accDto.setProvider(account.getProvider());
                accDto.setProviderAccountId(account.getProviderAccountId());

                try {
                    String providerAccId = account.getProviderAccountId();
                    String provider = account.getProvider();

                    if (providerAccId != null && !providerAccId.isEmpty()) {
                        Double consumption = 0.0;

                        if ("AWS".equalsIgnoreCase(provider)) {
                            consumption = costService.getTotalMonthToDateCost(providerAccId, false).join();
                        } else if ("GCP".equalsIgnoreCase(provider)) {
                            consumption = gcpCostService.getUnfilteredMonthToDateSpend(providerAccId).join();
                        } else if ("Azure".equalsIgnoreCase(provider)) {
                            consumption = azureCostService.getTotalMonthToDateCost(providerAccId).join();
                        }

                        accDto.setConsumption(consumption != null ? consumption : 0.0);
                        totalConsumption += accDto.getConsumption();
                    } else {
                        accDto.setConsumption(0.0);
                    }
                } catch (Exception e) {
                    log.error("Error fetching consumption for account {}: {}", account.getId(), e.getMessage());
                    accDto.setConsumption(0.0);
                }

                accountDtos.add(accDto);
            }
            clientDto.setAccounts(accountDtos);
            clientDto.setTotalConsumption(totalConsumption);

            // Fetch users for this client
            List<User> users = userRepository.findByClientId(client.getId());
            List<BillOpsUserDto> userDtos = new ArrayList<>();

            for (User user : users) {
                BillOpsUserDto userDto = new BillOpsUserDto();
                userDto.setId(user.getId());
                userDto.setUsername(user.getUsername());
                userDto.setEmail(user.getEmail());

                // Get account access for this user
                List<Long> accessibleAccountIds = userCloudAccountAccessRepository
                        .findCloudAccountIdsByUserId(user.getId());
                userDto.setAccountAccessIds(accessibleAccountIds != null ? accessibleAccountIds : new ArrayList<>());

                userDtos.add(userDto);
            }

            clientDto.setUsers(userDtos);
            result.add(clientDto);
        }

        return result;
    }
}
