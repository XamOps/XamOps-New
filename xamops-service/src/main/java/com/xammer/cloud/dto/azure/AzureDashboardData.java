package com.xammer.cloud.dto.azure;

import com.xammer.cloud.dto.DashboardData;
import java.io.Serializable;
import java.util.List;

public class AzureDashboardData implements Serializable {

  //  private List<DashboardData.Kpi> kpis; // **FIX:** Uncommented this line
    private ResourceInventory resourceInventory;

    public static class ResourceInventory implements Serializable {
        private int virtualMachines;
        private int disks;
        private int sqlDatabases;
        private int appServices;
        private int storageAccounts;

        // Getters and setters
        public int getVirtualMachines() { return virtualMachines; }
        public void setVirtualMachines(int virtualMachines) { this.virtualMachines = virtualMachines; }
        public int getDisks() { return disks; }
        public void setDisks(int disks) { this.disks = disks; }
        public int getSqlDatabases() { return sqlDatabases; }
        public void setSqlDatabases(int sqlDatabases) { this.sqlDatabases = sqlDatabases; }
        public int getAppServices() { return appServices; }
        public void setAppServices(int appServices) { this.appServices = appServices; }
        public int getStorageAccounts() { return storageAccounts; }
        public void setStorageAccounts(int storageAccounts) { this.storageAccounts = storageAccounts; }
    }

    // **FIX:** Uncommented these methods
//

    public ResourceInventory getResourceInventory() { return resourceInventory; }
    public void setResourceInventory(ResourceInventory resourceInventory) { this.resourceInventory = resourceInventory; }
}