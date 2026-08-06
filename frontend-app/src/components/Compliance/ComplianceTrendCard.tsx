import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import {
  Area, AreaChart, CartesianGrid, Line, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import type { ComplianceTrendPoint } from '../../types/compliance';
import { CARD, EYEBROW, SEVERITY_COLORS, SEVERITY_ROWS } from './complianceTokens';

interface Props {
  trend: ComplianceTrendPoint[];
  mode: 'score' | 'severity';
  onModeChange: (mode: 'score' | 'severity') => void;
}

/**
 * The real trend, one point per completed scan.
 *
 * What this replaces is worth stating plainly, because it is the single most important
 * change on this page: the previous chart took today's finding counts and multiplied
 * them by `1 - progress * 0.15` with a sine wave on top, producing a 14-day improving
 * curve that had no connection to the account and always sloped downwards. On a page
 * used in audit conversations that is manufactured evidence. Every point below is a
 * stored score from a real scan.
 *
 * With fewer than two scans there is nothing to compare, so the card says so instead of
 * drawing a flat line that implies stability.
 */
const ComplianceTrendCard: React.FC<Props> = ({ trend, mode, onModeChange }) => {
  const data = useMemo(
    () => trend.map((p) => ({
      ...p,
      label: new Date(p.at).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
    })),
    [trend],
  );

  const delta = useMemo(() => {
    const scored = trend.filter((p) => p.score != null);
    if (scored.length < 2) return null;
    const first = scored[0].score as number;
    const last = scored[scored.length - 1].score as number;
    return { value: last - first, from: first, to: last, span: scored.length };
  }, [trend]);

  return (
    <section className={`${CARD} p-4`}>
      <header className="flex items-start justify-between gap-3 mb-3">
        <div>
          <span className={EYEBROW}>Trend</span>
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mt-1">
            {trend.length} completed scan{trend.length === 1 ? '' : 's'} on record
          </p>
          {delta && (
            <p className="text-[11.5px] mt-0.5">
              <span
                className={`font-semibold ${
                  delta.value > 0
                    ? 'text-emerald-600'
                    : delta.value < 0
                      ? 'text-red-600'
                      : 'text-[rgba(0,0,0,0.45)]'
                }`}
              >
                {delta.value > 0 ? '+' : ''}{delta.value} pts
              </span>
              <span className="text-[rgba(0,0,0,0.4)]">
                {' '}from {delta.from}% to {delta.to}% across {delta.span} scans
              </span>
            </p>
          )}
        </div>

        {trend.length >= 2 && (
          <div className="flex items-center p-0.5 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)] shrink-0">
            {([
              { key: 'score' as const, label: 'Score' },
              { key: 'severity' as const, label: 'Findings' },
            ]).map((opt) => (
              <button
                key={opt.key}
                type="button"
                onClick={() => onModeChange(opt.key)}
                aria-pressed={mode === opt.key}
                className={`h-6 px-2.5 rounded-md text-[11px] font-medium transition-all ${
                  mode === opt.key
                    ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-[0_1px_2px_rgba(0,0,0,0.08)]'
                    : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.6)]'
                }`}
              >
                {opt.label}
              </button>
            ))}
          </div>
        )}
      </header>

      {trend.length < 2 ? (
        <div className="flex flex-col items-center justify-center py-12 text-center px-6">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-xl flex items-center justify-center mb-3">
            <Icon icon="mdi:chart-timeline-variant" width={26} className="text-[rgba(0,0,0,0.3)]" />
          </div>
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.78)] mb-1">
            {trend.length === 0 ? 'No completed scans yet' : 'One scan so far'}
          </p>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] max-w-[46ch] leading-relaxed">
            A trend needs at least two scans to compare. This chart plots stored scores
            from real scans only — it will fill in as scans accumulate.
          </p>
        </div>
      ) : mode === 'score' ? (
        <ResponsiveContainer width="100%" height={180}>
          <AreaChart data={data} margin={{ top: 6, right: 8, bottom: 0, left: -18 }}>
            <defs>
              <linearGradient id="complianceScoreFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#10b981" stopOpacity={0.28} />
                <stop offset="100%" stopColor="#10b981" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.04)" vertical={false} />
            <XAxis
              dataKey="label"
              tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.4)' }}
              tickLine={false}
              axisLine={false}
              minTickGap={18}
            />
            <YAxis
              domain={[0, 100]}
              tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.4)' }}
              tickLine={false}
              axisLine={false}
              unit="%"
            />
            <Tooltip
              contentStyle={{
                fontSize: 11,
                borderRadius: 8,
                border: '1px solid #e5e7eb',
                boxShadow: '0 4px 12px rgba(0,0,0,0.08)',
              }}
              formatter={(value) => [value == null ? '—' : `${value}%`, 'Score']}
            />
            <Area
              type="monotone"
              dataKey="score"
              stroke="#10b981"
              strokeWidth={2}
              fill="url(#complianceScoreFill)"
              // Gaps are real: a scan with nothing scoreable has no score, and
              // connecting across it would invent a measurement.
              connectNulls={false}
              dot={{ r: 2.5, fill: '#10b981', strokeWidth: 0 }}
              activeDot={{ r: 4 }}
            />
            <Line type="monotone" dataKey="score" stroke="transparent" dot={false} />
          </AreaChart>
        </ResponsiveContainer>
      ) : (
        <>
          <ResponsiveContainer width="100%" height={180}>
            <AreaChart data={data} margin={{ top: 6, right: 8, bottom: 0, left: -18 }}>
              <defs>
                {SEVERITY_ROWS.map((row) => (
                  <linearGradient
                    key={row.key}
                    id={`complianceSev-${row.key}`}
                    x1="0"
                    y1="0"
                    x2="0"
                    y2="1"
                  >
                    <stop offset="0%" stopColor={row.color} stopOpacity={0.45} />
                    <stop offset="100%" stopColor={row.color} stopOpacity={0.04} />
                  </linearGradient>
                ))}
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.04)" vertical={false} />
              <XAxis
                dataKey="label"
                tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.4)' }}
                tickLine={false}
                axisLine={false}
                minTickGap={18}
              />
              <YAxis
                tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.4)' }}
                tickLine={false}
                axisLine={false}
              />
              <Tooltip
                contentStyle={{
                  fontSize: 11,
                  borderRadius: 8,
                  border: '1px solid #e5e7eb',
                  boxShadow: '0 4px 12px rgba(0,0,0,0.08)',
                }}
              />
              <Area
                type="monotone"
                dataKey="low"
                name="Low"
                stackId="1"
                stroke={SEVERITY_COLORS.LOW}
                strokeWidth={1}
                fill="url(#complianceSev-low)"
              />
              <Area
                type="monotone"
                dataKey="medium"
                name="Medium"
                stackId="1"
                stroke={SEVERITY_COLORS.MEDIUM}
                strokeWidth={1}
                fill="url(#complianceSev-medium)"
              />
              <Area
                type="monotone"
                dataKey="high"
                name="High"
                stackId="1"
                stroke={SEVERITY_COLORS.HIGH}
                strokeWidth={1.5}
                fill="url(#complianceSev-high)"
              />
              <Area
                type="monotone"
                dataKey="critical"
                name="Critical"
                stackId="1"
                stroke={SEVERITY_COLORS.CRITICAL}
                strokeWidth={2}
                fill="url(#complianceSev-critical)"
              />
            </AreaChart>
          </ResponsiveContainer>
          <div className="flex items-center gap-4 mt-2.5">
            {SEVERITY_ROWS.map((row) => (
              <span key={row.key} className="flex items-center gap-1.5">
                <span
                  className="w-2 h-2 rounded-full"
                  style={{ backgroundColor: row.color }}
                  aria-hidden="true"
                />
                <span className="text-[10.5px] text-[rgba(0,0,0,0.45)]">{row.label}</span>
              </span>
            ))}
            <span className="text-[10.5px] text-[rgba(0,0,0,0.3)] ml-auto">
              failing controls per scan
            </span>
          </div>
        </>
      )}
    </section>
  );
};

export default ComplianceTrendCard;
