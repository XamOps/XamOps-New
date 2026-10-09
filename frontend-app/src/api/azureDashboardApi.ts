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
      // `forecast` replaced the misnamed `anomalies` key. Both are read so a payload already in the
      // backend's DB snapshot cache, written before the rename, still charts its forecast point.
      costHistory: {
        labels: rawData.costHistory?.labels || [],
        costs: rawData.costHistory?.costs || [],
        forecast: rawData.costHistory?.forecast || rawData.costHistory?.anomalies || [],
      },
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

      // Carried through deliberately. This transform builds an explicit object, so anything not
      // named here is dropped — which is why the backend's incomplete-data flags never reached
      // the page and a subscription with no cost export showed a confident $0.00.
      // Defaults to complete so an older cached payload without the fields is not flagged.
      dataComplete: rawData.dataComplete !== false,
      dataWarnings: rawData.dataWarnings || [],

      /*
       * The billing currency, which the backend has recorded since ingestion started reading the
       * Cost Management `Currency` column and which this transform dropped along with everything
       * else it did not name. Its absence is why every Azure cost component hardcoded a rupee
       * symbol and one of them hardcoded a dollar — both right for an INR-billed subscription and
       * both wrong for any other, with a roughly ninety-fold error in the reader's head.
       *
       * Left undefined rather than defaulted to USD when the backend does not send it, so a caller
       * can tell "billed in dollars" from "not ingested yet" and fall back on its own terms.
       */
      currency: rawData.currency || undefined,
      monthToDateSpendUsd: rawData.monthToDateSpendUsd,

      // How old the figures are. Set by the backend on every snapshot read and never mapped, so
      // the page had no way to say whether it was showing this morning's numbers or last week's.
      snapshotAgeMinutes: rawData.snapshotAgeMinutes,
      stale: rawData.stale === true,
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
    { name: 'Virtual Machines', type: 'Virtual Machines', icon: AZURE_RESOURCE_ICONS['Virtual Machines'], count: inventory.virtualMachines },
    { name: 'Storage Accounts', type: 'Storage Accounts', icon: AZURE_RESOURCE_ICONS['Storage Accounts'], count: inventory.storageAccounts },
    { name: 'SQL Databases', type: 'SQL Databases', icon: AZURE_RESOURCE_ICONS['SQL Databases'], count: inventory.sqlDatabases },
    { name: 'Virtual Networks', type: 'Virtual Networks', icon: AZURE_RESOURCE_ICONS['Virtual Networks'], count: inventory.virtualNetworks },
    { name: 'Function Apps', type: 'Function Apps', icon: AZURE_RESOURCE_ICONS['Function Apps'], count: inventory.functions },
    { name: 'Disk Storage', type: 'Disks', icon: AZURE_RESOURCE_ICONS['Disk Storage'], count: inventory.disks },
    { name: 'DNS Zones', type: 'DNS Zones', icon: AZURE_RESOURCE_ICONS['DNS Zones'], count: inventory.dnsZones },
    { name: 'Load Balancers', type: 'Load Balancers', icon: AZURE_RESOURCE_ICONS['Load Balancers'], count: inventory.loadBalancers },
    { name: 'Container Instances', type: 'Container Instances', icon: AZURE_RESOURCE_ICONS['Container Instances'], count: inventory.containerInstances },
    { name: 'Kubernetes Services', type: 'Kubernetes Services', icon: AZURE_RESOURCE_ICONS['Kubernetes Services'], count: inventory.kubernetesServices },
    { name: 'App Services', type: 'App Services', icon: AZURE_RESOURCE_ICONS['App Services'], count: inventory.appServices },
    { name: 'Static Web Apps', type: 'Static Web Apps', icon: AZURE_RESOURCE_ICONS['Static Web Apps'], count: inventory.staticWebApps },
  ];
};
