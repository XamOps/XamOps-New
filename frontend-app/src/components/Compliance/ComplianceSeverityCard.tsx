import React from 'react';
import { Cell, Pie, PieChart } from 'recharts';
import type { ComplianceCounts } from '../../types/compliance';
import { CARD, EYEBROW, SEVERITY_ROWS } from './complianceTokens';

interface Props {
  counts: ComplianceCounts;
  severityFilter: string;
  onSelectSeverity: (severity: string) => void;
}

/**
 * Failing controls by severity.
 *
 * Counted per control, not per resource. The old version counted resource-level findings,
 * so one misconfigured check across 400 buckets read as 400 critical problems and drowned
 * out everything else. A control is one thing to fix.
 */
const ComplianceSeverityCard: React.FC<Props> = ({
  counts, severityFilter, onSelectSeverity,
}) => {
  const rows = SEVERITY_ROWS.map((row) => ({
    ...row,
    value: row.key === 'critical' ? counts.critical
      : row.key === 'high' ? counts.high
        : row.key === 'medium' ? counts.medium
          : counts.low,
  }));

  const donut = rows.filter((r) => r.value > 0);
  const total = rows.reduce((sum, r) => sum + r.value, 0);

  return (
    <section className={`${CARD} p-4`}>
      <span className={EYEBROW}>Failing controls by severity</span>

      <div className="flex items-center gap-5 mt-3.5">
        <div className="relative shrink-0">
          <PieChart width={124} height={124}>
            <Pie
              data={donut.length ? donut : [{ key: 'none', value: 1, color: '#e5e7eb' }]}
              cx={62}
              cy={62}
              innerRadius={43}
              outerRadius={56}
              dataKey="value"
              startAngle={90}
              endAngle={-270}
              strokeWidth={0}
              paddingAngle={donut.length > 1 ? 2 : 0}
            >
              {(donut.length ? donut : [{ color: '#e5e7eb' }]).map((entry, i) => (
                <Cell key={i} fill={entry.color} />
              ))}
            </Pie>
          </PieChart>
          <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
            <span className="text-[21px] font-semibold text-[rgba(0,0,0,0.88)] tabular-nums leading-none">
              {total.toLocaleString()}
            </span>
            <span className="text-[9.5px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)] mt-1">
              Failing
            </span>
          </div>
        </div>

        <ul className="flex-1 min-w-0 space-y-1">
          {rows.map((row) => {
            const active = severityFilter.toLowerCase() === row.key;
            return (
              <li key={row.key}>
                <button
                  type="button"
                  onClick={() => onSelectSeverity(active ? 'All' : row.key)}
                  aria-pressed={active}
                  className={`w-full flex items-center gap-2.5 px-2 py-1.5 rounded-md transition-colors ${
                    active ? 'bg-[#f5f5f5]' : 'hover:bg-[#fafafa]'
                  }`}
                >
                  <span
                    className="w-2.5 h-2.5 rounded-full shrink-0"
                    style={{ backgroundColor: row.color }}
                    aria-hidden="true"
                  />
                  <span className="text-[12px] text-[rgba(0,0,0,0.6)] flex-1 text-left">
                    {row.label}
                  </span>
                  <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] tabular-nums">
                    {row.value.toLocaleString()}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </div>

      <p className="text-[10.5px] text-[rgba(0,0,0,0.3)] leading-relaxed mt-3 pt-3 border-t border-[rgba(0,0,0,0.06)]">
        One count per control, not per affected resource — a single check failing across
        400 buckets is one thing to fix, not 400 problems.
      </p>
    </section>
  );
};

export default ComplianceSeverityCard;
