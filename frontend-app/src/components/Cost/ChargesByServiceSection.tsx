import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchChargesByService, type ServiceCharge } from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import { AWS_REGIONS } from '../../types/flexCompute';

/**
 * Charges by service, in the exact shape of the AWS invoice:
 *
 * <pre>
 *   Simple Storage Service                                    USD 2,527.13
 *     Asia Pacific (Mumbai)                                   USD 2,527.13
 *       ...APS3-Requests-Tier1                                USD   735.64
 *         $0.005 per 1,000 PUT, COPY, POST or LIST requests
 *                                    147,128,739 Requests     USD   735.64
 * </pre>
 *
 * Only a Cost and Usage Report can produce this. Cost Explorer reports dollars, and the
 * usage quantity it exposes cannot be lined up against the same rows — here quantity, unit
 * and cost sit on every line already.
 *
 * <p>Four levels rather than a flat table because that is how the bill reads, and because
 * a real account produces hundreds of rate lines: without the hierarchy, S3 alone fills
 * the screen before EC2 appears.
 */

interface ChargesByServiceSectionProps {
  /** Single AWS account with a report, or null to render nothing. */
  accountId: string | null;
}

/** The last 12 months, newest first. */
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

/**
 * Prints this section alone, via the browser's own PDF export.
 *
 * Chosen over a PDF library because a bill runs to hundreds of rows across four nesting
 * levels: page breaks, repeated headers and long-table pagination all come free from the
 * print engine, where a generated PDF would need each hand-built. It also keeps the
 * on-screen styling rather than reimplementing it.
 */
const PRINT_STYLE_ID = 'charges-print-style';
const PRINT_CSS = `
@media print {
  body * { visibility: hidden !important; }
  #charges-by-service, #charges-by-service * { visibility: visible !important; }
  #charges-by-service {
    position: absolute; left: 0; top: 0; width: 100%;
    border: none !important; box-shadow: none !important; padding: 0 !important;
  }
  /* Controls are meaningless on paper. */
  #charges-by-service .no-print { display: none !important; }
  /* Keep a service and its first rows together rather than orphaning a heading. */
  #charges-by-service tr { page-break-inside: avoid; }
  @page { margin: 14mm; }
}`;

const money = (v: number): string =>
  `USD ${v < 0 ? '-' : ''}${Math.abs(v).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;

/**
 * Usage quantities span a huge range — 5.033 Tag-Mo to 147,128,739 Requests — so a fixed
 * number of decimals is unreadable at one end and misleading at the other.
 */
const quantity = (v: number): string => {
  if (v === 0) return '0';
  const abs = Math.abs(v);
  if (abs >= 1000) return v.toLocaleString('en-US', { maximumFractionDigits: 0 });
  if (abs >= 1) return v.toLocaleString('en-US', { maximumFractionDigits: 3 });
  // Below 1, fewer decimals would render most values as "0.00".
  return v.toLocaleString('en-US', { maximumFractionDigits: 6 });
};

/** CUR carries region codes; the invoice shows display names. Reuses the existing map. */
const REGION_LABELS: Record<string, string> = Object.fromEntries(
  AWS_REGIONS.map((r) => [r.value, r.label]),
);
const regionLabel = (code: string): string =>
  code === 'Global' ? 'Global' : (REGION_LABELS[code] ?? code);

interface UsageTypeNode {
  usageType: string;
  cost: number;
  lines: ServiceCharge[];
}
interface RegionNode {
  region: string;
  cost: number;
  usageTypes: UsageTypeNode[];
}
interface ServiceNode {
  service: string;
  cost: number;
  regions: RegionNode[];
}

const ChargesByServiceSection: React.FC<ChargesByServiceSectionProps> = ({ accountId }) => {
  const [open, setOpen] = useState<Set<string>>(new Set());

  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState<string>(months[0].value);
  const monthLabel = months.find((m) => m.value === period)?.label ?? '';
  const isCurrentMonth = period === months[0].value;

  /**
   * Expands every row before printing.
   *
   * A collapsed section prints as a list of service totals, which is not what anyone
   * asks a bill for. State is left expanded afterwards rather than restored — silently
   * re-collapsing after the dialog closes reads as the page resetting itself.
   */
  const handleDownload = () => {
    const all = new Set<string>();
    for (const svc of tree) {
      all.add(svc.service);
      for (const rgn of svc.regions) all.add(`${svc.service}|${rgn.region}`);
    }
    setOpen(all);

    if (!document.getElementById(PRINT_STYLE_ID)) {
      const style = document.createElement('style');
      style.id = PRINT_STYLE_ID;
      style.textContent = PRINT_CSS;
      document.head.appendChild(style);
    }
    // One frame so React has painted the expanded rows before the dialog captures them.
    requestAnimationFrame(() => requestAnimationFrame(() => window.print()));
  };

  // Cached across mounts, so returning to this page does not re-scan the report. A
  // failure leaves `charges` null and the section hides itself, rather than showing an
  // error the reader can do nothing about.
  const { data, isPending, isError } = useCurInsight(
    'charges-by-service',
    accountId,
    period,
    fetchChargesByService,
  );
  const charges: ServiceCharge[] | null = isError ? null : (data ?? null);
  // Only the first load blocks. A background revalidation keeps the table on screen
  // instead of replacing it with a skeleton showing the same numbers.
  const loading = Boolean(accountId) && isPending;

  const tree: ServiceNode[] = useMemo(() => {
    if (!charges) return [];
    const services = new Map<string, Map<string, Map<string, ServiceCharge[]>>>();
    for (const c of charges) {
      const regions = services.get(c.service) ?? new Map();
      const usageTypes = regions.get(c.region) ?? new Map();
      const lines = usageTypes.get(c.usageType) ?? [];
      lines.push(c);
      usageTypes.set(c.usageType, lines);
      regions.set(c.region, usageTypes);
      services.set(c.service, regions);
    }

    const sum = (lines: ServiceCharge[]) => lines.reduce((s, l) => s + l.cost, 0);

    return [...services.entries()]
      .map(([service, regionMap]) => {
        const regions = [...regionMap.entries()]
          .map(([region, usageMap]) => {
            const usageTypes = [...usageMap.entries()]
              .map(([usageType, lines]) => ({ usageType, cost: sum(lines), lines }))
              .sort((a, b) => b.cost - a.cost);
            return {
              region,
              cost: usageTypes.reduce((s, u) => s + u.cost, 0),
              usageTypes,
            };
          })
          .sort((a, b) => b.cost - a.cost);
        return {
          service,
          cost: regions.reduce((s, r) => s + r.cost, 0),
          regions,
        };
      })
      .sort((a, b) => b.cost - a.cost);
  }, [charges]);

  if (!accountId) return null;

  const total = tree.reduce((s, n) => s + n.cost, 0);
  const isEmpty = !loading && (!charges || charges.length === 0);
  // Nothing to show and nothing chosen yet — the account may simply have no report.
  if (isEmpty && isCurrentMonth) return null;
  const toggle = (key: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  return (
    <div
      id="charges-by-service"
      className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6"
    >
      <div className="flex items-start justify-between gap-4 mb-4 flex-wrap">
        <div>
          <h3 className="text-[15px] font-bold text-black">Charges by service</h3>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
            {monthLabel}
            {isCurrentMonth ? ' so far' : ''}, in the same breakdown as your AWS bill.
            {!isEmpty && ' Click to expand.'}
          </p>
          {/* Printed copies lose the account context otherwise. */}
          <p className="hidden print:block text-[11px] text-[rgba(0,0,0,0.5)] mt-1">
            Account {accountId} · {monthLabel}
          </p>
        </div>

        <div className="flex items-center gap-3 flex-wrap">
          <select
            value={period}
            onChange={(e) => setPeriod(e.target.value)}
            aria-label="Billing month"
            className="no-print px-3 py-1.5 text-[12.5px] border border-[rgba(0,0,0,0.15)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5"
          >
            {months.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>

          <button
            type="button"
            onClick={handleDownload}
            disabled={isEmpty}
            title="Expands every row, then opens your browser's print dialog — choose 'Save as PDF'"
            className="no-print inline-flex items-center gap-1.5 px-3 py-1.5 text-[12.5px] font-medium border border-[rgba(0,0,0,0.15)] rounded-lg hover:border-[rgba(0,0,0,0.35)] transition-colors disabled:opacity-40"
          >
            <Icon icon="mdi:file-pdf-box" width={15} />
            Download PDF
          </button>

          <div className="text-right flex-shrink-0">
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] uppercase tracking-wide">Total</p>
            <p className="text-[18px] font-bold text-black">{money(total)}</p>
          </div>
        </div>
      </div>

      {loading && (
        <p className="text-[12.5px] text-[rgba(0,0,0,0.4)] py-6 text-center">
          Reading the Cost and Usage Report…
        </p>
      )}

      {isEmpty && !loading && (
        <div className="flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
          <Icon
            icon="mdi:information-outline"
            width={15}
            className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
          />
          <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            No charges found for {monthLabel}. A Cost and Usage Report only covers months
            from when it was created onward — it does not backfill, so earlier months are
            not in it.
          </div>
        </div>
      )}

      {!isEmpty && !loading && (
      <div className="overflow-x-auto -mx-2">
        <table className="w-full min-w-[640px] text-[12.5px]">
          <tbody>
            {tree.map((svc) => {
              const svcOpen = open.has(svc.service);
              return (
                <React.Fragment key={svc.service}>
                  {/* Level 1 — service */}
                  <tr
                    className="border-t-2 border-[rgba(0,0,0,0.1)] cursor-pointer hover:bg-[rgba(0,0,0,0.02)]"
                    onClick={() => toggle(svc.service)}
                  >
                    <td className="px-2 py-2.5 font-bold text-black">
                      <span className="inline-flex items-center gap-1.5">
                        <Icon
                          icon={svcOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                          width={14}
                          className="text-[rgba(0,0,0,0.35)]"
                        />
                        {svc.service}
                      </span>
                    </td>
                    <td className="px-2 py-2.5 text-right" />
                    <td className="px-2 py-2.5 text-right font-bold text-black whitespace-nowrap">
                      {money(svc.cost)}
                    </td>
                  </tr>

                  {svcOpen &&
                    svc.regions.map((rgn) => {
                      const rgnKey = `${svc.service}|${rgn.region}`;
                      const rgnOpen = open.has(rgnKey);
                      return (
                        <React.Fragment key={rgnKey}>
                          {/* Level 2 — region */}
                          <tr
                            className="border-t border-[rgba(0,0,0,0.05)] cursor-pointer hover:bg-[rgba(0,0,0,0.02)]"
                            onClick={() => toggle(rgnKey)}
                          >
                            <td className="px-2 py-2 pl-7 font-semibold text-[rgba(0,0,0,0.8)]">
                              <span className="inline-flex items-center gap-1.5">
                                <Icon
                                  icon={rgnOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                                  width={12}
                                  className="text-[rgba(0,0,0,0.3)]"
                                />
                                {regionLabel(rgn.region)}
                              </span>
                            </td>
                            <td className="px-2 py-2 text-right" />
                            <td className="px-2 py-2 text-right font-semibold text-[rgba(0,0,0,0.8)] whitespace-nowrap">
                              {money(rgn.cost)}
                            </td>
                          </tr>

                          {rgnOpen &&
                            rgn.usageTypes.map((ut) => (
                              <React.Fragment key={`${rgnKey}|${ut.usageType}`}>
                                {/* Level 3 — usage type */}
                                <tr className="border-t border-[rgba(0,0,0,0.04)]">
                                  <td className="px-2 py-1.5 pl-14 font-medium text-[rgba(0,0,0,0.7)]">
                                    {ut.usageType || '—'}
                                  </td>
                                  <td className="px-2 py-1.5 text-right" />
                                  <td className="px-2 py-1.5 text-right font-medium text-[rgba(0,0,0,0.7)] whitespace-nowrap">
                                    {money(ut.cost)}
                                  </td>
                                </tr>

                                {/* Level 4 — the rate lines */}
                                {ut.lines.map((line, i) => (
                                  <tr
                                    key={`${rgnKey}|${ut.usageType}|${i}`}
                                    className="bg-[rgba(0,0,0,0.012)]"
                                  >
                                    <td className="px-2 py-1 pl-20 text-[11.5px] text-[rgba(0,0,0,0.55)]">
                                      {line.description}
                                    </td>
                                    <td className="px-2 py-1 text-right text-[11.5px] text-[rgba(0,0,0,0.55)] tabular-nums whitespace-nowrap">
                                      {quantity(line.quantity)}
                                      {line.unit && (
                                        <span className="text-[rgba(0,0,0,0.35)] ml-1">
                                          {line.unit}
                                        </span>
                                      )}
                                    </td>
                                    <td className="px-2 py-1 text-right text-[11.5px] text-[rgba(0,0,0,0.6)] tabular-nums whitespace-nowrap">
                                      {money(line.cost)}
                                    </td>
                                  </tr>
                                ))}
                              </React.Fragment>
                            ))}
                        </React.Fragment>
                      );
                    })}
                </React.Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
      )}

      {!isEmpty && !loading && (
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
          Includes tax, fees and credits, so this reconciles to your invoice. Negative amounts
          are credits, refunds, or usage covered by a Savings Plan or Reserved Instance.
        </p>
      )}
    </div>
  );
};

export default ChargesByServiceSection;
