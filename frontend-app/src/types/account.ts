/**
 * Account-related TypeScript types
 *
 * These types match the backend API responses for accounts
 */

export type CloudProvider = 'AWS' | 'GCP' | 'Azure';

export interface Account {
  /** The numeric XamOps account id (cloud_account.id), which the AutoSpotting v2 API takes. */
  dbId?: number;
  id: string;
  name: string;
  provider: CloudProvider;
  status: 'CONNECTED' | 'DISCONNECTED' | 'ERROR';
  access?: string;
  /** The connection role ARN, already in the account response (CloudK8s setup cards, D13). */
  roleArn?: string;
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
