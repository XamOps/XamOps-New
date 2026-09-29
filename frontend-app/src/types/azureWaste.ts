export interface AzureWasteItem {
  resourceId: string;
  resourceName: string;
  resourceType: string; // "Unattached Disk" | "Stopped VM" | "Deallocated VM" | "Orphaned Public IP" | "Unattached NIC"
  region: string;
  reason: string;
  monthlySavings: number;
  /**
   * True when the cost is real but could not be priced, so `monthlySavings` is a
   * placeholder zero. Exclude from savings totals and cost-based ranking.
   */
  savingsUnknown?: boolean;
  /**
   * True when Azure bills nothing for the resource (unattached NIC, unused NSG,
   * empty availability set, route table, empty resource group). Still worth cleaning up
   * for hygiene, but it frees no spend, so exclude it from reclaimable-spend counts.
   */
  noCostImpact?: boolean;
}

export interface HighestWasteCategory {
  resourceType: string;
  totalCost: number;
  count: number;
}

export interface AzureWasteGroup {
  type: string;
  resources: AzureWasteItem[];
  open: boolean;
  totalSavings: number;
}
