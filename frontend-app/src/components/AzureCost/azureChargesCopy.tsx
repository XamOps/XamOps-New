import type { ChargesByServiceCopy } from '../Cost/ChargesByServiceSection';
import type { ChargeTypeReconciliationCopy } from '../Cost/ChargeTypeReconciliation';
import type { ServiceResourcesCopy } from '../Cost/ServiceResourcesDrawer';

/**
 * What the invoice and resource panels say on Azure.
 *
 * <p>Azure has no Cost and Usage Report and no Cost Explorer. Both figures come from one place — the
 * Microsoft Cost Management query API — and the reasons it can fail are entirely different from the
 * AWS ones the shared components default to. An Azure customer told that "no Cost and Usage Report
 * covers this month" will go looking in their billing console for a report they have never had and
 * cannot create.
 *
 * <p>The distinction that replaces it is real and worth stating plainly: Cost Management will group
 * spend by a given dimension only if the agreement behind the subscription exposes that dimension.
 * Enterprise Agreement, Microsoft Customer Agreement and pay-as-you-go differ, and Azure reports an
 * unsupported dimension as a rejected query rather than as a missing column — which is why per-
 * resource cost can be unavailable on a subscription whose service-level breakdown works perfectly.
 */
export const AZURE_CHARGES_COPY: ChargesByServiceCopy = {
  subheading: 'in the same breakdown as your Azure invoice — service, meter, and what was used.',

  loading: 'Querying Azure Cost Management…',

  emptyHint: (
    <>
      Azure Cost Management serves roughly the last 13 months and refreshes every few hours, so a very
      old month can legitimately be empty. A subscription connected recently also has nothing before
      the day its spend started being collected.
    </>
  ),

  currentMonthHint: (
    <>
      Azure Cost Management refreshes every few hours, so a month that has only just begun can still
      be empty. Pick last month for a complete invoice.
    </>
  ),
};

export const AZURE_RESOURCES_COPY: ServiceResourcesCopy = {
  unavailableTitle: 'Per-resource cost is not available',

  unavailableDetail: (
    <>
      This comes from a Cost Management query grouped by resource, and Azure would not group this
      subscription&apos;s spend that way. Which dimensions are available depends on the agreement
      behind the subscription — the service, meter and region breakdown behind this panel is
      unaffected.
    </>
  ),

  emptyHint: (
    <>
      Either nothing was billed to a resource this month, or the charges were subscription-level
      purchases — Marketplace, support, reservations — which Azure attributes to no resource.
    </>
  ),

  currentMonthHint: (
    <>
      Cost Management refreshes every few hours, so a month that has only just begun can still be
      empty. Pick last month for a complete one.
    </>
  ),

  noNamesHint: (
    <>
      Names are read from each resource&apos;s ARM id, which Azure lowercases in cost data, so they may
      not match the casing shown elsewhere in the portal. The badge beside each name is its resource
      group.
    </>
  ),
};

/**
 * What the reconciliation ledger says on Azure.
 *
 * <p>Azure's charge types arrive already humanised — `AzureCostInsightsService.readable` turns
 * `UnusedReservation` into "Unused reservation" before the payload leaves the backend — so none of
 * them reach the client as a raw code and the shared fall-through already renders a readable label.
 * What is overridden here is therefore the *explanation* rather than the name, keyed on the strings
 * that service actually emits. Anything it emits that is not listed falls through to its own label
 * with no explanation, which is a better answer than a guess.
 *
 * <p>The figures are amortized by Azure's own choice — the Cost Management query asks for
 * AmortizedCost, which is why this page has no basis toggle — so a reservation purchase appears as
 * the part of it that applies to this month rather than whole.
 */
export const AZURE_CHARGE_TYPES_COPY: ChargeTypeReconciliationCopy = {
  explanation: (
    <>
      Every line Cost Management returns carries a charge type. These are those types, summed — so
      the figures below are the same money as the breakdown above, cut by what kind of charge it was
      rather than by which service incurred it.
    </>
  ),

  emptyHint: (
    <>
      Nothing was billed in this month, so there is nothing to reconcile. Cost Management refreshes
      every few hours, so a month that has only just begun can also read as empty.
    </>
  ),

  labels: {
    Usage: { label: 'Usage', detail: 'What you used, at the rate you pay for it' },
    Purchase: {
      label: 'Purchase',
      detail: 'A reservation, savings plan or Marketplace purchase, spread across the term it buys',
    },
    'Unused reservation': {
      label: 'Unused reservation',
      detail: 'Reservation capacity this month that no running resource matched',
    },
    'Unused savings plan': {
      label: 'Unused savings plan',
      detail: 'The hourly commitment this month that usage did not reach',
    },
    Refund: { label: 'Refund', detail: 'Money returned to you' },
    'Rounding adjustment': {
      label: 'Rounding',
      detail: 'Sub-cent rounding across the invoice',
    },
  },
};
