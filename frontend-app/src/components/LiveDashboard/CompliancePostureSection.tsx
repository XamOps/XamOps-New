/**
 * Compliance posture widget — takes the dashboard slot previously held by
 * "Cost by Region" (a duplicate of the Top Services region toggle).
 *
 * Reads GET /api/xamops/compliance/report, which already assembles the whole
 * ComplianceOps page server-side, so this needs no new backend work.
 *
 * Two behaviours copied deliberately from the backend DTO's intent:
 *  - `assessable === false` renders as "—", never as zero. "We could not measure
 *    this" and "you failed everything" are different statements.
 *  - `waivedControls` is surfaced next to the score so the number cannot be
 *    quietly inflated by waiving controls.
 */

import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { Area, AreaChart, ResponsiveContainer } from 'recharts';
import apiClient from '../../utils/apiClient';

interface CompliancePostureSectionProps {
  accountId: string | null;
}

// Subset of com.xammer.cloud.dto.compliance.ComplianceReport
interface TrendPoint {
  at: string | null;
  score: number | null;
}

interface ControlView {
  checkId: string;
  title: string;
  severity: string;
  status: string;
}

interface ComplianceCounts {
  totalControls: number;
  passedControls: number;
  failedControls: number;
  waivedControls: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
  newlyFailing: number;
}

interface ComplianceReport {
  score: number | null;
  assessable: boolean;
  scoreLabel: string | null;
  scanStatus: string | null;
  scannedAt: string | null;
  counts: ComplianceCounts;
  topRisks: ControlView[];
  trend: TrendPoint[];
  notes: string[];
}

const ACCENT = '#2563eb';

const SEVERITY_COLOR: Record<string, string> = {
  CRITICAL: '#dc2626',
  HIGH: '#f97316',
  MEDIUM: '#f59e0b',
  LOW: '#facc15',
};

/** Score band colour. Mirrors the backend's Strong/Adequate/Weak/Critical labels. */
const scoreColor = (score: number): string => {
  if (score >= 90) return '#10b981';
  if (score >= 75) return '#22c55e';
  if (score >= 50) return '#f59e0b';
  return '#dc2626';
};

const severityOf = (s: string) => SEVERITY_COLOR[(s || '').toUpperCase()] || '#94a3b8';

/** Compact "2h ago" / "3d ago" so the card can show scan freshness in one line. */
const relativeTime = (iso: string | null): string | null => {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return null;
  const mins = Math.floor((Date.now() - then) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
};

const ScoreRing: React.FC<{ score: number | null; label: string | null }> = ({ score, label }) => {
  const measured = score != null;
  const color = measured ? scoreColor(score) : '#94a3b8';
  const radius = 30;
  const circumference = 2 * Math.PI * radius;
  const offset = measured ? circumference * (1 - score / 100) : circumference;

  return (
    <div className="relative flex-shrink-0" style={{ width: 76, height: 76 }}>
      <svg width={76} height={76} className="-rotate-90">
        <circle cx={38} cy={38} r={radius} fill="none" stroke="rgba(0,0,0,0.07)" strokeWidth={7} />
        {measured && (
          <circle
            cx={38} cy={38} r={radius} fill="none" stroke={color} strokeWidth={7}
            strokeLinecap="round" strokeDasharray={circumference} strokeDashoffset={offset}
            style={{ transition: 'stroke-dashoffset 700ms ease-out' }}
          />
        )}
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
        <span className="text-[19px] font-bold leading-none tabular-nums" style={{ color: measured ? 'rgba(0,0,0,0.88)' : 'rgba(0,0,0,0.3)' }}>
          {measured ? score : '—'}
        </span>
        {label && (
          <span className="text-[8.5px] uppercase tracking-wide font-semibold leading-none mt-1" style={{ color }}>
            {label}
          </span>
        )}
      </div>
    </div>
  );
};

const CompliancePostureSection: React.FC<CompliancePostureSectionProps> = ({ accountId }) => {
  const [report, setReport] = useState<ComplianceReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!accountId) {
      setReport(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setFailed(false);
    // trendDays is left to the controller default (90). A 30-day window often
    // yields fewer than two completed scans, which collapses the sparkline into
    // the "no trend yet" state on accounts that scan weekly or monthly.
    apiClient
      .get<ComplianceReport>(`/api/xamops/compliance/report?accountId=${encodeURIComponent(accountId)}`)
      .then(data => { if (!cancelled) setReport(data); })
      .catch(() => { if (!cancelled) setFailed(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [accountId]);

  const counts = report?.counts;
  const trendData = (report?.trend || [])
    .filter(p => p.score != null)
    .map((p, i) => ({ i, score: p.score as number }));

  const severityChips = counts
    ? ([
        { label: 'Critical', value: counts.critical, color: SEVERITY_COLOR.CRITICAL },
        { label: 'High', value: counts.high, color: SEVERITY_COLOR.HIGH },
        { label: 'Medium', value: counts.medium, color: SEVERITY_COLOR.MEDIUM },
      ] as const).filter(c => c.value > 0)
    : [];

  const subtitle = (() => {
    if (loading) return 'Loading scan…';
    if (!accountId) return 'Select a single account';
    if (failed) return 'Report unavailable';
    if (!counts?.totalControls) return 'No scan recorded yet';
    const parts = [`${counts.failedControls} failing of ${counts.totalControls}`];
    if (counts.waivedControls > 0) parts.push(`${counts.waivedControls} waived`);
    const when = relativeTime(report?.scannedAt || null);
    if (when) parts.push(when);
    return parts.join(' · ');
  })();

  return (
    <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 flex flex-col widget-panel-hover">
      {/* Header */}
      <div className="flex items-center justify-between mb-3 flex-shrink-0">
        <div className="flex items-center gap-2 min-w-0">
          <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0"
            style={{ background: `linear-gradient(135deg,${ACCENT}20,${ACCENT}0d)` }}>
            <Icon icon="mdi:clipboard-check-outline" width={15} color={ACCENT} />
          </div>
          <div className="min-w-0">
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] leading-tight">Compliance Posture</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] truncate">{subtitle}</p>
          </div>
        </div>
        <Link to="/complianceops"
          className="flex items-center gap-0.5 text-[11px] font-semibold whitespace-nowrap hover:opacity-70 transition-opacity flex-shrink-0"
          style={{ color: ACCENT }}>
          View <Icon icon="mdi:arrow-right" width={10} />
        </Link>
      </div>

      {/* Body */}
      {loading && !report ? (
        <div className="flex-1 flex items-center justify-center" style={{ minHeight: 150 }}>
          <div className="w-5 h-5 rounded-full border-2 animate-spin"
            style={{ borderColor: ACCENT, borderTopColor: 'transparent' }} />
        </div>
      ) : !accountId || failed || !counts?.totalControls ? (
        <div className="flex-1 flex flex-col items-center justify-center gap-1.5 text-center px-2" style={{ minHeight: 150 }}>
          <Icon icon={failed ? 'mdi:cloud-off-outline' : 'mdi:clipboard-text-clock-outline'} width={30} color="rgba(0,0,0,0.15)" />
          <p className="text-[12px] text-[rgba(0,0,0,0.45)]">
            {!accountId ? 'Select a single account' : failed ? 'Could not load compliance report' : 'No compliance scan yet'}
          </p>
          {accountId && !failed && (
            <Link to="/complianceops" className="text-[11px] font-semibold hover:opacity-70" style={{ color: ACCENT }}>
              Run a scan
            </Link>
          )}
        </div>
      ) : (
        <div className="flex-1 flex flex-col gap-3">
          {/* Score + trend */}
          <div className="flex items-center gap-3">
            <ScoreRing score={report?.assessable ? report.score ?? null : null} label={report?.scoreLabel || null} />

            <div className="flex-1 min-w-0">
              {trendData.length > 1 ? (
                <div className="h-[42px] -mx-1">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={trendData} margin={{ top: 4, right: 4, left: 4, bottom: 0 }}>
                      <defs>
                        <linearGradient id="complianceTrendGrad" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor={ACCENT} stopOpacity={0.2} />
                          <stop offset="95%" stopColor={ACCENT} stopOpacity={0.01} />
                        </linearGradient>
                      </defs>
                      <Area type="monotone" dataKey="score" stroke={ACCENT} strokeWidth={2}
                        fill="url(#complianceTrendGrad)" dot={false} />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <p className="text-[10.5px] text-[rgba(0,0,0,0.3)] leading-snug">
                  Only one scan recorded, so there is no trend yet.
                </p>
              )}

              {/* Severity chips = the actual work queue */}
              <div className="flex items-center gap-1.5 flex-wrap mt-1.5">
                {severityChips.length > 0 ? (
                  severityChips.map(c => (
                    <span key={c.label}
                      className="text-[9.5px] font-semibold px-1.5 py-0.5 rounded-full border tabular-nums"
                      style={{ color: c.color, backgroundColor: `${c.color}14`, borderColor: `${c.color}33` }}>
                      {c.value} {c.label}
                    </span>
                  ))
                ) : (
                  <span className="text-[9.5px] font-semibold px-1.5 py-0.5 rounded-full border tabular-nums"
                    style={{ color: '#10b981', backgroundColor: '#10b98114', borderColor: '#10b98133' }}>
                    All controls passing
                  </span>
                )}
                {counts.newlyFailing > 0 && (
                  <span className="text-[9.5px] font-semibold px-1.5 py-0.5 rounded-full border tabular-nums"
                    style={{ color: '#dc2626', backgroundColor: '#dc262614', borderColor: '#dc262633' }}>
                    +{counts.newlyFailing} new
                  </span>
                )}
              </div>
            </div>
          </div>

          {/* Top risks */}
          {report && report.topRisks.length > 0 && (
            <div className="border-t border-[rgba(0,0,0,0.06)] pt-2 space-y-1.5">
              <p className="text-[9.5px] uppercase tracking-wider font-semibold text-[rgba(0,0,0,0.3)]">Top risks</p>
              {report.topRisks.slice(0, 3).map(risk => (
                <div key={risk.checkId} className="flex items-center gap-2 min-w-0">
                  <span className="w-1.5 h-1.5 rounded-full flex-shrink-0"
                    style={{ backgroundColor: severityOf(risk.severity) }} />
                  <span className="text-[11px] text-[rgba(0,0,0,0.6)] truncate leading-tight" title={risk.title}>
                    {risk.title}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default CompliancePostureSection;
