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
  /**
   * True when the cost is real but could not be priced, so `monthlySavings` is a
   * placeholder zero. Exclude from savings totals.
   */
  savingsUnknown?: boolean;
  /**
   * True when the resource is billed at nothing (unattached ENI, unused security group,
   * empty log group, idle ECS cluster record, ACM certificate). Still worth cleaning up
   * for hygiene, but it frees no spend, so exclude it from reclaimable-spend counts.
   */
  noCostImpact?: boolean;
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
