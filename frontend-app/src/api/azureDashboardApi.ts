import { AzureDashboardData, AzureResourceInventory, AzureResourceType, AZURE_RESOURCE_ICONS } from '../types/azureDashboard';

/**
 * Fetch Azure dashboard data for a single account
 */
export const fetchAzureDashboardData = async (
  accountId: string,
  forceRefresh: boolean = false
): Promise<AzureDashboardData> => {
  try {
    let endpoint = `/api/xamops/azure/dashboard-data?accountId=${accountId}`;
    if (forceRefresh) {
      endpoint += '&force=true';
    }

    console.log(`🔷 Fetching Azure dashboard data from: ${endpoint}`);

    const response = await fetch(endpoint, {
      method: 'GET',
      credentials: 'include',
      headers: {
        'Accept': 'application/json',
      },
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const rawData = await response.json();
    console.log('🔷 Raw Azure dashboard data received:', rawData);

    // Transform the raw data to match our frontend interface
    const transformedData: AzureDashboardData = {
      accountId: rawData.accountId || accountId,
      accountName: rawData.accountName || 'Azure Subscription',
      provider: 'Azure',
      kpi: {
        mtdSpend: rawData.monthToDateSpend || 0,
        forecastedSpend: rawData.forecastedSpend || 0,
        lastMonthSpend: rawData.lastMonthSpend || 0,
        potentialSavings: rawData.optimizationSummary?.totalPotentialSavings || 0,
      },
      resourceInventory: rawData.resourceInventory || {
        virtualMachines: 0,
        storageAccounts: 0,
        sqlDatabases: 0,
        virtualNetworks: 0,
        functions: 0,
        disks: 0,
        dnsZones: 0,
        loadBalancers: 0,
        containerInstances: 0,
        kubernetesServices: 0,
        appServices: 0,
        staticWebApps: 0,
      },
      regionStatus: rawData.regionStatus || [],
      costHistory: rawData.costHistory || { labels: [], costs: [], anomalies: [] },
      optimization: {
        summary: rawData.optimizationSummary || {
          totalPotentialSavings: 0,
          criticalAlertsCount: 0,
        },
        vmRecommendations: rawData.vmRecommendations || [],
        diskRecommendations: rawData.diskRecommendations || [],
        functionRecommendations: rawData.functionRecommendations || [],
        costAnomalies: rawData.costAnomalies || [],
      },
      billingSummary: rawData.billingSummary || [],
      optimizationSummary: rawData.optimizationSummary || {
        totalPotentialSavings: 0,
        criticalAlertsCount: 0,
      },
      vmRecommendations: rawData.vmRecommendations || [],
      diskRecommendations: rawData.diskRecommendations || [],
      functionRecommendations: rawData.functionRecommendations || [],
      costAnomalies: rawData.costAnomalies || [],
      lastUpdated: new Date().toISOString(),
      cacheStatus: 'fresh',
    };

    console.log('🔷 Transformed Azure dashboard data:', transformedData);
    return transformedData;
  } catch (error) {
    console.error('❌ Failed to fetch Azure dashboard data:', error);
    throw error;
  }
};

/**
 * Map inventory to resource types for the UI
 */
export const mapInventoryToResources = (inventory: AzureResourceInventory): AzureResourceType[] => {
  return [
    { name: 'Virtual Machines', type: 'Virtual Machine', icon: AZURE_RESOURCE_ICONS['Virtual Machines'], count: inventory.virtualMachines },
    { name: 'Storage Accounts', type: 'Storage Account', icon: AZURE_RESOURCE_ICONS['Storage Accounts'], count: inventory.storageAccounts },
    { name: 'SQL Databases', type: 'SQL Database', icon: AZURE_RESOURCE_ICONS['SQL Databases'], count: inventory.sqlDatabases },
    { name: 'Virtual Networks', type: 'Virtual Network', icon: AZURE_RESOURCE_ICONS['Virtual Networks'], count: inventory.virtualNetworks },
    { name: 'Function Apps', type: 'Function App', icon: AZURE_RESOURCE_ICONS['Function Apps'], count: inventory.functions },
    { name: 'Disk Storage', type: 'Disk', icon: AZURE_RESOURCE_ICONS['Disk Storage'], count: inventory.disks },
    { name: 'DNS Zones', type: 'DNS Zone', icon: AZURE_RESOURCE_ICONS['DNS Zones'], count: inventory.dnsZones },
    { name: 'Load Balancers', type: 'Load Balancer', icon: AZURE_RESOURCE_ICONS['Load Balancers'], count: inventory.loadBalancers },
    { name: 'Container Instances', type: 'Container Instance', icon: AZURE_RESOURCE_ICONS['Container Instances'], count: inventory.containerInstances },
    { name: 'Kubernetes Services', type: 'AKS', icon: AZURE_RESOURCE_ICONS['Kubernetes Services'], count: inventory.kubernetesServices },
    { name: 'App Services', type: 'App Service', icon: AZURE_RESOURCE_ICONS['App Services'], count: inventory.appServices },
    { name: 'Static Web Apps', type: 'Static Web App', icon: AZURE_RESOURCE_ICONS['Static Web Apps'], count: inventory.staticWebApps },
  ];
};
