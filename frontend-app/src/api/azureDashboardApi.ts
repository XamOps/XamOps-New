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
      costByRegion: rawData.costByRegion || {},
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

// =================== Azure AutoSpotting API ===================

export interface AzureVmssDto {
  name: string;
  resourceGroup: string;
  region: string;
  isEnabled: boolean;
  instanceCount: number;
  spotInstanceCount: number;
  ondemandInstanceCount: number;
  vmSku: string;
  currentHourlyCost: number;
  potentialHourlyCost: number;
  actualHourlySavings: number;
  potentialHourlySavings: number;
}

export interface AzureSpotInstanceDto {
  instanceId: string;
  instanceType: string;
  availabilityZone: string;
  lifecycle: string; // "spot" | "on-demand"
  state: string;
  asgName: string;
  region: string;
  estimatedHourlySavings?: number;
  launchTime?: string;
  privateIpAddress?: string;
  publicIpAddress?: string;
}

const AUTOSPOTTING_BASE = '/api/azure/autospotting';

export const fetchAzureVmssAllRegions = async (subscriptionId: string): Promise<AzureVmssDto[]> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/vmss/all-regions/${subscriptionId}`, {
    credentials: 'include',
  });
  if (!response.ok) throw new Error(`Failed to fetch Azure VMSS: ${response.status}`);
  return response.json();
};

export const fetchAzureSpotInstances = async (subscriptionId: string): Promise<AzureSpotInstanceDto[]> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/instances/${subscriptionId}`, {
    credentials: 'include',
  });
  if (!response.ok) throw new Error(`Failed to fetch Azure spot instances: ${response.status}`);
  return response.json();
};

export const fetchAzureAutoSpottingHistory = async (
  subscriptionId: string,
  range: string = 'last7d'
): Promise<any> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/history/${subscriptionId}?range=${range}`, {
    credentials: 'include',
  });
  if (!response.ok) throw new Error(`Failed to fetch Azure history: ${response.status}`);
  return response.json();
};

export const fetchAzureAutoSpottingEvents = async (
  subscriptionId: string,
  params: { start?: string; end?: string; eventType?: string; vmssName?: string } = {}
): Promise<any> => {
  const query = new URLSearchParams();
  if (params.start) query.append('start', params.start);
  if (params.end) query.append('end', params.end);
  if (params.eventType) query.append('eventType', params.eventType);
  if (params.vmssName) query.append('vmssName', params.vmssName);

  const response = await fetch(
    `${AUTOSPOTTING_BASE}/events/${subscriptionId}${query.toString() ? '?' + query.toString() : ''}`,
    { credentials: 'include' }
  );
  if (!response.ok) throw new Error(`Failed to fetch Azure events: ${response.status}`);
  return response.json();
};

export const enableAzureAutoSpotting = async (
  subscriptionId: string,
  resourceGroup: string,
  vmssName: string
): Promise<{ success: boolean; message: string }> => {
  const response = await fetch(
    `${AUTOSPOTTING_BASE}/enable?subscriptionId=${encodeURIComponent(subscriptionId)}&resourceGroup=${encodeURIComponent(resourceGroup)}&vmssName=${encodeURIComponent(vmssName)}`,
    { method: 'POST', credentials: 'include' }
  );
  if (!response.ok) throw new Error(`Failed to enable Azure AutoSpotting: ${response.status}`);
  return response.json();
};

export const disableAzureAutoSpotting = async (
  subscriptionId: string,
  resourceGroup: string,
  vmssName: string
): Promise<{ success: boolean; message: string }> => {
  const response = await fetch(
    `${AUTOSPOTTING_BASE}/disable?subscriptionId=${encodeURIComponent(subscriptionId)}&resourceGroup=${encodeURIComponent(resourceGroup)}&vmssName=${encodeURIComponent(vmssName)}`,
    { method: 'POST', credentials: 'include' }
  );
  if (!response.ok) throw new Error(`Failed to disable Azure AutoSpotting: ${response.status}`);
  return response.json();
};

export const fetchAzureCostData = async (subscriptionId: string): Promise<any> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/costs/${subscriptionId}`, { credentials: 'include' });
  if (!response.ok) throw new Error(`Failed to fetch Azure cost data: ${response.status}`);
  return response.json();
};

export const fetchAzureSavingsMetrics = async (subscriptionId: string): Promise<any> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/savings/${subscriptionId}`, { credentials: 'include' });
  if (!response.ok) throw new Error(`Failed to fetch Azure savings metrics: ${response.status}`);
  return response.json();
};

export const fetchAzureLaunchAnalytics = async (subscriptionId: string, range: string = 'last7d'): Promise<any> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/analytics/launches/${subscriptionId}?range=${range}`, { credentials: 'include' });
  if (!response.ok) throw new Error(`Failed to fetch Azure launch analytics: ${response.status}`);
  return response.json();
};

export const fetchAzureReservationCoverage = async (subscriptionId: string): Promise<any> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/analytics/reservation-coverage/${subscriptionId}`, { credentials: 'include' });
  if (!response.ok) throw new Error(`Failed to fetch Azure reservation coverage: ${response.status}`);
  return response.json();
};

export const fetchAzureSettings = async (subscriptionId: string): Promise<any> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/settings/${subscriptionId}`, { credentials: 'include' });
  if (!response.ok) throw new Error(`Failed to fetch Azure AutoSpotting settings: ${response.status}`);
  return response.json();
};

export const updateAzureRispRespectSettings = async (
  subscriptionId: string,
  enabled: boolean,
  threshold: number
): Promise<any> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/settings/${subscriptionId}/reservation-respect`, {
    method: 'PUT',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled, threshold }),
  });
  if (!response.ok) throw new Error(`Failed to update Azure settings: ${response.status}`);
  return response.json();
};

export const fetchAzureVmssSpotConfig = async (
  subscriptionId: string,
  resourceGroup: string,
  vmssName: string
): Promise<any> => {
  const params = new URLSearchParams({ resourceGroup, vmssName });
  const response = await fetch(`${AUTOSPOTTING_BASE}/vmss/${subscriptionId}/spot-config?${params}`, { credentials: 'include' });
  if (!response.ok) throw new Error(`Failed to fetch VMSS spot config: ${response.status}`);
  return response.json();
};

export const saveAzureVmssSpotConfig = async (
  subscriptionId: string,
  resourceGroup: string,
  vmssName: string,
  spotPercentage: number | null,
  onDemandCount: number | null
): Promise<any> => {
  const params = new URLSearchParams({ resourceGroup, vmssName });
  const response = await fetch(`${AUTOSPOTTING_BASE}/vmss/${subscriptionId}/spot-config?${params}`, {
    method: 'PUT',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ spot_percentage: spotPercentage, on_demand_count: onDemandCount }),
  });
  if (!response.ok) throw new Error(`Failed to save VMSS spot config: ${response.status}`);
  return response.json();
};

export const fetchAzureVmssSkuConfig = async (
  subscriptionId: string,
  resourceGroup: string,
  vmssName: string
): Promise<any> => {
  const params = new URLSearchParams({ resourceGroup, vmssName });
  const response = await fetch(`${AUTOSPOTTING_BASE}/vmss/${subscriptionId}/sku-types?${params}`, { credentials: 'include' });
  if (!response.ok) throw new Error(`Failed to fetch VMSS SKU config: ${response.status}`);
  return response.json();
};

export const saveAzureVmssSkuConfig = async (
  subscriptionId: string,
  resourceGroup: string,
  vmssName: string,
  allowedVmSkus: string,
  strategy: string
): Promise<any> => {
  const params = new URLSearchParams({ resourceGroup, vmssName });
  const response = await fetch(`${AUTOSPOTTING_BASE}/vmss/${subscriptionId}/sku-types?${params}`, {
    method: 'PUT',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ allowed_vm_skus: allowedVmSkus, spot_allocation_strategy: strategy }),
  });
  if (!response.ok) throw new Error(`Failed to save VMSS SKU config: ${response.status}`);
  return response.json();
};

export const syncAzureSpotEvents = async (subscriptionId: string): Promise<any> => {
  const response = await fetch(`${AUTOSPOTTING_BASE}/vmss/${subscriptionId}/sync`, {
    method: 'POST',
    credentials: 'include',
  });
  if (!response.ok) throw new Error(`Failed to sync Azure spot events: ${response.status}`);
  return response.json();
};
