import React, { useMemo, useState } from 'react';
import {
  fetchChargesByService,
  fetchTopResources,
  type PriceVolumeChange,
  type ResourceChange,
  type ServiceCharge,
  type TopResources,
} from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import { AriaTabGroup } from '../ui/Tabs';
import ChargesByServiceSection, { type ChargesByServiceCopy } from './ChargesByServiceSection';
import TopResourcesSection, { type TopResourcesCopy } from './TopResourcesSection';
import PriceVsVolumeSection from './PriceVsVolumeSection';

/**
 * Two views of the same bill, switched from the card's own heading.
 *
 * <p>"Charges by service" answers what was billed, in the invoice's own breakdown. "Charges by
 * resource" answers which individual bucket, volume or instance that was. They read the same
 * Cost and Usage Report at the same grain and only differ in how they cut it, which is why they
 * belong behind one switch rather than stacked as two sections the reader scrolls between.
 *
 * <p>The account-wide resource tree that briefly lived here has moved to the Cloud Map, where
 * it is drawn against real connectivity between resources rather than against cost alone.
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
 * on a tab switch throws both away, so coming back from the tree lands on a collapsed table for
 * the current month — reading as the page having reset itself. Hidden with the {@code hidden}
 * attribute, which takes it out of the accessibility tree as well as the layout.
 *
 * <p>The resource list is mounted only once it has been opened. It costs an Athena scan, so an
 * account whose reader never leaves the invoice view should never pay for one.
 *
 * <h2>Why visibility is decided here and not by the panels</h2>
 * Every CUR section hides itself when the account has no usable report. That is right for a
 * section that owns its own space, and wrong the moment two of them share a card: the first
 * build let the active panel hide, which deleted the sub-tab control along with it — clicking
 * "Charges by resource" made the entire card vanish with no way back to the invoice. So the group
 * decides whether the card exists, and each panel only decides what fills it.
 */

type TabId = 'charges' | 'resources' | 'change';

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
   * Where the two panels read from. Defaults to the AWS Cost and Usage Report; GCP passes its
   * BigQuery billing-export fetchers, which return the identical payload shapes.
   */
  chargesFetcher?: (accountId: string, period?: string) => Promise<ServiceCharge[] | null>;
  resourcesFetcher?: (accountId: string, period?: string) => Promise<TopResources | null>;
  /** Cache-key prefix, so two providers never share an entry for the same id. */
  insightPrefix?: string;
  /** ISO currency code the billing source reports in. */
  currency?: string;
  /** Whether resource rows offer the label control. Off for GCP — see TopResourcesSection. */
  labels?: boolean;
  /**
   * What the two panels call the billing source.
   *
   * <p>Not optional in spirit, only in type. Both panels default to AWS wording — "your AWS bill",
   * "the Cost and Usage Report" — and a provider that passes fetchers without passing copy will show
   * an Azure subscription a sentence about a report it has never had.
   */
  chargesCopy?: ChargesByServiceCopy;
  resourcesCopy?: TopResourcesCopy;
  /**
   * Where the month-on-month comparison comes from. Defaults to the AWS Cost and Usage Report.
   *
   * <p>This used to be a section of its own, stacked below the card. Moved in here because it reads the
   * same bill at the same grain and only cuts it differently, which is exactly what this card is for —
   * and because a page that grows a section for every question eventually costs more scrolling than the
   * answers are worth.
   */
  changeFetcher?: (accountId: string, period?: string) => Promise<PriceVolumeChange[] | null>;
  /** Where the per-resource detail panel's payload comes from. */
  detailFetcher?: (
    accountId: string,
    resourceId: string,
    period?: string,
  ) => Promise<ResourceChange | null>;
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
}) => {
  const [tab, setTab] = useState<TabId>('charges');
  /** Tabs the reader has opened, so the tree's query is never issued unasked. */
  const [visited, setVisited] = useState<Set<TabId>>(() => new Set<TabId>(['charges']));

  /**
   * Does this account have a readable report at all?
   *
   * <p>Answered from the invoice breakdown, which is the cheapest question already being asked:
   * the charges panel issues this exact query for this exact month on mount, so react-query
   * serves both from one request. Asking the tree instead would mean running its Athena scan
   * for every visitor to the Cost page, including the ones who never open the tab.
   */
  const period = useMemo(currentPeriod, []);
  const chargesInsight = `${insightPrefix}charges-by-service`;
  const { data: charges, isPending } = useCurInsight(
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
          { id: 'resources', label: 'Charges by resource', icon: 'mdi:sitemap-outline' },
          // Last, because it is the question that follows the other two: what was billed, then which
          // resource, then why that moved.
          { id: 'change', label: 'Why spend changed', icon: 'mdi:swap-vertical' },
        ]}
      />
    ),
    [tab],
  );

  if (!accountId) return null;

  /*
   * Hidden entirely when there is nothing to show, matching what the page did before the tabs
   * existed: `null` for no usable report, and an empty current month for an account whose report
   * does not reach it yet — the same two conditions ChargesByServiceSection hides itself on.
   *
   * Not hidden while the query is still in flight: the charges panel has its own loading state,
   * and gating on `isPending` would make the whole card appear a second late on every visit.
   */
  // `charges?.length` rather than a null check: the value is also undefined before the first
  // fetch settles, and an empty array is the "report does not reach this month" case.
  const readable = isPending || Boolean(charges?.length);
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
        />
      </div>

      {visited.has('resources') && (
        <div hidden={tab !== 'resources'}>
          <TopResourcesSection
            accountId={accountId}
            heading={control}
            fetcher={resourcesFetcher}
            insightName={`${insightPrefix}top-resources`}
            currency={currency}
            labels={labels}
            copy={resourcesCopy}
            detailFetcher={detailFetcher}
          />
        </div>
      )}

      {/* Mounted only once opened, like the resource list and for the same reason: the comparison scans
          two months rather than one, so a reader who never asks "why did it change" should never pay
          for the query that answers it. */}
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
