/**
 * GCP Waste Management Types
 */

export interface GcpWasteResource {
  resourceName: string;
  type: string;
  location?: string;
  monthlySavings: number;
  /**
   * True when the cost is real but could not be priced, so `monthlySavings` is a
   * placeholder zero. Exclude from savings totals and cost-based ranking.
   */
  savingsUnknown?: boolean;
  /**
   * True when GCP bills nothing for the resource. Still worth cleaning up for hygiene,
   * but it frees no spend, so exclude it from reclaimable-spend counts.
   */
  noCostImpact?: boolean;
  detailsOpen?: boolean;
}

export interface HighestWasteCategory {
  type: string;
  totalCost: number;
  count: number;
}

export interface GcpWasteGroup {
  type: string;
  resources: GcpWasteResource[];
  open: boolean;
  totalSavings: number;
}
