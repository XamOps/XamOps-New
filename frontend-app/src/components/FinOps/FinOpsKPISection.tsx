import React, { useRef, useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import type { FinOpsKPIs } from '../../types/finops';
import {
  fetchSavingsRate,
  fetchUnusedCommitment,
  type SavingsRate,
  type UnusedCommitment,
} from '../../api/curInsightsApi';

interface FinOpsKPISectionProps {
  kpis: FinOpsKPIs;
  /** Account to read the savings rate for, or null to omit that card. */
  curAccountId?: string | null;
  /**
   * Show the spend total alone, under the given heading.
   *
   * <p>For a custom date range, where the other cards would be wrong rather than merely
   * absent: forecast extrapolates to the end of the current month, "Last Month" is the
   * previous calendar month whatever the picker says, and potential savings comes from
   * scans of today's resources. Only the total describes the range actually asked for.
   */
  spendOnlyLabel?: string | null;
}

const formatCurrency = (value: number): string =>
  '$' + (value || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const splitValue = (str: string) => {
  const dot = str.lastIndexOf('.');
  return dot === -1 ? { main: str, cents: '' } : { main: str.slice(0, dot), cents: str.slice(dot) };
};

const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '32px',
  fontWeight: 400,
  lineHeight: '32px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

const CENTS_STYLE: React.CSSProperties = {
  fontFamily: "'Inter', sans-serif",
  fontSize: '14px',
  fontWeight: 400,
  lineHeight: '20px',
  color: 'rgb(10,10,10)',
};

const CLOUD_SVG = (
  <div className="absolute top-0 right-0 w-full h-full pointer-events-none opacity-[0.07]">
    <svg className="absolute -top-8 -right-12 w-32 h-32" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
      <path d="M140 70c0-15-12-27-27-27-3 0-6 1-9 2-6-14-20-24-36-24-17 0-31 10-36 24-3-1-6-2-9-2-15 0-27 12-27 27s12 27 27 27h117c15 0 27-12 27-27z"/>
    </svg>
    <svg className="absolute top-16 right-2 w-24 h-24" viewBox="0 0 200 120" fill="currentColor" style={{ color: '#6b7280' }}>
      <path d="M120 60c0-10-8-18-18-18-2 0-4 0-5 1-4-8-13-14-23-14s-19 6-23 14c-1-1-3-1-5-1-10 0-18 8-18 18s8 18 18 18h74c10 0 18-8 18-18z"/>
    </svg>
  </div>
);

interface KPICardProps {
  label: string;
  rawValue: number;
  icon: string;
  iconColor: string;
  iconBg: string;
  changePercent?: number | null;
  changeLabel?: string;
  isSavings?: boolean;
  index: number;
}

const KPICard: React.FC<KPICardProps> = ({ label, rawValue, icon, iconColor, iconBg, changePercent, changeLabel, isSavings, index }) => {
  const animRef = useRef<number | undefined>(undefined);
  const prevRawValue = useRef(rawValue);
  const [animCount, setAnimCount] = useState(rawValue);

  useEffect(() => {
    if (rawValue === prevRawValue.current) return;
    const prev = prevRawValue.current;
    prevRawValue.current = rawValue;
    if (animRef.current) cancelAnimationFrame(animRef.current);
    if (rawValue === 0) { setAnimCount(0); return; }
    const duration = 1050;
    const startTime = performance.now();
    const step = (now: number) => {
      const t = Math.min((now - startTime) / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3);
      setAnimCount(prev + eased * (rawValue - prev));
      if (t < 1) animRef.current = requestAnimationFrame(step);
      else setAnimCount(rawValue);
    };
    animRef.current = requestAnimationFrame(step);
    return () => { if (animRef.current) cancelAnimationFrame(animRef.current); };
  }, [rawValue]);

  const split = splitValue(formatCurrency(animCount));
  const hasChange = changePercent != null && !isNaN(changePercent) && isFinite(changePercent);
  const isGood = isSavings ? true : (changePercent ?? 0) < 0;
  const badgeText = hasChange ? `${(changePercent ?? 0) >= 0 ? '+' : ''}${Math.abs(changePercent ?? 0).toFixed(1)}%` : '';

  return (
    <div
      className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift"
      style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${0.05 + index * 0.09}s both` }}
    >
      {CLOUD_SVG}
      <div className="relative z-10">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
          {label}
        </p>
        <div className="flex items-end justify-between mb-3">
          <p className="leading-8 flex items-baseline gap-0.5">
            <span style={NUM_STYLE}>{split.main}</span>
            {split.cents && <span style={CENTS_STYLE}>{split.cents}</span>}
          </p>
          <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5" style={{ backgroundColor: iconBg }}>
            <Icon icon={icon} width={18} color={iconColor} />
          </div>
        </div>
        {hasChange && (
          <div className="flex items-center gap-2">
            <span className={`inline-flex items-center gap-0.5 text-[11px] font-bold ${isGood ? 'text-[#158045]' : 'text-[#DC2626]'}`}>
              <svg className="w-2.5 h-2.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3}
                  d={isGood ? "M13 17h8m0 0v-8m0 8l-8-8-4 4-6-6" : "M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"} />
              </svg>
              {badgeText}
            </span>
            <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{changeLabel}</span>
          </div>
        )}
        {!hasChange && changeLabel && (
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{changeLabel}</span>
        )}
      </div>
    </div>
  );
};

/**
 * Effective savings rate — what was paid against what the same usage would have cost at
 * public on-demand rates.
 *
 * Separate from KPICard because that formats every value as currency, and a rate rendered
 * as "$34.20" would read as a dollar amount. Shares the styling so the row stays uniform.
 *
 * Always reports a CLOSED month, never month-to-date. Reservation and Savings Plan fees
 * are booked at the start of a period while the usage they cover accrues across it, so a
 * mid-month rate reads far too low and then climbs — which looks like a bug and prompts
 * questions no answer satisfies. A finished month is the only honest comparison.
 */
const SavingsRateCard: React.FC<{ data: SavingsRate; periodLabel: string; index: number }> = ({
  data,
  periodLabel,
  index,
}) => {
  const percent = (data.rate || 0) * 100;

  return (
    <div
      className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift"
      style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${0.05 + index * 0.09}s both` }}
      title={
        `Discount achieved against AWS list price for ${periodLabel}.\n\n` +
        `Paid ${formatCurrency(data.amortizedCost)} for usage that would cost ` +
        `${formatCurrency(data.publicOnDemandCost)} at public on-demand rates.\n\n` +
        'Reservations and Savings Plans are spread across their term, so this reflects ' +
        'what the commitments actually earned rather than when they were billed.'
      }
    >
      {CLOUD_SVG}
      <div className="relative z-10">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
          Effective Savings Rate
        </p>
        <div className="flex items-end justify-between mb-3">
          <p className="leading-8 flex items-baseline gap-0.5">
            <span style={NUM_STYLE}>{percent.toFixed(1)}</span>
            <span style={CENTS_STYLE}>%</span>
          </p>
          <div
            className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5"
            style={{ backgroundColor: '#7C3AED14' }}
          >
            <Icon icon="mdi:sale" width={18} color="#7C3AED" />
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-0.5 text-[11px] font-bold text-[#158045]">
            {formatCurrency(data.savings)}
          </span>
          <span className="text-[11px] text-[rgba(0,0,0,0.35)]">saved in {periodLabel}</span>
        </div>
      </div>
    </div>
  );
};

/**
 * Commitment spend that bought nothing — reservation fees not matched by usage.
 *
 * Deliberately a dollar amount rather than a coverage percentage. "72% covered" tells
 * nobody whether to act; "$340 wasted last month" does.
 *
 * Reports a CLOSED month, like the savings rate and for a sharper reason: AWS computes
 * the unused portion of a period's fee against usage accrued so far, so on the 5th of a
 * month nearly the whole fee reads as unused simply because the month has not happened
 * yet. Mid-month this would look alarming every time and then quietly correct itself.
 */
const UnusedCommitmentCard: React.FC<{
  data: UnusedCommitment;
  periodLabel: string;
  index: number;
}> = ({ data, periodLabel, index }) => {
  const split = splitValue(formatCurrency(data.total));

  return (
    <div
      className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift"
      style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${0.05 + index * 0.09}s both` }}
      title={
        `Reservation and Savings Plan spend in ${periodLabel} that no usage matched.\n\n` +
        `Recurring fees: ${formatCurrency(data.unusedRecurringFee)}\n` +
        `Upfront (amortised): ${formatCurrency(data.unusedUpfrontFee)}\n\n` +
        'Money already paid that bought nothing — either the commitment is larger than ' +
        'the workload, or the instances it covers were stopped or resized.'
      }
    >
      {CLOUD_SVG}
      <div className="relative z-10">
        <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
          Unused Commitment
        </p>
        <div className="flex items-end justify-between mb-3">
          <p className="leading-8 flex items-baseline gap-0.5">
            <span style={NUM_STYLE}>{split.main}</span>
            {split.cents && <span style={CENTS_STYLE}>{split.cents}</span>}
          </p>
          <div
            className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mb-0.5"
            style={{ backgroundColor: '#DC262614' }}
          >
            <Icon icon="mdi:alert-circle-outline" width={18} color="#DC2626" />
          </div>
        </div>
        <span className="text-[11px] text-[rgba(0,0,0,0.35)]">
          reservations unused in {periodLabel}
        </span>
      </div>
    </div>
  );
};

const FinOpsKPISection: React.FC<FinOpsKPISectionProps> = ({ kpis, curAccountId = null, spendOnlyLabel = null }) => {
  const mtdSpend = kpis.monthToDateSpend || 0;
  const lastMonthSpend = kpis.lastMonthSpend || 0;
  const forecast = kpis.forecastedSpend || 0;
  const potentialSavings = kpis.potentialSavings || 0;

  const mtdChange = lastMonthSpend > 0 ? ((mtdSpend - lastMonthSpend) / lastMonthSpend) * 100 : null;
  const forecastChange = lastMonthSpend > 0 ? ((forecast - lastMonthSpend) / lastMonthSpend) * 100 : null;

  // Last complete month — see SavingsRateCard for why an open month is not shown.
  const closedMonth = React.useMemo(() => {
    const d = new Date();
    d.setDate(1);
    d.setMonth(d.getMonth() - 1);
    return {
      period: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`,
      label: d.toLocaleDateString('en-US', { month: 'long' }),
    };
  }, []);

  const [savingsRate, setSavingsRate] = useState<SavingsRate | null>(null);
  const [unusedCommitment, setUnusedCommitment] = useState<UnusedCommitment | null>(null);

  useEffect(() => {
    if (!curAccountId) {
      setUnusedCommitment(null);
      return;
    }
    let cancelled = false;
    fetchUnusedCommitment(curAccountId, closedMonth.period)
      .then((data) => {
        // Hidden at zero. That covers both "no reservations" and "perfectly utilised",
        // and the two are indistinguishable from the total alone — but a card reading
        // $0.00 forever on the many accounts with no commitments is pure clutter.
        //
        // `available !== false` rather than `=== true`: false means the report does not carry the
        // columns this is measured from, so the figures are zero and mean nothing — "cannot say"
        // outranks a non-zero total. An *absent* field means the source has no such concept, and
        // must keep rendering: the insight cache holds payloads for three days in Redis and
        // indefinitely in Postgres, so entries written before the flag existed legitimately lack it.
        if (!cancelled) {
          setUnusedCommitment(data && data.available !== false && data.total > 0 ? data : null);
        }
      })
      .catch(() => {
        if (!cancelled) setUnusedCommitment(null);
      });
    return () => {
      cancelled = true;
    };
  }, [curAccountId, closedMonth.period]);

  useEffect(() => {
    if (!curAccountId) {
      setSavingsRate(null);
      return;
    }
    let cancelled = false;
    fetchSavingsRate(curAccountId, closedMonth.period)
      .then((data) => {
        // Only render on a real result. A zero denominator means the report has no
        // on-demand pricing for that month, and "0.0%" would read as "we saved nothing"
        // rather than "this cannot be calculated".
        if (!cancelled) setSavingsRate(data && data.publicOnDemandCost > 0 ? data : null);
      })
      .catch(() => {
        // No report, or it could not be read. The card simply does not appear; the rest
        // of the FinOps page is unaffected.
        if (!cancelled) setSavingsRate(null);
      });
    return () => {
      cancelled = true;
    };
  }, [curAccountId, closedMonth.period]);

  // Widens only for the cards actually present, so the row never has a gap. Written as
  // whole class names because Tailwind scans source literally and cannot see a computed one.
  const extraCards = (savingsRate ? 1 : 0) + (unusedCommitment ? 1 : 0);
  const columns =
    extraCards === 2 ? 'lg:grid-cols-6' : extraCards === 1 ? 'lg:grid-cols-5' : 'lg:grid-cols-4';

  // A custom range: only the total is meaningful, so the rest of the row is dropped
  // rather than left showing current-month figures beside a historical chart.
  if (spendOnlyLabel) {
    return (
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <KPICard index={0} label={spendOnlyLabel} rawValue={mtdSpend} icon="mdi:currency-usd"
          iconColor="#4285F4" iconBg="#4285F414" changeLabel="selected range" />
      </div>
    );
  }

  return (
    <div className={`grid grid-cols-1 md:grid-cols-2 ${columns} gap-4`}>
      <KPICard index={0} label="Month-to-Date Spend" rawValue={mtdSpend}        icon="mdi:currency-usd"   iconColor="#4285F4" iconBg="#4285F414" changePercent={mtdChange}      changeLabel="vs last month" />
      <KPICard index={1} label="Forecasted Spend"     rawValue={forecast}        icon="mdi:chart-line"     iconColor="#F4A429" iconBg="#F4A42914" changePercent={forecastChange} changeLabel="vs last month" />
      <KPICard index={2} label="Last Month Spend"     rawValue={lastMonthSpend}  icon="mdi:calendar-month" iconColor="#6B7280" iconBg="#6B728014" changeLabel="prior period" />
      <KPICard index={3} label="Potential Savings"    rawValue={potentialSavings} icon="mdi:piggy-bank"    iconColor="#158045" iconBg="#15804514" changeLabel="available to reclaim" isSavings />
      {/* Sits beside Potential Savings on purpose: that card is what you could still
          save, this is what you already are. */}
      {savingsRate && (
        <SavingsRateCard index={4} data={savingsRate} periodLabel={closedMonth.label} />
      )}
      {/* Last, because it is the only card that is bad news — the row reads spend,
          forecast, history, opportunity, achievement, then waste. */}
      {unusedCommitment && (
        <UnusedCommitmentCard
          index={5}
          data={unusedCommitment}
          periodLabel={closedMonth.label}
        />
      )}
    </div>
  );
};

export default FinOpsKPISection;
