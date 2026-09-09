import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchDataTransferCosts, type TransferCharge } from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';

/**
 * Network spend, grouped by what actually drives it.
 *
 * <p>This is normally the largest cost nobody can see. A service breakdown files NAT
 * gateway charges under EC2 and cross-AZ traffic under whichever service moved the bytes,
 * so the network total never appears anywhere as one number — it is scattered across every
 * other line of the bill. Only a Cost and Usage Report can reassemble it, because only the
 * report carries {@code line_item_usage_type} on every row.
 *
 * <p>The categories are the ones that map to an actual decision: a NAT gateway can be
 * replaced with a VPC endpoint, cross-AZ traffic can be made same-AZ, egress can be moved
 * behind CloudFront. A flat list of usage types would be accurate and useless.
 */

interface DataTransferSectionProps {
  /** Single AWS account with a report, or null to render nothing. */
  accountId: string | null;
}

/** The last 12 months, newest first. Matches the Charges by service selector. */
const monthOptions = (): { value: string; label: string }[] => {
  const out: { value: string; label: string }[] = [];
  const d = new Date();
  d.setDate(1);
  for (let i = 0; i < 12; i++) {
    out.push({
      value: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`,
      label: d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' }),
    });
    d.setMonth(d.getMonth() - 1);
  }
  return out;
};

const money = (v: number): string =>
  '$' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const quantity = (v: number, unit: string): string => {
  const n = v.toLocaleString('en-US', {
    minimumFractionDigits: v < 10 ? 2 : 0,
    maximumFractionDigits: v < 10 ? 2 : 0,
  });
  return unit ? `${n} ${unit}` : n;
};

/**
 * What each category means and what can be done about it.
 *
 * The explanations matter more here than anywhere else on the page: "cross-AZ" is jargon,
 * and someone who does not already know what it means cannot act on a number labelled
 * with it. Each one names the fix, because that is the only reason to show the row.
 */
const CATEGORIES: Record<string, { icon: string; color: string; blurb: string }> = {
  'NAT Gateway': {
    icon: 'mdi:lan-connect',
    color: '#DC2626',
    blurb:
      'Private subnets reaching the internet. Includes both the hourly fee and per-GB processing — ' +
      'a VPC endpoint removes the processing charge for traffic to S3, DynamoDB and other AWS services.',
  },
  'Cross-AZ': {
    icon: 'mdi:swap-horizontal',
    color: '#F4A429',
    blurb:
      'Traffic between availability zones in the same region, charged in both directions. ' +
      'Often an app talking to a database in another AZ.',
  },
  'Inter-region': {
    icon: 'mdi:earth',
    color: '#7C3AED',
    blurb: 'Traffic between AWS regions — replication, cross-region reads, or a multi-region setup.',
  },
  'Internet egress': {
    icon: 'mdi:upload-network-outline',
    color: '#4285F4',
    blurb:
      'Data leaving AWS for the internet. Serving it through CloudFront is usually cheaper per GB.',
  },
  'Internet ingress': {
    icon: 'mdi:download-network-outline',
    color: '#158045',
    blurb: 'Data entering AWS. Normally free, so anything charged here is worth a look.',
  },
  'Other network': {
    icon: 'mdi:transit-connection-variant',
    color: '#6B7280',
    blurb: 'Transfer charges that do not fall into the categories above.',
  },
};

const fallbackMeta = { icon: 'mdi:transit-connection-variant', color: '#6B7280', blurb: '' };

interface Group {
  category: string;
  cost: number;
  charges: TransferCharge[];
  /** Total GB, when the category is measured in GB at all. Null for hour-based rows. */
  gigabytes: number | null;
}

const DataTransferSection: React.FC<DataTransferSectionProps> = ({ accountId }) => {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState(months[0].value);

  // Cached across mounts, so returning to this page does not re-scan the report.
  const { data, isPending, isError } = useCurInsight(
    'data-transfer',
    accountId,
    period,
    fetchDataTransferCosts,
  );
  const rows: TransferCharge[] | null = isError ? null : (data ?? null);
  // Only the first load blocks; a background revalidation keeps the rows on screen.
  const loading = Boolean(accountId) && isPending;

  const groups = useMemo<Group[]>(() => {
    if (!rows) return [];
    const byCategory = new Map<string, TransferCharge[]>();
    for (const r of rows) {
      const list = byCategory.get(r.category);
      if (list) list.push(r);
      else byCategory.set(r.category, [r]);
    }
    return Array.from(byCategory.entries())
      .map(([category, charges]) => {
        // Only GB-denominated rows are summed. A NAT gateway bills hours alongside bytes,
        // and adding the two would produce a number that is not a quantity of anything.
        const gb = charges
          .filter((c) => c.unit.toUpperCase() === 'GB')
          .reduce((sum, c) => sum + c.quantity, 0);
        return {
          category,
          charges: [...charges].sort((a, b) => b.cost - a.cost),
          cost: charges.reduce((sum, c) => sum + c.cost, 0),
          gigabytes: gb > 0 ? gb : null,
        };
      })
      .sort((a, b) => b.cost - a.cost);
  }, [rows]);

  const total = groups.reduce((sum, g) => sum + g.cost, 0);

  const toggle = (category: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(category)) next.delete(category);
      else next.add(category);
      return next;
    });
  };

  // No report for this account — the section does not belong on the page at all.
  if (!accountId || (!loading && rows === null)) return null;

  const selectedLabel = months.find((m) => m.value === period)?.label ?? '';

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex flex-wrap items-start justify-between gap-4 mb-4">
        <div>
          <h3 className="text-[15px] font-bold text-black">Data transfer &amp; network costs</h3>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
            Network charges pulled out of every service they are billed under.
          </p>
        </div>

        <div className="flex items-center gap-4">
          <select
            value={period}
            onChange={(e) => setPeriod(e.target.value)}
            className="text-[12px] border border-[rgba(0,0,0,0.15)] rounded-lg px-2.5 py-1.5 bg-white text-black focus:outline-none focus:ring-1 focus:ring-[#4285F4]"
          >
            {months.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
          <div className="text-right">
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] uppercase tracking-wide">Total</p>
            <p className="text-[18px] font-bold text-black leading-tight">{money(total)}</p>
          </div>
        </div>
      </div>

      {loading ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[52px] rounded-lg" />
          ))}
        </div>
      ) : groups.length === 0 ? (
        <div className="text-center py-8">
          <Icon
            icon="mdi:transit-connection-variant"
            width={26}
            className="text-[rgba(0,0,0,0.18)] mx-auto mb-2"
          />
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">
            No network charges in {selectedLabel}.
          </p>
          <p className="text-[11.5px] text-[rgba(0,0,0,0.35)] mt-1">
            Either nothing was billed, or the report does not yet cover this month.
          </p>
        </div>
      ) : (
        <div className="space-y-1.5">
          {groups.map((g) => {
            const meta = CATEGORIES[g.category] ?? fallbackMeta;
            const share = total > 0 ? (g.cost / total) * 100 : 0;
            const isOpen = expanded.has(g.category);

            return (
              <div
                key={g.category}
                className="border border-[rgba(0,0,0,0.08)] rounded-lg overflow-hidden"
              >
                <button
                  type="button"
                  onClick={() => toggle(g.category)}
                  className="w-full flex items-center gap-3 px-3 py-2.5 hover:bg-[rgba(0,0,0,0.02)] transition-colors text-left"
                >
                  <Icon
                    icon={isOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                    width={16}
                    className="text-[rgba(0,0,0,0.35)] flex-shrink-0"
                  />
                  <Icon
                    icon={meta.icon}
                    width={17}
                    style={{ color: meta.color }}
                    className="flex-shrink-0"
                  />

                  <div className="flex-1 min-w-0">
                    <div className="flex items-baseline gap-2">
                      <span className="text-[13px] font-semibold text-black">{g.category}</span>
                      {g.gigabytes !== null && (
                        <span className="text-[11.5px] text-[rgba(0,0,0,0.45)]">
                          {quantity(g.gigabytes, 'GB')}
                        </span>
                      )}
                    </div>
                    <div className="h-1 bg-[rgba(0,0,0,0.05)] rounded-full mt-1.5 overflow-hidden">
                      <div
                        className="h-full rounded-full"
                        style={{ width: `${share}%`, backgroundColor: meta.color }}
                      />
                    </div>
                  </div>

                  <div className="text-right flex-shrink-0">
                    <p className="text-[13px] font-bold text-black">{money(g.cost)}</p>
                    <p className="text-[10.5px] text-[rgba(0,0,0,0.4)]">{share.toFixed(0)}%</p>
                  </div>
                </button>

                {isOpen && (
                  <div className="border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                    {meta.blurb && (
                      <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] leading-relaxed px-4 pt-2.5">
                        {meta.blurb}
                      </p>
                    )}
                    <table className="w-full text-[12px] mt-1">
                      <tbody>
                        {g.charges.map((c) => (
                          <tr
                            key={`${c.usageType}-${c.service}`}
                            className="border-t border-[rgba(0,0,0,0.05)]"
                          >
                            <td className="px-4 py-2">
                              <span className="font-mono text-[11.5px] text-black">
                                {c.usageType}
                              </span>
                              <span className="text-[11px] text-[rgba(0,0,0,0.4)] ml-2">
                                {c.service}
                              </span>
                            </td>
                            <td className="px-3 py-2 text-right text-[rgba(0,0,0,0.6)] whitespace-nowrap">
                              {quantity(c.quantity, c.unit)}
                            </td>
                            <td className="px-4 py-2 text-right font-medium text-black whitespace-nowrap w-[90px]">
                              {money(c.cost)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {groups.length > 0 && (
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
          NAT Gateway includes its hourly fee as well as per-GB processing — the two are
          billed separately, and the hourly one is usually the smaller of the pair.
        </p>
      )}
    </div>
  );
};

export default DataTransferSection;
