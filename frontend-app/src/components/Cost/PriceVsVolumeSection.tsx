import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import { fetchPriceVsVolume } from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';

/**
 * Explains *why* spend changed, by splitting each service's movement into the part caused
 * by using more or less, and the part caused by paying a different rate.
 *
 * Only a Cost and Usage Report can answer this — it needs usage quantity sitting beside
 * cost on every line, which the Cost Explorer API does not expose in a comparable form.
 *
 * <h3>Why it compares two CLOSED months</h3>
 * The obvious default — this month against last — compares a partial month with a
 * complete one, so every service looks like it collapsed. That is not a smaller bill, it
 * is fewer days, and no amount of labelling stops people reading it as a real drop. Two
 * finished months is the only comparison that means what it appears to mean.
 */

interface PriceVsVolumeSectionProps {
  /** Single AWS account with a report, or null to render nothing. */
  accountId: string | null;
}

const money = (v: number): string =>
  (v < 0 ? '-$' : '$') +
  Math.abs(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * Below this a change is noise — rounding on a service costing a few cents, not something
 * worth a row. Without it the table fills with $0.00 movements and the real ones are lost.
 */
const MIN_CHANGE = 1;

/** How many rows before it stops being a summary. */
const MAX_ROWS = 8;

const PriceVsVolumeSection: React.FC<PriceVsVolumeSectionProps> = ({ accountId }) => {
  // The last two complete months. See the note above on why not "this month".
  const period = useMemo(() => {
    const d = new Date();
    d.setDate(1);
    d.setMonth(d.getMonth() - 1);
    const prior = new Date(d);
    prior.setMonth(prior.getMonth() - 1);
    return {
      value: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`,
      current: d.toLocaleDateString('en-US', { month: 'long' }),
      prior: prior.toLocaleDateString('en-US', { month: 'long' }),
    };
  }, []);

  // Cached across mounts, so returning to this page does not re-scan the report.
  const { data, isPending, isError } = useCurInsight(
    'price-vs-volume',
    accountId,
    period.value,
    fetchPriceVsVolume,
  );
  const loading = Boolean(accountId) && isPending;
  // Movements under a dollar are rounding on a service costing pennies, not a story.
  const rows = useMemo(
    () =>
      isError || !data
        ? null
        : data.filter((r) => Math.abs(r.costChange) >= MIN_CHANGE).slice(0, MAX_ROWS),
    [data, isError],
  );

  if (!accountId || loading) return null;
  // Nothing moved by more than a dollar. A table of zeroes says less than no table.
  if (!rows || rows.length === 0) return null;

  const totalChange = rows.reduce((sum, r) => sum + r.costChange, 0);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex items-start justify-between gap-4 mb-1">
        <div>
          <h3 className="text-[15px] font-bold text-black">Why did spend change?</h3>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
            {period.current} vs {period.prior} — whether each service cost more because it
            was <span className="font-medium">used more</span>, or because the{' '}
            <span className="font-medium">rate changed</span>.
          </p>
        </div>
        <div className="text-right flex-shrink-0">
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] uppercase tracking-wide">Net change</p>
          <p
            className={`text-[18px] font-bold ${
              totalChange >= 0 ? 'text-[#DC2626]' : 'text-[#158045]'
            }`}
          >
            {totalChange >= 0 ? '+' : ''}
            {money(totalChange)}
          </p>
        </div>
      </div>

      <div className="overflow-x-auto -mx-2 mt-4">
        <table className="w-full min-w-[560px] text-[12.5px]">
          <thead>
            <tr className="text-left text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
              <th className="font-medium px-2 pb-2">Service</th>
              <th className="font-medium px-2 pb-2 text-right">Change</th>
              <th className="font-medium px-2 pb-2 text-right">Used more / less</th>
              <th className="font-medium px-2 pb-2 text-right">Rate change</th>
              <th className="font-medium px-2 pb-2">Mainly because</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              // Whichever effect is larger is the story; the other is a footnote. Saying
              // "mainly more usage" is what a reader actually takes away, rather than
              // leaving them to compare two signed numbers themselves.
              const usageDriven = Math.abs(r.volumeEffect) >= Math.abs(r.priceEffect);
              const reason = usageDriven
                ? r.volumeEffect >= 0
                  ? 'Used more'
                  : 'Used less'
                : r.priceEffect >= 0
                  ? 'Higher rate'
                  : 'Lower rate';

              return (
                <tr key={r.service} className="border-t border-[rgba(0,0,0,0.06)]">
                  <td className="px-2 py-2.5 font-medium text-black">{r.service}</td>
                  <td
                    className={`px-2 py-2.5 text-right font-semibold ${
                      r.costChange >= 0 ? 'text-[#DC2626]' : 'text-[#158045]'
                    }`}
                  >
                    {r.costChange >= 0 ? '+' : ''}
                    {money(r.costChange)}
                  </td>
                  <td className="px-2 py-2.5 text-right text-[rgba(0,0,0,0.6)]">
                    {r.volumeEffect >= 0 ? '+' : ''}
                    {money(r.volumeEffect)}
                  </td>
                  <td className="px-2 py-2.5 text-right text-[rgba(0,0,0,0.6)]">
                    {r.priceEffect >= 0 ? '+' : ''}
                    {money(r.priceEffect)}
                  </td>
                  <td className="px-2 py-2.5">
                    <span
                      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium ${
                        usageDriven
                          ? 'bg-[#4285F414] text-[#1A56C4]'
                          : 'bg-[#F4A42914] text-[#9A6800]'
                      }`}
                    >
                      <Icon icon={usageDriven ? 'mdi:chart-bar' : 'mdi:tag-outline'} width={12} />
                      {reason}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
        The two middle columns add up to the change. A service that only appeared this
        month counts entirely as usage, since it had no earlier rate to compare against.
      </p>
    </div>
  );
};

export default PriceVsVolumeSection;
