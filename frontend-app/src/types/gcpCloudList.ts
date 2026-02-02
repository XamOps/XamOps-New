/**
 * Types for GCP CloudList
 */

export interface GcpResource {
  id: string;
  name: string;
  type: string;
  location: string;
  status: string;
  tags?: Record<string, string>;
}

export interface GcpServiceGroup {
  serviceType: string;
  resourceCount: number;
  resources: GcpResource[];
}

export interface GcpCloudListPageData {
  services: GcpServiceGroup[];
  totalServices: number;
  totalResources: number;
  hasNext: boolean;
}

export interface GcpCloudListFilters {
  region: string;
  searchTerm: string;
  serviceType?: string;
}
