export interface AzureWasteItem {
  resourceId: string;
  resourceName: string;
  resourceType: string; // "Unattached Disk" | "Stopped VM" | "Deallocated VM" | "Orphaned Public IP" | "Unattached NIC"
  region: string;
  reason: string;
  monthlySavings: number;
}

export interface AzureWasteGroup {
  type: string;
  resources: AzureWasteItem[];
  open: boolean;
  totalSavings: number;
}
