import type { ChargesByServiceCopy } from '../Cost/ChargesByServiceSection';
import type { TopResourcesCopy } from '../Cost/TopResourcesSection';

/**
 * What the invoice and resource panels say on GCP.
 *
 * <p>The shared panels default to AWS wording — "your AWS bill", "the Cost and Usage Report", "Cost
 * Explorer stops at the service" — because they were written for AWS and nothing else rendered them.
 * When this page started passing its own fetchers it inherited those sentences, so a GCP customer was
 * being told about a report and an API that belong to a different cloud.
 *
 * <p>The GCP distinction that replaces it is the one that actually has a fix: the standard billing
 * export carries no {@code resource} field at all, and the detailed export does. That is a setting the
 * customer can change, which is worth saying precisely.
 */
export const GCP_CHARGES_COPY: ChargesByServiceCopy = {
  subheading: 'in the same breakdown as your Google Cloud invoice — service, SKU, and what was used.',

  loading: 'Reading the BigQuery billing export…',

  emptyHint: (
    <>
      A billing export only covers days from when it was enabled onward — it does not backfill, so
      earlier months are not in it.
    </>
  ),
};

export const GCP_RESOURCES_COPY: TopResourcesCopy = {
  unavailableTitle: 'Per-resource cost is not available',

  unavailableDetail: (
    <>
      This project uses GCP&apos;s standard billing export, which records no resource name. Switching
      the export to <strong>detailed usage cost</strong> adds it from the next billing period — the
      service and SKU breakdown on the other tab works either way.
    </>
  ),

  emptyHint: (
    <>
      Either nothing was billed to a resource this month, or the export does not yet cover it.
    </>
  ),

  noNamesHint: (
    <>
      Names come from the export&apos;s <span className="font-mono">resource.global_name</span>, shown
      as its last path segment. A row with no name is a charge the export attributes to no resource.
    </>
  ),
};
