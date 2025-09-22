package com.xammer.cloud.service.azure;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.azure.resourcemanager.AzureResourceManager;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class AzureDashboardService {

    private final AzureClientProvider azureClientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    public AzureDashboardService(AzureClientProvider azureClientProvider, CloudAccountRepository cloudAccountRepository) {
        this.azureClientProvider = azureClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    public AzureDashboardData getDashboardData(String subscriptionId) {
        CloudAccount account = cloudAccountRepository.findByProviderAccountId(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Azure account not found for subscription ID: " + subscriptionId));

        AzureResourceManager azureClient = azureClientProvider.getAzureClient(account);
        AzureDashboardData data = new AzureDashboardData();

        // --- Fetch Real Resource Inventory ---
        AzureDashboardData.ResourceInventory inventory = new AzureDashboardData.ResourceInventory();
        inventory.setVirtualMachines((int) azureClient.virtualMachines().list().stream().count());
        inventory.setDisks((int) azureClient.disks().list().stream().count());
        inventory.setSqlDatabases((int) azureClient.sqlServers().list().stream().mapToLong(server -> server.databases().list().size()).sum());
        inventory.setAppServices((int) azureClient.webApps().list().stream().count());
        inventory.setStorageAccounts((int) azureClient.storageAccounts().list().stream().count());
        data.setResourceInventory(inventory);

        // --- KPI Data ---
        // Correctly reference the inner class `DashboardData.Kpi`
        //List<DashboardData.Kpi> kpis = new ArrayList<>();
        // kpis.add(new DashboardData.Kpi("Total Resources", String.valueOf(inventory.getVirtualMachines() + inventory.getDisks() + inventory.getSqlDatabases() + inventory.getAppServices() + inventory.getStorageAccounts()), "", "Live Count"));
        //  data.setKpis(kpis);

        return data;
    }
}