import React from 'react';
import { Icon } from '@iconify/react';
import type { ComplianceReport } from '../../types/compliance';
import { CARD_ANCHOR, EYEBROW, scoreBand, statusToken } from './complianceTokens';

interface Props {
  report: ComplianceReport;
  onSelectStatus: (status: 'FAIL' | 'PASS' | 'MANUAL' | 'WAIVED') => void;
}

/**
 * The lead panel: the score, what it is made of, and the provenance behind it.
 *
 * Three things this panel is careful about, each of them a bug it used to have:
 *
 *  - **The dial and the sentence beside it agree.** Both now read the same unweighted
 *    `passedControls / settledControls`, taken from the server's own denominator. The dial used
 *    to be severity-weighted while the caption was a raw control count, so the two routinely
 *    disagreed by a point or more and neither explained itself.
 *  - **Coverage sits next to the score, not in a footnote.** A percentage over a fraction of the
 *    control set is not a measurement, and the page has to say which one it is showing.
 *  - **Severity weighting is a separate, labelled number.** It answers "what should I fix first",
 *    which is a different question from "how much of the framework do I meet", and presenting it
 *    as the compliance figure was the original mistake.
 *
 * When the scan could not produce a scoreable result the score renders as "—" and the label says
 * "Unable to assess" — showing 0% would read as "you failed everything" rather than "we could not
 * measure this".
 */
const ComplianceScoreHeader: React.FC<Props> = ({ report, onSelectStatus }) => {
  const band = scoreBand(report.scoreLabel);
  const { counts } = report;

  const size = 128;
  const stroke = 8;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const rated = report.assessable && report.score != null;
  const pct = rated ? report.score! : 0;

  const scanned = report.scannedAt ? new Date(report.scannedAt) : null;

  const breakdown = [
    { key: 'FAIL' as const, label: 'Failing', value: counts.failedControls },
    { key: 'PASS' as const, label: 'Passing', value: counts.passedControls },
    { key: 'MANUAL' as const, label: 'Not evaluated', value: counts.manualControls },
    { key: 'WAIVED' as const, label: 'Waived', value: counts.waivedControls },
  ];

  /**
   * Secondary readouts. Coverage is always shown — it is the context that makes the score
   * legible. The other two appear only when they carry information: a risk score identical in
   * spirit to the compliance score on an account with uniform severities adds nothing, and an
   * exceptions figure is meaningless with no exceptions on file.
   */
  const secondary: Array<{ label: string; value: string; hint: string }> = [];

  if (report.coverage != null) {
    secondary.push({
      label: 'Coverage',
      value: `${report.coverage}%`,
      hint: `${counts.settledControls} of ${counts.totalControls} controls reached a pass, fail `
        + 'or waiver. The rest could not be decided by the scanner and are excluded from the '
        + 'score. Raising coverage moves the score independently of any remediation.',
    });
  }

  if (rated && report.riskWeightedScore != null) {
    secondary.push({
      label: 'Risk-weighted',
      value: `${report.riskWeightedScore}%`,
      hint: 'The same controls weighted by severity (critical 10, high 6, medium 3, low 1). '
        + 'A risk indicator for prioritising work — not a compliance figure, because no '
        + 'framework ranks its own controls.',
    });
  }

  if (rated && counts.waivedControls > 0 && report.scoreWithExceptions != null) {
    secondary.push({
      label: 'With exceptions',
      value: `${report.scoreWithExceptions}%`,
      hint: `Counting the ${counts.waivedControls} waived control(s) as met with a caveat, which `
        + 'is how an auditor reads an approved exception. The headline score leaves them in the '
        + 'denominator, so filing an exception can never raise it.',
    });
  }

  return (
    <section className={`${CARD_ANCHOR} p-5`}>
      <div className="flex flex-col lg:flex-row gap-6">
        {/* Score dial */}
        <div className="flex items-center gap-5 shrink-0">
          <div className="relative" style={{ width: size, height: size }}>
            <svg width={size} height={size} className="-rotate-90" aria-hidden="true">
              <circle
                cx={size / 2}
                cy={size / 2}
                r={radius}
                fill="none"
                stroke="rgba(0,0,0,0.06)"
                strokeWidth={stroke}
              />
              <circle
                cx={size / 2}
                cy={size / 2}
                r={radius}
                fill="none"
                className={band.ring}
                strokeWidth={stroke}
                strokeLinecap="round"
                strokeDasharray={circumference}
                strokeDashoffset={circumference * (1 - pct / 100)}
                style={{ transition: 'stroke-dashoffset 0.6s ease' }}
              />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
              <span className={`text-[34px] font-semibold leading-none tabular-nums ${band.text}`}>
                {rated ? `${report.score}%` : '—'}
              </span>
              <span className="text-[10px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)] mt-1.5">
                Controls met
              </span>
            </div>
          </div>

          <div className="min-w-0">
            {/*
              Named for what it actually measures. This figure pools every control in the scan,
              including ones mapped to no framework at all, so calling it "compliance posture"
              implied a per-framework statement it cannot make. Framework-level answers are one
              card down.
            */}
            <span className={EYEBROW}>Control posture</span>
            <p className={`text-[19px] font-semibold leading-tight mt-1 ${band.text}`}>
              {report.scoreLabel}
            </p>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)] leading-relaxed mt-1.5 max-w-[28ch]">
              {rated
                // Same numerator and denominator as the dial, so the two can never disagree.
                ? `${counts.passedControls.toLocaleString()} of `
                  + `${counts.settledControls.toLocaleString()} settled controls pass`
                  + (report.frameworks.length > 0
                    ? `, across ${report.frameworks.length} `
                      + `framework${report.frameworks.length === 1 ? '' : 's'}.`
                    : '.')
                : 'The scan did not settle enough controls to rate this account.'}
            </p>
            {(counts.newlyFailing > 0 || counts.newlyPassing > 0) && (
              <div className="flex items-center gap-3 mt-2.5">
                {counts.newlyFailing > 0 && (
                  <span className="inline-flex items-center gap-1 text-[11.5px] font-medium text-red-600">
                    <Icon icon="mdi:trending-down" width={13} />
                    {counts.newlyFailing} newly failing
                  </span>
                )}
                {counts.newlyPassing > 0 && (
                  <span className="inline-flex items-center gap-1 text-[11.5px] font-medium text-emerald-600">
                    <Icon icon="mdi:trending-up" width={13} />
                    {counts.newlyPassing} fixed
                  </span>
                )}
              </div>
            )}
          </div>
        </div>

        {/* Breakdown — each tile filters the list, so the number and the rows agree */}
        <div className="flex-1 min-w-0 grid grid-cols-2 sm:grid-cols-4 gap-2 self-start">
          {breakdown.map(({ key, label, value }) => {
            const token = statusToken(key);
            return (
              <button
                key={key}
                type="button"
                onClick={() => onSelectStatus(key)}
                title={token.description}
                className="text-left px-3 py-2.5 rounded-lg border border-[#e5e7eb] bg-white hover:border-[rgba(0,0,0,0.35)] transition-colors group"
              >
                <div className="flex items-center gap-1.5">
                  <Icon icon={token.icon} width={13} className="text-[rgba(0,0,0,0.35)]" />
                  <span className="text-[10.5px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)] truncate">
                    {label}
                  </span>
                </div>
                <p className="text-[22px] font-semibold text-[rgba(0,0,0,0.88)] tabular-nums leading-none mt-1.5">
                  {value.toLocaleString()}
                </p>
                <span className="text-[10.5px] text-[rgba(0,0,0,0.3)] group-hover:text-[rgba(0,0,0,0.6)] transition-colors">
                  controls
                </span>
              </button>
            );
          })}

          {/* Secondary measures, deliberately smaller than the headline they qualify */}
          {secondary.length > 0 && (
            <div className="col-span-2 sm:col-span-4 flex flex-wrap items-stretch gap-2">
              {secondary.map(({ label, value, hint }) => (
                <div
                  key={label}
                  title={hint}
                  className="flex-1 min-w-[132px] px-3 py-2 rounded-lg border border-[#e5e7eb] bg-white"
                >
                  <div className="flex items-center gap-1">
                    <span className="text-[10px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)] truncate">
                      {label}
                    </span>
                    <Icon
                      icon="mdi:help-circle-outline"
                      width={11}
                      className="text-[rgba(0,0,0,0.25)] shrink-0"
                    />
                  </div>
                  <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.7)] tabular-nums leading-none mt-1.5">
                    {value}
                  </p>
                </div>
              ))}
            </div>
          )}

          {/* Provenance */}
          <div className="col-span-2 sm:col-span-4 flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2 rounded-lg bg-[#f9fafb] border border-[#e5e7eb]">
            <Provenance
              icon="mdi:clock-outline"
              label={scanned ? scanned.toLocaleString() : 'never scanned'}
            />
            <Provenance
              icon="mdi:earth"
              label={report.regions.length > 0
                ? `${report.regions.length} region${report.regions.length === 1 ? '' : 's'}: ${report.regions.join(', ')}`
                : 'account-wide'}
            />
            <Provenance
              icon="mdi:shield-check-outline"
              label={`${report.frameworks.length} framework${report.frameworks.length === 1 ? '' : 's'}`}
            />
            {/* Resource counts, labelled as such — these are not controls */}
            <Provenance
              icon="mdi:counter"
              label={`${counts.failedResources.toLocaleString()} failing / ${
                (counts.failedResources + counts.passedResources).toLocaleString()} resource checks`}
            />
            {report.scannerVersion && (
              <Provenance icon="mdi:tag-outline" label={`Prowler ${report.scannerVersion}`} />
            )}
          </div>
        </div>
      </div>

      {/* Caveats about this specific report, straight from the backend */}
      {report.notes.length > 0 && (
        <ul className="mt-4 pt-3.5 border-t border-[rgba(0,0,0,0.06)] space-y-1.5">
          {report.notes.map((note) => (
            <li key={note} className="flex items-start gap-2">
              <Icon
                icon="mdi:information-outline"
                width={13}
                className="text-[rgba(0,0,0,0.3)] mt-[2px] shrink-0"
              />
              <span className="text-[11.5px] text-[rgba(0,0,0,0.5)] leading-relaxed">{note}</span>
            </li>
          ))}
        </ul>
      )}

      {report.scoreMethod && (
        <p className="mt-3 text-[10.5px] text-[rgba(0,0,0,0.3)] leading-relaxed">
          {report.scoreMethod} A score is a coverage indicator, not a certification —
          formal compliance requires an independent auditor&apos;s attestation.
        </p>
      )}
    </section>
  );
};

const Provenance: React.FC<{ icon: string; label: string }> = ({ icon, label }) => (
  <span className="inline-flex items-center gap-1.5 text-[10.5px] font-medium text-[rgba(0,0,0,0.45)] min-w-0">
    <Icon icon={icon} width={12} className="text-[rgba(0,0,0,0.3)] shrink-0" />
    <span className="truncate max-w-[42ch]">{label}</span>
  </span>
);

export default ComplianceScoreHeader;
