/**
 * GCP Waste Management Types
 */

export interface GcpWasteResource {
  resourceName: string;
  type: string;
  location?: string;
  monthlySavings: number;
  detailsOpen?: boolean;
}

export interface GcpWasteGroup {
  type: string;
  resources: GcpWasteResource[];
  open: boolean;
  totalSavings: number;
}
