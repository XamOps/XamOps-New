/**
 * Waste Management Type Definitions
 *
 * Type definitions for the Waste Management page
 */

export interface WastedResource {
  resourceId: string;
  resourceName?: string;
  resourceType: string;
  region: string;
  reason: string;
  monthlySavings: number;
  detailsOpen?: boolean;
}

export interface ServiceGroup {
  serviceType: string;
  resources: WastedResource[];
  open: boolean;
}

export interface HighestWasteService {
  serviceType: string;
  totalCost: number;
  count: number;
}
