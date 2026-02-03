/**
 * Azure CloudList API
 *
 * API client for fetching Azure CloudList resources
 * Maintains exact same backend endpoints as the original HTML version
 */

import { AzureServiceGroup } from '../types/azureCloudList';

/**
 * Fetch Azure CloudList resources
 * @param accountIds - Array of account IDs to fetch resources for
 * @param forceRefresh - Force refresh data from backend
 * @returns Array of service groups with resources
 */
export const fetchAzureCloudListResources = async (
  accountIds: string[],
  forceRefresh: boolean = false
): Promise<AzureServiceGroup[]> => {
  try {
    const accountIdParam = accountIds.join(',');
    let apiUrl = `/api/azure/cloudlist/resources?accountIds=${accountIdParam}`;

    if (forceRefresh) {
      apiUrl += '&forceRefresh=true';
    }

    console.log('🔵 Azure CloudList: Fetching resources from:', apiUrl);

    const response = await fetch(apiUrl, {
      credentials: 'include',
      headers: {
        'Accept': 'application/json',
      },
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const data = await response.json();
    console.log('🔵 Azure CloudList: Received data:', data);

    return Array.isArray(data) ? data : [];
  } catch (error) {
    console.error('❌ Azure CloudList: Error fetching resources:', error);
    throw error;
  }
};

/**
 * Get Azure service icon path
 * @param serviceType - The service type name
 * @returns Icon path
 */
export const getAzureServiceIcon = (serviceType: string): string => {
  const iconMap: Record<string, string> = {
    'Virtual Machines': 'azure-vm.png',
    'Virtual Machine Scale Sets': 'azure-vmss.png',
    'Availability Sets': 'azure-availability-sets.png',
    'Container Instances': 'azure-container-instances.png',
    'Kubernetes Services': 'azure-aks.png',
    'App Services': 'app-service.png',
    'Storage Accounts': 'azure-storage-account.png',
    'Disks': 'disk-storage.png',
    'Snapshots': 'azure-snapshot.png',
    'Virtual Networks': 'virtual-network.png',
    'Load Balancers': 'load-balancer.png',
    'Application Gateways': 'azure-app-gateway.png',
    'VPN Gateways': 'azure-vpn-gateway.png',
    'ExpressRoute Circuits': 'azure-expressroute.png',
    'Public IP Addresses': 'azure-public-ip.png',
    'Network Security Groups': 'azure-nsg.png',
    'Network Interfaces': 'azure-network-interface.png',
    'DNS Zones': 'dns.png',
    'Traffic Manager': 'azure-traffic-manager.png',
    'SQL Databases': 'sql-database.png',
    'CosmosDB': 'azure-cosmos-db.png',
    'Redis Cache': 'azure-redis.png',
    'Service Bus': 'azure-service-bus.png',
    'Event Hubs': 'azure-event-hubs.png',
    'Container Registries': 'azure-container-registry.png',
    'Search Services': 'azure-search.png',
  };

  return iconMap[serviceType] || 'azure-icon.png';
};

/**
 * Get state badge CSS classes based on resource state
 * @param state - Resource state
 * @returns CSS class string
 */
export const getStateClass = (state: string): string => {
  const s = (state || '').toLowerCase();

  if (
    s.includes('running') ||
    s.includes('succeeded') ||
    s.includes('available') ||
    s.includes('active') ||
    s.includes('online')
  ) {
    return 'bg-green-100 text-green-800 border-green-200';
  }

  if (
    s.includes('stopped') ||
    s.includes('deallocated') ||
    s.includes('failed') ||
    s.includes('deleted')
  ) {
    return 'bg-red-100 text-red-800 border-red-200';
  }

  if (
    s.includes('pending') ||
    s.includes('updating') ||
    s.includes('creating') ||
    s.includes('starting')
  ) {
    return 'bg-yellow-100 text-yellow-800 border-yellow-200';
  }

  return 'bg-gray-100 text-gray-800 border-gray-200';
};
