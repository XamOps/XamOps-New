import React, { useMemo, useState } from 'react';
import {
  fetchChargeTypes,
  fetchChargesByService,
  fetchReportCapabilities,
  fetchServiceResources,
  fetchTopResources,
  type ChargeTypeAmount,
  type CostBasis,
  type PriceVolumeChange,
  type ReportCapabilities,
  type ResourceChange,
  type ServiceCharge,
  type TopResources,
} from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import { AriaTabGroup } from '../ui/Tabs';
import ChargesByServiceSection, { type ChargesByServiceCopy } from './ChargesByServiceSection';
import { type ChargeTypeReconciliationCopy } from './ChargeTypeReconciliation';
import { type ServiceResourcesCopy } from './ServiceResourcesDrawer';
import { type ConnectivityFetchers } from './ResourceConnectivityTab';
import PriceVsVolumeSection from './PriceVsVolumeSection';

/**
 * Two views of the same bill, switched from the card's own heading.
 *
 * <p>"Charges by service" answers what was billed, in the invoice's own breakdown. "Why spend
 * changed" answers whether a movement was price or volume. They read the same Cost and Usage
 * Report at the same grain and only differ in how they cut it, which is why they belong behind
 * one switch rather than stacked as two sections the reader scrolls between.
 *
 * <p>The account-wide resource tree that briefly lived here has moved to the Cloud Map, where
 * it is drawn against real connectivity between resources rather than against cost alone.
 *
 * <h2>Why per-resource cost is no longer a tab</h2>
 * It was one — "Charges by resource", a third segment listing every service in the account all
 * over again, with its own month selector and its own expansion state. The reader would expand
 * S3 here, read $2,527, switch tabs, scroll to find S3 in a second list of the same services,
 * and expand it again to see which bucket that was. Two lists of identical services, two
 * expansion states, and the figure that raised the question off-screen by the time it was
 * answered.
 *
 * <p>So the second list is gone and the drill-down lives on the service row itself: clicking a
 * service in the invoice breakdown slides its resources in from the right, over the total they
 * add up to. See {@code ServiceResourcesDrawer}. The resource fetchers still arrive here because
 * this is where the providers configure the card — they are passed straight through to the
 * charges panel, which owns the selection.
 *
 * <h2>Why the control replaces the heading</h2>
 * The switch is passed down as each panel's {@code heading}, so it renders in place of that
 * panel's {@code h3} rather than in a strip above the card. A tab bar floating above a bordered
 * card reads as page-level navigation — as though the rest of the Cost page changed too — when
 * the only thing changing is the contents of this one card. Sitting where the title sits, the
 * active label *is* the title, which is both what the control means and one less line of
 * chrome.
 *
 * <h2>Why the charges panel stays mounted</h2>
 * It carries a month selection and an expansion set that can be several levels deep. Unmounting
 * on a tab switch throws both away, so coming back lands on a collapsed table for the current
 * month — reading as the page having reset itself. Hidden with the {@code hidden} attribute,
 * which takes it out of the accessibility tree as well as the layout.
 *
 * <h2>Why visibility is decided here and not by the panels</h2>
 * Every CUR section hides itself when the account has no usable report. That is right for a
 * section that owns its own space, and wrong the moment two of them share a card: the first
 * build let the active panel hide, which deleted the sub-tab control along with it — switching
 * tabs made the entire card vanish with no way back to the invoice. So the group decides whether
 * the card exists, and each panel only decides what fills it.
 */

type TabId = 'charges' | 'change';

interface CostDetailTabsProps {
  /**
   * Single account or project with a readable billing source, or null.
   *
   * <p>A CUR belongs to one AWS account and a billing export to one GCP project, so a
   * multi-account selection has nothing to show here and the whole card is omitted rather than
   * rendered empty.
   */
  accountId: string | null;
  /**
   * Where the panels read from. Defaults to the AWS Cost and Usage Report; GCP passes its
   * BigQuery billing-export fetchers, which return the identical payload shapes.
   *
   * <p>Widened, not new: the third parameter is the cost basis, and a two-parameter function
   * satisfies this type — so the GCP and Azure fetchers are unchanged. The type has to accept it or
   * the charges card calling it with a basis is a TS2554.
   */
  chargesFetcher?: (
    accountId: string,
    period?: string,
    basis?: CostBasis,
  ) => Promise<ServiceCharge[] | null>;
  /**
   * Where a clicked service's resources come from, for the panel that opens over the invoice.
   *
   * <p>Pass `null` for a billing source that cannot attribute cost to a resource at all; service
   * rows then stay plain expanders rather than advertising a drill-down that can only apologise.
   */
  resourcesFetcher?: ((accountId: string, period?: string) => Promise<TopResources | null>) | null;
  /** Cache-key prefix, so two providers never share an entry for the same id. */
  insightPrefix?: string;
  /** ISO currency code the billing source reports in. */
  currency?: string;
  /** Whether resource rows offer the label control. Off for GCP — see ServiceResourcesDrawer. */
  labels?: boolean;
  /**
   * What the panels call the billing source.
   *
   * <p>Not optional in spirit, only in type. Both default to AWS wording — "your AWS bill",
   * "the Cost and Usage Report" — and a provider that passes fetchers without passing copy will show
   * an Azure subscription a sentence about a report it has never had.
   */
  chargesCopy?: ChargesByServiceCopy;
  resourcesCopy?: ServiceResourcesCopy;
  /**
   * Where the month-on-month comparison comes from. Defaults to the AWS Cost and Usage Report.
   *
   * <p>This used to be a section of its own, stacked below the card. Moved in here because it reads the
   * same bill at the same grain and only cuts it differently, which is exactly what this card is for —
   * and because a page that grows a section for every question eventually costs more scrolling than the
   * answers are worth.
   */
  changeFetcher?: (accountId: string, period?: string) => Promise<PriceVolumeChange[] | null>;
  /**
   * Where the per-resource detail panel's payload comes from.
   *
   * <p>The `scope` is part of the signature because the panel is reached from a resource row, and
   * the report groups by (resource, service, region) — so one resource can hold rows under two
   * services, each a different figure. Omitting it from the type let a fetcher that ignores the
   * scope satisfy it, and the panel would then report the resource's whole cost against a row
   * showing part of it.
   */
  detailFetcher?: (
    accountId: string,
    resourceId: string,
    period?: string,
    scope?: { service?: string; region?: string },
  ) => Promise<ResourceChange | null>;
  /**
   * Where the per-resource panel's Connectivity tab reads from, or `null` to omit that tab.
   *
   * <p>Defaults to the AWS trio. Both provider pages pass `null` explicitly, for the reasons on
   * `ResourceCostDrawer.connectivity` — omitting it would take the AWS default and render a tab
   * that can only apologise.
   */
  connectivity?: ConnectivityFetchers | null;
  /**
   * Where the basis capability probe reads from. Defaults to the AWS report.
   *
   * <p>Pass `null` for a billing source with no unblended/amortized distinction, which suppresses
   * the basis toggle and its probe. Omitting it takes the AWS default, which would render an AWS
   * toggle over figures that have no such basis — so both provider pages pass `null` explicitly.
   */
  capabilitiesFetcher?:
    | ((accountId: string, period?: string) => Promise<ReportCapabilities | null>)
    | null;
  /**
   * Where the reconciliation ledger reads from. Defaults to the AWS report.
   *
   * <p>All three providers have a charge-type endpoint and none had a UI for it. `null` omits the
   * ledger entirely; it does not affect the basis toggle, which is a separate gate.
   */
  chargeTypesFetcher?:
    | ((accountId: string, period?: string, basis?: CostBasis) => Promise<ChargeTypeAmount[] | null>)
    | null;
  /**
   * Where one service's resources come from, scoped server-side. Defaults to the AWS report.
   *
   * <p>Nullable because a provider that cannot answer per-service must be able to say so, and
   * omitting the prop takes the AWS endpoint instead — where a GCP project id or Azure
   * subscription id 404s and would empty every panel.
   */
  scopedResourcesFetcher?:
    | ((
        accountId: string,
        service: string,
        period?: string,
        basis?: CostBasis,
      ) => Promise<TopResources | null>)
    | null;
  /** What the reconciliation ledger calls the billing source, and its per-code labels. */
  chargeTypesCopy?: ChargeTypeReconciliationCopy;
}

/** The current billing month, in the form the CUR endpoints take. */
const currentPeriod = (): string => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
};

const CostDetailTabs: React.FC<CostDetailTabsProps> = ({
  accountId,
  chargesFetcher = fetchChargesByService,
  resourcesFetcher = fetchTopResources,
  insightPrefix = '',
  currency = 'USD',
  labels = true,
  chargesCopy,
  resourcesCopy,
  changeFetcher,
  detailFetcher,
  connectivity,
  capabilitiesFetcher = fetchReportCapabilities,
  chargeTypesFetcher = fetchChargeTypes,
  scopedResourcesFetcher = fetchServiceResources,
  chargeTypesCopy,
}) => {
  const [tab, setTab] = useState<TabId>('charges');
  /** Tabs the reader has opened, so the comparison's query is never issued unasked. */
  const [visited, setVisited] = useState<Set<TabId>>(() => new Set<TabId>(['charges']));

  /**
   * Does this account have a readable report at all?
   *
   * <p>Answered from the invoice breakdown, which is the cheapest question already being asked:
   * the charges panel issues this exact query for this exact month on mount, so react-query
   * serves both from one request. Asking the resource list instead would mean running its Athena
   * scan for every visitor to the Cost page, including the ones who never open a service.
   */
  const period = useMemo(currentPeriod, []);
  const chargesInsight = `${insightPrefix}charges-by-service`;
  const { data: charges, isPending, isError } = useCurInsight(
    chargesInsight,
    accountId,
    period,
    chargesFetcher,
  );

  const select = (key: string) => {
    const id = key as TabId;
    setTab(id);
    setVisited((prev) => (prev.has(id) ? prev : new Set(prev).add(id)));
  };

  /**
   * The same element handed to both panels.
   *
   * <p>Only one is visible at a time, so only one tablist is ever in the accessibility tree —
   * the other sits inside a `hidden` subtree. Memoised so switching tabs does not hand the
   * panels a new node and remount the control under them.
   */
  const control = useMemo(
    () => (
      <AriaTabGroup
        variant="segmented"
        label="Cost detail view"
        selectedKey={tab}
        onSelectionChange={select}
        tabs={[
          { id: 'charges', label: 'Charges by service', icon: 'mdi:receipt-text-outline' },
          // Second, because it is the question that follows: what was billed, then why that
          // moved. "Which resource" sits between the two and is no longer a tab — it opens from
          // the service row itself, where the figure that raises it is.
          { id: 'change', label: 'Why spend changed', icon: 'mdi:swap-vertical' },
        ]}
      />
    ),
    [tab],
  );

  if (!accountId) return null;

  /*
   * Hidden only when the billing source cannot answer for this account at all — no report, no
   * readable export, or a read that failed outright. Then there is genuinely nothing behind the
   * card and omitting it is right.
   *
   * An empty month is emphatically not that case, and used to be treated as it. The report was
   * found, queried and answered; the month simply has nothing in it yet. That is the normal state
   * on the 1st of a month, because a CUR's first delivery for a new billing period lands hours
   * after the period opens — and it was making the whole card disappear, sub-tab control and
   * month selector included, so the one action that would have helped (pick last month) was
   * unreachable. An empty table that says why beats a page with a hole in it.
   *
   * Not hidden while the query is still in flight: each panel has its own loading state, and
   * gating on `isPending` would make the card appear a second late on every visit.
   */
  // `undefined` while the first fetch is in flight, `null` for "no usable source", `[]` for a
  // readable source with an empty month. Only the middle one hides the card.
  const readable = isPending || (!isError && (charges ?? null) !== null);
  if (!readable) return null;

  return (
    <div>
      <div hidden={tab !== 'charges'}>
        <ChargesByServiceSection
          accountId={accountId}
          heading={control}
          fetcher={chargesFetcher}
          insightName={chargesInsight}
          currency={currency}
          copy={chargesCopy}
          /* The per-service resource panel. Configured here because this is where each provider
             wires the card up, but owned there, because the service row is what opens it. Its
             query is not issued until a reader actually clicks a service, so an account whose
             reader only ever reads the invoice still pays for one scan rather than two. */
          resourcesFetcher={resourcesFetcher}
          resourcesCopy={resourcesCopy}
          resourcesInsightName={`${insightPrefix}top-resources`}
          labels={labels}
          detailFetcher={detailFetcher}
          connectivity={connectivity}
          /* The basis toggle, the reconciliation ledger and the scoped per-service query. Two
             independent gates: a provider with no cost basis still wants the ledger. */
          capabilitiesFetcher={capabilitiesFetcher}
          chargeTypesFetcher={chargeTypesFetcher}
          scopedResourcesFetcher={scopedResourcesFetcher}
          chargeTypesCopy={chargeTypesCopy}
        />
      </div>

      {/* Mounted only once opened: the comparison scans two months rather than one, so a reader who
          never asks "why did it change" should never pay for the query that answers it. */}
      {visited.has('change') && (
        <div hidden={tab !== 'change'}>
          <PriceVsVolumeSection
            accountId={accountId}
            heading={control}
            fetcher={changeFetcher}
            insightName={`${insightPrefix}price-vs-volume`}
            currency={currency}
          />
        </div>
      )}
    </div>
  );
};

export default CostDetailTabs;
