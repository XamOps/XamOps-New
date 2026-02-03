/**
 * Azure CloudList Types
 *
 * Type definitions for Azure CloudList resources and service groups
 */

export interface AzureResource {
  name: string;
  id: string;
  region: string;
  state: string;
  type?: string;
}

export interface AzureServiceGroup {
  serviceType: string;
  resources: AzureResource[];
}

export interface AzureCloudListData {
  groups: AzureServiceGroup[];
  totalResources: number;
  lastUpdated?: string;
}

export interface AzureCloudListFilters {
  accountId?: string;
  serviceType?: string;
  region?: string;
  state?: string;
}
