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
 * The provenance row is not decoration. A score is only meaningful alongside the regions
 * and framework versions the scan covered and when it ran, so those travel with the
 * number rather than living in a tooltip. When the scan could not produce a scoreable
 * result the score renders as "—" and the label says "Unable to assess" — the previous
 * page would have shown 0%, which reads as "you failed everything" rather than "we
 * could not measure this".
 */
const ComplianceScoreHeader: React.FC<Props> = ({ report, onSelectStatus }) => {
  const band = scoreBand(report.scoreLabel);
  const { counts } = report;

  const size = 128;
  const stroke = 8;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const pct = report.assessable && report.score != null ? report.score : 0;

  const scanned = report.scannedAt ? new Date(report.scannedAt) : null;

  const breakdown = [
    { key: 'FAIL' as const, label: 'Failing', value: counts.failedControls },
    { key: 'PASS' as const, label: 'Passing', value: counts.passedControls },
    { key: 'MANUAL' as const, label: 'Not evaluated', value: counts.manualControls },
    { key: 'WAIVED' as const, label: 'Waived', value: counts.waivedControls },
  ];

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
                {report.assessable && report.score != null ? `${report.score}%` : '—'}
              </span>
              <span className="text-[10px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)] mt-1.5">
                Score
              </span>
            </div>
          </div>

          <div className="min-w-0">
            <span className={EYEBROW}>Compliance posture</span>
            <p className={`text-[19px] font-semibold leading-tight mt-1 ${band.text}`}>
              {report.scoreLabel}
            </p>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)] leading-relaxed mt-1.5 max-w-[26ch]">
              {report.assessable
                ? `${counts.passedControls} of ${counts.passedControls + counts.failedControls} `
                  + 'evaluated controls are passing.'
                : 'The scan did not evaluate enough controls to rate this account.'}
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
