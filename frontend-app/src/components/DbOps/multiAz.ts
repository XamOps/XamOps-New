/**
 * Multi-AZ / high-availability mapping for the DbOps inventory tables.
 *
 * `DbInstance.multiAz` is a string whose values differ per cloud, so a raw truthiness check is
 * wrong (every non-empty value, including "No"/"None", is truthy). Backend values:
 *   AWS   — 'Yes' / 'No'            (DbOpsCostService)
 *   GCP   — 'REGIONAL' / 'ZONAL'    (GcpDbOpsService)
 *   Azure — 'ZoneRedundant' / 'None' (AzureDbOpsService)
 */

/** AWS: Multi-AZ is enabled only when the value is exactly 'Yes'. */
export function isAwsMultiAz(multiAz: string | null | undefined): boolean {
  return multiAz === 'Yes';
}

/** Azure: high availability is a zone-redundant deployment. */
export function isAzureHa(multiAz: string | null | undefined): boolean {
  return multiAz === 'ZoneRedundant';
}
