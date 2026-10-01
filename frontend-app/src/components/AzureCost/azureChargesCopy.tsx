import type { ChargesByServiceCopy } from '../Cost/ChargesByServiceSection';
import type { TopResourcesCopy } from '../Cost/TopResourcesSection';

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
      old month or one that has only just begun can legitimately be empty. A subscription connected
      recently also has nothing before the day its spend started being collected.
    </>
  ),
};

export const AZURE_RESOURCES_COPY: TopResourcesCopy = {
  unavailableTitle: 'Per-resource cost is not available',

  unavailableDetail: (
    <>
      This comes from a Cost Management query grouped by resource, and Azure would not group this
      subscription&apos;s spend that way. Which dimensions are available depends on the agreement
      behind the subscription — the service, meter and region breakdown on the other tab is
      unaffected.
    </>
  ),

  emptyHint: (
    <>
      Either nothing was billed to a resource this month, or the charges were subscription-level
      purchases — Marketplace, support, reservations — which Azure attributes to no resource.
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
