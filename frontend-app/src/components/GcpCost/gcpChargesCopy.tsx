import type { ChargesByServiceCopy } from '../Cost/ChargesByServiceSection';
import type { ChargeTypeReconciliationCopy } from '../Cost/ChargeTypeReconciliation';
import type { ServiceResourcesCopy } from '../Cost/ServiceResourcesDrawer';

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

  currentMonthHint: (
    <>
      A billing export is written continuously but lags the usage it describes by a few hours, so a
      month that has only just started can still be empty. Pick last month for a complete invoice.
    </>
  ),
};

export const GCP_RESOURCES_COPY: ServiceResourcesCopy = {
  unavailableTitle: 'Per-resource cost is not available',

  unavailableDetail: (
    <>
      This project uses GCP&apos;s standard billing export, which records no resource name. Switching
      the export to <strong>detailed usage cost</strong> adds it from the next billing period — the
      service and SKU breakdown behind this panel works either way.
    </>
  ),

  emptyHint: (
    <>
      Either nothing was billed to a resource this month, or the export does not yet cover it.
    </>
  ),

  currentMonthHint: (
    <>
      The export lags the usage it describes by a few hours, so a month that has only just started
      can still be empty. Pick last month for a complete one.
    </>
  ),

  noNamesHint: (
    <>
      Names come from the export&apos;s <span className="font-mono">resource.global_name</span>, shown
      as its last path segment. A row with no name is a charge the export attributes to no resource.
    </>
  ),
};

/**
 * What the reconciliation ledger says on GCP.
 *
 * <p>The AWS defaults name AWS charge types and the Cost and Usage Report. GCP's export records its
 * own set in `cost_type` and `credits.type`, and the backend passes them through as it finds them,
 * so only the codes it emits raw are overridden here — anything else falls through to its own code,
 * which is a better answer than a guess.
 */
export const GCP_CHARGE_TYPES_COPY: ChargeTypeReconciliationCopy = {
  explanation: (
    <>
      Every line in the billing export carries a cost type, and credits carry a discount type. These
      are those types, summed — so the figures below are the same money as the breakdown above, cut
      by what kind of charge it was rather than by which service incurred it.
    </>
  ),

  emptyHint: <>Nothing was billed in this month, so there is nothing to reconcile.</>,

  labels: {
    Regular: { label: 'Usage', detail: 'What you used, at the rate you pay for it' },
    'Committed usage discount': {
      label: 'Committed use discount',
      detail: 'The discount earned by committing to a level of usage for one or three years',
    },
    'Sustained usage discount': {
      label: 'Sustained use discount',
      detail: 'An automatic discount for running a workload for most of the month',
    },
    'Free tier': {
      label: 'Free tier',
      detail: 'Usage inside the monthly free allowance, credited back',
    },
    Tax: { label: 'Tax', detail: 'Tax on the charges above' },
    Adjustment: {
      label: 'Adjustment',
      detail: 'A correction Google applied to a previously billed charge',
    },
    // The export's `rounding_error` cost type, which the backend's readable() renders this way.
    'Rounding error': {
      label: 'Rounding',
      detail: 'Sub-cent rounding across the invoice',
    },
    Discount: { label: 'Discount', detail: 'A negotiated discount applied to this bill' },
    Promotion: { label: 'Promotion', detail: 'Promotional credit applied to this bill' },
  },
};
