import axios from 'axios';
import {
  GcpDashboardData,
  GcpMultiAccountDashboardData,
  GcpDashboardApiResponse,
  GcpHealthCheckResponse,
  GcpCacheClearRequest,
} from '../types/gcpDashboard';

const API_BASE_URL = '/api/xamops/gcp/dashboard';

// Single Account Dashboard Data
export const fetchGcpDashboardData = async (
  accountId: string,
  forceRefresh: boolean = false
): Promise<GcpDashboardData> => {
  try {
    console.log('🌐 GCP API: Fetching dashboard data', { accountId, forceRefresh });
    const response = await axios.get<GcpDashboardData>(`${API_BASE_URL}`, {
      params: {
        accountId,
        forceRefresh, // Note: Backend ignores this for security - only nightly refresh
      },
    });
    console.log('✅ GCP API: Dashboard data received', response.data);
    return response.data;
  } catch (error) {
    console.error('❌ GCP API: Failed to fetch dashboard data', error);
    if (axios.isAxiosError(error)) {
      console.error('❌ GCP API: Error details', {
        status: error.response?.status,
        data: error.response?.data,
        message: error.message,
      });
      throw new Error(
        error.response?.data?.message || 'Failed to fetch GCP dashboard data'
      );
    }
    throw error;
  }
};

// Multi-Account Dashboard Data
export const fetchGcpMultiAccountDashboardData = async (
  accountIds: string[]
): Promise<GcpMultiAccountDashboardData> => {
  try {
    if (accountIds.length === 0) {
      throw new Error('At least one account ID is required');
    }
    if (accountIds.length > 10) {
      throw new Error('Maximum 10 accounts can be selected at once');
    }

    const response = await axios.get<GcpMultiAccountDashboardData>(
      `${API_BASE_URL}/multi-account`,
      {
        params: {
          accountIds: accountIds.join(','),
        },
      }
    );
    return response.data;
  } catch (error) {
    if (axios.isAxiosError(error)) {
      throw new Error(
        error.response?.data?.message ||
          'Failed to fetch multi-account GCP dashboard data'
      );
    }
    throw error;
  }
};

// Manual Refresh (Will return 403 - only for authorized users)
export const refreshGcpDashboardData = async (
  accountId: string
): Promise<GcpDashboardApiResponse> => {
  try {
    const response = await axios.post<GcpDashboardApiResponse>(
      `${API_BASE_URL}/refresh`,
      null,
      {
        params: { accountId },
      }
    );
    return response.data;
  } catch (error) {
    if (axios.isAxiosError(error)) {
      if (error.response?.status === 403) {
        throw new Error(
          'Manual refresh is disabled for security. Data is refreshed nightly at 4 AM IST.'
        );
      }
      throw new Error(
        error.response?.data?.message || 'Failed to refresh GCP dashboard data'
      );
    }
    throw error;
  }
};

// Clear Cache
export const clearGcpDashboardCache = async (
  request: GcpCacheClearRequest
): Promise<void> => {
  try {
    await axios.delete(`${API_BASE_URL}/cache`, {
      params: {
        accountId: request.accountId,
        type: request.type || 'all',
      },
    });
  } catch (error) {
    if (axios.isAxiosError(error)) {
      throw new Error(
        error.response?.data?.message || 'Failed to clear GCP dashboard cache'
      );
    }
    throw error;
  }
};

// Health Check
export const checkGcpDashboardHealth = async (): Promise<GcpHealthCheckResponse> => {
  try {
    const response = await axios.get<GcpHealthCheckResponse>(
      `${API_BASE_URL}/health`
    );
    return response.data;
  } catch (error) {
    if (axios.isAxiosError(error)) {
      return {
        status: 'down',
        message: error.response?.data?.message || 'GCP Dashboard service is down',
        timestamp: new Date().toISOString(),
      };
    }
    return {
      status: 'down',
      message: 'Unknown error occurred',
      timestamp: new Date().toISOString(),
    };
  }
};

// Layout Management (Reusing existing dashboard layout endpoints)
export const fetchGcpDashboardLayout = async (
  accountId: string
): Promise<any> => {
  try {
    const response = await axios.get('/api/xamops/dashboard/layout', {
      params: { accountId },
    });
    return response.data;
  } catch (error) {
    if (axios.isAxiosError(error)) {
      if (error.response?.status === 404) {
        return null; // No saved layout
      }
      throw new Error('Failed to fetch dashboard layout');
    }
    throw error;
  }
};

export const saveGcpDashboardLayout = async (
  accountId: string,
  layout: any
): Promise<void> => {
  try {
    await axios.post('/api/xamops/dashboard/layout', {
      accountId,
      layout,
    });
  } catch (error) {
    if (axios.isAxiosError(error)) {
      throw new Error('Failed to save dashboard layout');
    }
    throw error;
  }
};

// Helper function to map inventory to resource types
export const mapInventoryToResources = (
  inventory: any
): { name: string; type: string; icon: string; count: number }[] => {
  return [
    {
      name: 'GCE Instances',
      type: 'Compute Engine',
      icon: 'gcp-compute-engine.png',
      count: inventory.gceInstances || 0,
    },
    {
      name: 'Cloud Storage Buckets',
      type: 'Cloud Storage',
      icon: 'gcp-cloud-storage.png',
      count: inventory.cloudStorageBuckets || 0,
    },
    {
      name: 'Cloud SQL Instances',
      type: 'Cloud SQL',
      icon: 'gcp-cloud-sql.png',
      count: inventory.cloudSqlInstances || 0,
    },
    {
      name: 'GKE Clusters',
      type: 'Kubernetes Engine',
      icon: 'gcp-gke.png',
      count: inventory.gkeClusters || 0,
    },
    {
      name: 'VPC Networks',
      type: 'VPC Network',
      icon: 'gcp-vpc.png',
      count: inventory.vpcNetworks || 0,
    },
    {
      name: 'Cloud DNS Zones',
      type: 'Cloud DNS',
      icon: 'gcp-cloud-dns.png',
      count: inventory.cloudDnsZones || 0,
    },
    {
      name: 'Load Balancers',
      type: 'Load Balancer',
      icon: 'gcp-load-balancer.png',
      count: inventory.loadBalancers || 0,
    },
    {
      name: 'Cloud NAT Gateways',
      type: 'Cloud NAT',
      icon: 'gcp-nat.png',
      count: inventory.cloudNatGateways || 0,
    },
    {
      name: 'Artifact Repositories',
      type: 'Artifact Registry',
      icon: 'gcp-artifact-registry.png',
      count: inventory.artifactRepositories || 0,
    },
    {
      name: 'KMS Keys',
      type: 'Cloud KMS',
      icon: 'gcp-kms.png',
      count: inventory.kmsKeys || 0,
    },
    {
      name: 'Cloud Functions',
      type: 'Cloud Function',
      icon: 'gcp-cloud-functions.png',
      count: inventory.cloudFunctions || 0,
    },
    {
      name: 'Cloud Build Triggers',
      type: 'Cloud Build',
      icon: 'gcp-cloud-build.png',
      count: inventory.cloudBuildTriggers || 0,
    },
    {
      name: 'Secrets',
      type: 'Secret Manager',
      icon: 'gcp-secret-manager.png',
      count: inventory.secrets || 0,
    },
    {
      name: 'Cloud Armor Policies',
      type: 'Cloud Armor',
      icon: 'gcp-cloud-armor.png',
      count: inventory.cloudArmorPolicies || 0,
    },
  ];
};

// Format currency
export const formatCurrency = (amount: number): string => {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
};

// Format number with K/M/B suffix
export const formatCompactNumber = (num: number): string => {
  if (num >= 1000000000) {
    return (num / 1000000000).toFixed(1) + 'B';
  }
  if (num >= 1000000) {
    return (num / 1000000).toFixed(1) + 'M';
  }
  if (num >= 1000) {
    return (num / 1000).toFixed(1) + 'K';
  }
  return num.toString();
};

// Format date
export const formatDate = (dateString: string): string => {
  const date = new Date(dateString);
  return date.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
};

// Format time ago
export const formatTimeAgo = (dateString: string): string => {
  const date = new Date(dateString);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffMins < 1) return 'just now';
  if (diffMins < 60) return `${diffMins} minute${diffMins > 1 ? 's' : ''} ago`;
  if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`;
  if (diffDays < 7) return `${diffDays} day${diffDays > 1 ? 's' : ''} ago`;
  return formatDate(dateString);
};

export default {
  fetchGcpDashboardData,
  fetchGcpMultiAccountDashboardData,
  refreshGcpDashboardData,
  clearGcpDashboardCache,
  checkGcpDashboardHealth,
  fetchGcpDashboardLayout,
  saveGcpDashboardLayout,
  mapInventoryToResources,
  formatCurrency,
  formatCompactNumber,
  formatDate,
  formatTimeAgo,
};
