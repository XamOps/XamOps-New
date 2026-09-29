import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchChargesByService, type ServiceCharge } from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import { awsRegionName } from '../../utils/awsRegionNames';

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
  /**
   * Replaces this section's own `h3`.
   *
   * <p>Used to hoist the sub-tab control into the card header, so switching between this and
   * the resource tree happens where the title is rather than in a strip above the card. The
   * control's own active label reads as the heading, which is why this substitutes for the
   * title rather than sitting beside it — two things both saying "Charges by service" is one
   * too many.
   */
  heading?: React.ReactNode;
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
 *
 * <p>The card is marked with `data-printing` and everything else is hidden with
 * `display: none` — not `visibility: hidden`. Visibility toggling keeps the hidden nodes
 * in the layout, so the printed card still had to share the page with a full-height,
 * invisible app; combined with the reveal animation's `transform` on an ancestor (which
 * re-roots `position: absolute` and clips overflow), only the on-screen viewport made it
 * onto paper. Removing the other nodes from layout and neutralising every ancestor's
 * transform, overflow and height lets the full, expanded card flow across as many pages
 * as it needs.
 */
const PRINT_STYLE_ID = 'charges-print-style';
const PRINT_CSS = `
@media print {
  /* Hide everything, then reveal only the card and its ancestors. Ancestors must stay
     rendered so the card is not inside a display:none subtree. */
  body * { display: none !important; }
  html, body { background: #fff !important; }
  [data-charges-print-ancestor],
  [data-charges-print-ancestor] > #charges-by-service {
    display: block !important;
  }
  #charges-by-service, #charges-by-service * { display: revert !important; }
  #charges-by-service table { display: table !important; width: 100% !important; }
  #charges-by-service thead { display: table-header-group !important; }
  #charges-by-service tbody { display: table-row-group !important; }
  #charges-by-service tr { display: table-row !important; }
  #charges-by-service td, #charges-by-service th { display: table-cell !important; }

  /* Neutralise anything on an ancestor that would clip or re-root the card:
     the reveal animation's transform/opacity, scroll containers, fixed heights. */
  [data-charges-print-ancestor] {
    position: static !important;
    transform: none !important;
    opacity: 1 !important;
    overflow: visible !important;
    max-height: none !important;
    height: auto !important;
    filter: none !important;
    clip-path: none !important;
    margin: 0 !important;
    padding: 0 !important;
    width: auto !important;
  }

  #charges-by-service {
    border: none !important; box-shadow: none !important; padding: 0 !important;
    width: 100% !important;
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

/**
 * CUR carries region codes; the invoice shows display names. Shared with the BillOps
 * invoice detail page, which lists these same lines.
 */
const regionLabel = awsRegionName;

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

const ChargesByServiceSection: React.FC<ChargesByServiceSectionProps> = ({
  accountId,
  heading,
}) => {
  const [open, setOpen] = useState<Set<string>>(new Set());
  /**
   * True only for the instant the print dialog is capturing the card. It forces every row
   * to render expanded on paper without touching `open`, so the on-screen tree keeps
   * whatever the reader had opened — the PDF is fully expanded, the UI is not.
   */
  const [printing, setPrinting] = useState(false);

  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState<string>(months[0].value);
  const monthLabel = months.find((m) => m.value === period)?.label ?? '';
  const isCurrentMonth = period === months[0].value;

  /**
   * Prints the whole bill, fully expanded, without changing what is on screen.
   *
   * A collapsed section prints as a list of service totals, which is not what anyone asks
   * a bill for — so `printing` forces every row open for the capture. The reader's own
   * expand/collapse state (`open`) is never touched: the PDF is fully expanded, the page
   * they return to looks exactly as they left it.
   *
   * The card's ancestors are tagged with `data-charges-print-ancestor` so the print CSS
   * can un-clip them (the reveal animation puts a `transform` on one of them, which would
   * otherwise crop the output to a single screen). The tag and the flag are both cleared
   * once the dialog closes, whether the reader saved or cancelled.
   */
  const handleDownload = () => {
    if (!document.getElementById(PRINT_STYLE_ID)) {
      const style = document.createElement('style');
      style.id = PRINT_STYLE_ID;
      style.textContent = PRINT_CSS;
      document.head.appendChild(style);
    }

    const card = document.getElementById('charges-by-service');
    const tagged: HTMLElement[] = [];
    for (let el = card?.parentElement ?? null; el; el = el.parentElement) {
      el.setAttribute('data-charges-print-ancestor', '');
      tagged.push(el);
    }
    if (card) card.setAttribute('data-charges-print-ancestor', '');

    const cleanup = () => {
      setPrinting(false);
      for (const el of tagged) el.removeAttribute('data-charges-print-ancestor');
      card?.removeAttribute('data-charges-print-ancestor');
      window.removeEventListener('afterprint', cleanup);
    };
    window.addEventListener('afterprint', cleanup);

    setPrinting(true);
    // Two frames so React has painted the fully expanded tree before the dialog captures it.
    requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        window.print();
        // Fallback for browsers that do not fire `afterprint` reliably.
        setTimeout(cleanup, 0);
      }),
    );
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
          {heading ?? <h3 className="text-[15px] font-bold text-black">Charges by service</h3>}
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-1">
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
              // `printing` forces every node open for the PDF; the live `open` set is untouched.
              const svcOpen = printing || open.has(svc.service);
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
                      const rgnOpen = printing || open.has(rgnKey);
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
