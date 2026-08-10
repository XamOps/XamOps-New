import React from 'react';
import { Icon } from '@iconify/react';
import type { WarReport } from '../../types/war';
import { CARD_ANCHOR, RISK_LEVEL_TOKENS } from './warTokens';

interface Props {
  report: WarReport;
}

/**
 * The page's anchor panel: one score, the verdict behind it, and the counts that
 * justify both.
 *
 * Uses the anchor card chrome (black left edge, lifted shadow) that the ticket detail
 * page gives its lead panel, so the eye starts here rather than wandering.
 *
 * The score is a weighted pass rate over verified controls only. That caveat is stated
 * on the card rather than buried in a tooltip, because a security score nobody can
 * explain is a security score nobody trusts.
 */
const WarScoreHeader: React.FC<Props> = ({ report }) => {
  const token = RISK_LEVEL_TOKENS[report.riskLevel] ?? RISK_LEVEL_TOKENS.MEDIUM;

  const failed = report.statusCounts.FAIL ?? 0;
  const warned = report.statusCounts.WARN ?? 0;
  const unknown = report.statusCounts.UNKNOWN ?? 0;
  const passed = report.statusCounts.PASS ?? 0;
  const manual = report.statusCounts.MANUAL ?? 0;

  const criticals = report.severityCounts.Critical ?? 0;
  const highs = report.severityCounts.High ?? 0;

  /** Controls that could have produced a verdict — the honest denominator for coverage. */
  const scoreable = report.verifiedControls + unknown;
  const coverage = scoreable > 0
    ? Math.round((report.verifiedControls / scoreable) * 100)
    : 0;

  // Circumference of an r=52 circle, for the progress ring dash offset.
  const circumference = 2 * Math.PI * 52;
  // An unassessable scan leaves the ring empty rather than drawing a 0% arc, which
  // would read as a measured result.
  const offset = report.assessable
    ? circumference - (report.warScore / 100) * circumference
    : circumference;

  return (
    <div className={`${CARD_ANCHOR} p-6`}>
      <div className="flex flex-col lg:flex-row lg:items-center gap-7">
        {/* Score ring */}
        <div className="flex items-center gap-5 shrink-0">
          <div className="relative w-[116px] h-[116px]">
            <svg className="w-[116px] h-[116px] -rotate-90" viewBox="0 0 120 120" aria-hidden="true">
              <circle
                cx="60" cy="60" r="52" fill="none" strokeWidth="9"
                className="stroke-[rgba(0,0,0,0.06)]"
              />
              <circle
                cx="60" cy="60" r="52" fill="none" strokeWidth="9" strokeLinecap="round"
                strokeDasharray={circumference}
                strokeDashoffset={offset}
                className={`${token.ring} transition-[stroke-dashoffset] duration-700`}
              />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span
                className={`text-[30px] font-bold leading-none tabular-nums ${
                  report.assessable ? 'text-black' : 'text-[rgba(0,0,0,0.45)]'
                }`}
              >
                {report.assessable ? report.warScore : '—'}
              </span>
              <span className="text-[9.5px] font-semibold text-[rgba(0,0,0,0.55)] uppercase tracking-widest mt-1">
                WAR score
              </span>
            </div>
          </div>

          <div className="max-w-[16rem]">
            <div className={`flex items-center gap-1.5 text-[13px] font-semibold ${token.text}`}>
              <Icon
                icon={report.assessable ? 'mdi:shield-alert-outline' : 'mdi:shield-search'}
                width={16}
              />
              <span>{token.label}</span>
            </div>
            <p className="mt-1.5 text-[12px] text-[rgba(0,0,0,0.6)] leading-relaxed">
              {report.assessable ? (
                <>
                  Weighted pass rate across the {report.verifiedControls} controls this scan
                  verified against live account state. Manual and not-applicable controls
                  are excluded.
                </>
              ) : report.verifiedControls === 0 ? (
                <>
                  No control could be verified against live account state, so there is
                  nothing to score. This is a scan problem, not a verdict on your security
                  posture.
                </>
              ) : (
                <>
                  Only {report.verifiedControls} of {scoreable} checkable controls produced
                  a verdict. Too little coverage to score — the rest were blocked, not
                  failed.
                </>
              )}
            </p>
          </div>
        </div>

        {/* Counts */}
        <div className="flex-1 grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-5 gap-2.5">
          <Stat
            label="Exposed" value={failed} icon="mdi:shield-off-outline"
            tone="text-red-600" hint="Usable today"
          />
          <Stat
            label="Weakened" value={warned} icon="mdi:shield-alert-outline"
            tone="text-amber-600" hint="Partly mitigated"
          />
          <Stat
            label="Unverified" value={unknown} icon="mdi:shield-search"
            tone="text-[rgba(0,0,0,0.55)]"
            // Only blame permissions when something was actually denied.
            hint={report.deniedActions.length > 0 ? 'Missing permissions' : 'Blocked before auth'}
          />
          <Stat
            label="Protected" value={passed} icon="mdi:shield-check-outline"
            tone="text-emerald-600" hint="Verified compliant"
          />
          <Stat
            label="Manual" value={manual} icon="mdi:account-search-outline"
            tone="text-sky-600" hint="No API can prove"
          />
        </div>
      </div>

      {/* Context strip */}
      <div className="mt-5 pt-4 border-t border-[rgba(0,0,0,0.06)] flex flex-wrap items-center gap-x-5 gap-y-2">
        <Meta icon="mdi:cloud-outline">
          <span className="font-semibold text-[rgba(0,0,0,0.72)]">{report.provider}</span>
          <span className="font-mono text-[10.5px] text-[rgba(0,0,0,0.55)]">
            {report.accountId}
          </span>
          {report.accountName && (
            <span className="text-[rgba(0,0,0,0.6)]">{report.accountName}</span>
          )}
        </Meta>

        <Meta icon="mdi:map-marker-outline">
          {report.scannedRegions.length} region
          {report.scannedRegions.length === 1 ? '' : 's'}
          <span className="font-mono text-[10.5px] text-[rgba(0,0,0,0.55)]">
            {report.scannedRegions.join(', ')}
          </span>
        </Meta>

        <Meta icon="mdi:clock-outline">{report.generatedAt}</Meta>

        {/* Coverage is stated unconditionally. A score without it invites the reader to
            assume the whole baseline was checked. */}
        <Meta icon="mdi:progress-check" emphasis={!report.assessable}>
          {report.verifiedControls} of {scoreable} verified
          {scoreable > 0 && ` · ${coverage}%`}
        </Meta>

        {report.assessable && (criticals > 0 || highs > 0) && (
          <span className="flex items-center gap-1.5 text-[11.5px] font-semibold text-red-600">
            <Icon icon="mdi:alert-circle" width={13} />
            {criticals} critical · {highs} high open
          </span>
        )}
      </div>
    </div>
  );
};

const Stat: React.FC<{
  label: string;
  value: number;
  hint: string;
  icon: string;
  tone: string;
}> = ({ label, value, hint, icon, tone }) => (
  <div className="border border-[#e5e7eb] rounded-lg p-2.5">
    <div className={`flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-widest ${tone}`}>
      <Icon icon={icon} width={12} />
      <span className="truncate">{label}</span>
    </div>
    <div className="mt-1 text-[22px] font-bold text-black leading-none tabular-nums">{value}</div>
    <div className="text-[10px] text-[rgba(0,0,0,0.55)] leading-tight mt-1 truncate">{hint}</div>
  </div>
);

const Meta: React.FC<{
  icon: string;
  emphasis?: boolean;
  children: React.ReactNode;
}> = ({ icon, emphasis, children }) => (
  <span
    className={`flex items-center gap-1.5 text-[11.5px] ${
      emphasis ? 'font-semibold text-[rgba(0,0,0,0.6)]' : 'text-[rgba(0,0,0,0.6)]'
    }`}
  >
    <Icon icon={icon} width={13} className="text-[rgba(0,0,0,0.55)] shrink-0" />
    {children}
  </span>
);

export default WarScoreHeader;
