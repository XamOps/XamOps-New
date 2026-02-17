/**
 * Account-related TypeScript types
 *
 * These types match the backend API responses for accounts
 */

export type CloudProvider = 'AWS' | 'GCP' | 'Azure';

export interface Account {
  id: string;
  dbId?: number; // Database ID for backend API calls
  name: string;
  provider: CloudProvider;
  status: 'CONNECTED' | 'DISCONNECTED' | 'ERROR';
  azureSubscriptionId?: string;
  // Grafana monitoring configuration
  grafanaIp?: string;
  grafanaDashboardUid?: string;
  grafanaDashboardSlug?: string;
  // Add other fields as needed based on your API response
}

export interface AccountSelectionState {
  selectedAccountIds: string[];
  multiAccountMode: boolean;
  tempSelectedAccounts: string[];
}
