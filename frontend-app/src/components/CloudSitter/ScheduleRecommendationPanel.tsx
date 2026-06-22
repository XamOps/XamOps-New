import React, { useState, useCallback, useEffect } from 'react';
import { Icon } from '@iconify/react';
import type { ScheduleRecommendation } from '../../types/cloudsitter';

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const DAY_KEYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

interface Props {
  instanceId: string;
  instanceName?: string;
  region: string;
  accountId: string;
  onApply: (grid: boolean[][]) => void;
}

// ── CPU colour scale ──────────────────────────────────────────────────────────
function cpuColor(avg: number) {
  if (avg >= 50) return '#1a3a3a';  // brand-teal  — high
  if (avg >= 20) return '#b8a4ed';  // lavender    — moderate
  if (avg >= 5)  return '#ffb084';  // peach       — light
  if (avg >= 2)  return '#f5f0e0';  // cream       — idle
  return '#e8f4fd';                 // blue-tint   — consistently idle < 2%
}

function cpuLabel(avg: number) {
  if (avg >= 50) return 'High';
  if (avg >= 20) return 'Moderate';
  if (avg >= 5)  return 'Light';
  if (avg >= 2)  return 'Idle';
  return 'Consistently Idle';
}

// ── Memory colour scale ───────────────────────────────────────────────────────
function memColor(avg: number) {
  if (avg >= 80) return '#ef4444';  // red    — critical
  if (avg >= 50) return '#f59e0b';  // amber  — high
  if (avg >= 20) return '#a4d4c5';  // mint   — moderate
  return '#f5f0e0';                 // cream  — idle (< 20%)
}

function memLabel(avg: number) {
  if (avg >= 80) return 'Critical';
  if (avg >= 50) return 'High';
  if (avg >= 20) return 'Moderate';
  return 'Idle';
}

// ── Combined tooltip ──────────────────────────────────────────────────────────
function cellTooltip(
  cpu: number,
  mem: number | null,
  day: string,
  hour: number,
): string {
  const time = `${day} ${String(hour).padStart(2, '0')}:00`;
  let tip = `${time}\nCPU: ${cpu.toFixed(2)}% (${cpuLabel(cpu)})`;
  if (mem !== null) tip += `\nMemory: ${mem.toFixed(1)}% (${memLabel(mem)})`;
  if (cpu < 2) tip += '\n⚡ Consistently idle — strong candidate to keep OFF';
  return tip;
}

// ── Shared grid renderer ──────────────────────────────────────────────────────
const MetricGrid: React.FC<{
  label: string;
  subLabel: string;
  grid: Record<string, number[]>;
  colorFn: (v: number) => string;
  tooltipFn: (v: number, day: string, h: number) => string;
  dotThreshold?: number; // draw blue dot below this value
}> = ({ label, subLabel, grid, colorFn, tooltipFn, dotThreshold }) => (
  <div style={{ flex: 1, minWidth: 0 }}>
    <div style={{ marginBottom: 8 }}>
      <span style={{ fontSize: 12, fontWeight: 700, color: '#0a0a0a', fontFamily: 'Inter, sans-serif', letterSpacing: '0.3px' }}>
        {label}
      </span>
      <span style={{ fontSize: 11, color: '#9a9a9a', fontFamily: 'Inter, sans-serif', marginLeft: 6 }}>
        {subLabel}
      </span>
    </div>

    <div style={{ background: '#fffaf0', border: '1px solid #e5e5e5', borderRadius: 10, overflow: 'hidden' }}>
      {/* Hour header */}
      <div style={{ display: 'grid', gridTemplateColumns: '52px repeat(24, 1fr)', background: '#f5f0e0', borderBottom: '1px solid #e5e5e5' }}>
        <div style={{ padding: '4px 6px', fontSize: 9, fontWeight: 700, color: '#6a6a6a', fontFamily: 'Inter, sans-serif', borderRight: '1px solid #e5e5e5', textAlign: 'center' }}>
          HR
        </div>
        {Array.from({ length: 24 }, (_, h) => (
          <div key={h} style={{ fontSize: 8, fontFamily: 'monospace', color: '#9a9a9a', textAlign: 'center', padding: '4px 0', borderRight: h < 23 ? '1px solid #ebe6d6' : 'none' }}>
            {String(h).padStart(2, '0')}
          </div>
        ))}
      </div>

      {/* Day rows */}
      {DAYS.map((day, dIdx) => {
        const key = DAY_KEYS[dIdx];
        const row = grid[key] ?? Array(24).fill(0);
        return (
          <div key={dIdx} style={{ display: 'grid', gridTemplateColumns: '52px repeat(24, 1fr)', borderBottom: dIdx < 6 ? '1px solid #ebe6d6' : 'none', height: 30 }}>
            <div style={{ display: 'flex', alignItems: 'center', padding: '0 6px', fontSize: 9, fontWeight: 700, color: '#3a3a3a', fontFamily: 'Inter, sans-serif', letterSpacing: '0.5px', textTransform: 'uppercase', background: '#faf5e8', borderRight: '1px solid #e5e5e5' }}>
              {day.substring(0, 3)}
            </div>
            {row.map((avg, h) => (
              <div
                key={h}
                title={tooltipFn(avg, day, h)}
                style={{ background: colorFn(avg), borderRight: h < 23 ? '1px solid rgba(0,0,0,0.04)' : 'none', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'default' }}
              >
                {dotThreshold !== undefined && avg < dotThreshold && avg > 0 && (
                  <div style={{ width: 3, height: 3, borderRadius: '50%', background: '#1e6fa3', flexShrink: 0 }} />
                )}
              </div>
            ))}
          </div>
        );
      })}
    </div>
  </div>
);

// ── Main component ────────────────────────────────────────────────────────────
const ScheduleRecommendationPanel: React.FC<Props> = ({
  instanceId,
  instanceName,
  region,
  accountId,
  onApply,
}) => {
  const [rec, setRec] = useState<ScheduleRecommendation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lookbackDays, setLookbackDays] = useState(30);

  const fetchRecommendation = useCallback(async () => {
    setLoading(true);
    setError(null);
    setRec(null);
    try {
      const res = await fetch(
        `/api/xamops/cloudsitter/recommendation?accountId=${accountId}&instanceId=${instanceId}&region=${region}&lookbackDays=${lookbackDays}`
      );
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setRec(await res.json());
    } catch (e: any) {
      setError(e.message || 'Failed to fetch recommendation');
    } finally {
      setLoading(false);
    }
  }, [accountId, instanceId, region, lookbackDays]);

  useEffect(() => { fetchRecommendation(); }, [fetchRecommendation]);

  const handleApply = useCallback(() => {
    if (!rec || !rec.recommendedSchedule) return;
    const grid = DAY_KEYS.map((key) => {
      const runningHours = rec.recommendedSchedule![key] ?? [];
      return Array.from({ length: 24 }, (_, h) => runningHours.includes(h));
    });
    onApply(grid);
  }, [rec, onApply]);

  const offHoursPerWeek = rec?.recommendedSchedule
    ? DAY_KEYS.reduce((t, k) => t + (24 - (rec.recommendedSchedule![k]?.length ?? 24)), 0)
    : 0;

  return (
    <div style={{ background: 'linear-gradient(135deg, #faf5e8 0%, #f5f0e0 100%)', border: '1.5px solid #e5e5e5', borderRadius: 24, padding: '24px 28px', marginBottom: 24 }}>

      {/* ── Header ── */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ width: 40, height: 40, borderRadius: 12, background: '#1a3a3a', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
            <Icon icon="mdi:chart-bar" color="#fff" width={18} />
          </div>
          <div>
            <p style={{ fontFamily: 'Inter, sans-serif', fontSize: 16, fontWeight: 600, color: '#0a0a0a', margin: 0 }}>
              Smart Schedule Recommendation
            </p>
            <p style={{ fontFamily: 'Inter, sans-serif', fontSize: 13, color: '#6a6a6a', margin: '2px 0 0' }}>
              CPU &amp; Memory usage for{' '}
              <span style={{ fontWeight: 600, color: '#1a3a3a' }}>{instanceName || instanceId}</span>
            </p>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 11, fontWeight: 700, color: '#6a6a6a', fontFamily: 'Inter, sans-serif', letterSpacing: '0.5px' }}>
            ANALYZE LAST
          </span>
          <select
            value={lookbackDays}
            onChange={(e) => setLookbackDays(Number(e.target.value))}
            disabled={loading}
            style={{ background: '#fffaf0', border: '1px solid #e5e5e5', borderRadius: 8, padding: '4px 10px', fontSize: 13, fontWeight: 600, color: '#0a0a0a', fontFamily: 'Inter, sans-serif', cursor: loading ? 'not-allowed' : 'pointer' }}
          >
            <option value={30}>30 days</option>
            <option value={45}>45 days</option>
            <option value={60}>60 days</option>
            <option value={90}>90 days</option>
          </select>
        </div>
      </div>

      {/* ── Loading ── */}
      {loading && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0' }}>
          <Icon icon="mdi:loading" color="#1a3a3a" width={16} className="animate-spin" />
          <span style={{ fontSize: 14, color: '#6a6a6a', fontFamily: 'Inter, sans-serif' }}>
            Analyzing {lookbackDays} days of CloudWatch data…
          </span>
        </div>
      )}

      {/* ── Error ── */}
      {error && (
        <div style={{ background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 10, padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
          <Icon icon="mdi:alert-circle-outline" color="#ef4444" width={14} />
          <span style={{ fontSize: 13, color: '#b91c1c', fontFamily: 'Inter, sans-serif' }}>{error}</span>
          <button onClick={fetchRecommendation} style={{ marginLeft: 'auto', fontSize: 12, color: '#6a6a6a', background: 'none', border: 'none', cursor: 'pointer', fontFamily: 'Inter, sans-serif' }}>Retry</button>
        </div>
      )}

      {/* ── Skipped ── */}
      {rec && rec.skipReason && (
        <div style={{ background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 10, padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
          <Icon icon="mdi:information-outline" color="#d97706" width={14} />
          <span style={{ fontSize: 13, color: '#92400e', fontFamily: 'Inter, sans-serif' }}>
            Recommendation skipped:{' '}
            <strong>
              {rec.skipReason === 'ASG_MANAGED' && 'Instance is in an Auto Scaling Group'}
              {rec.skipReason === 'K8S_NODE' && 'Instance is a Kubernetes node'}
              {rec.skipReason === 'PRODUCTION' && 'Instance is tagged as production'}
              {rec.skipReason === 'ACTIVE_ALARM' && 'Instance has active CloudWatch alarms'}
              {rec.skipReason === 'INSUFFICIENT_DATA' && 'Insufficient CloudWatch data'}
              {!['ASG_MANAGED','K8S_NODE','PRODUCTION','ACTIVE_ALARM','INSUFFICIENT_DATA'].includes(rec.skipReason) && rec.skipReason}
            </strong>
          </span>
        </div>
      )}

      {/* ── Results ── */}
      {rec && !rec.skipReason && rec.recommendedSchedule && (
        <>
          {/* Stats pills */}
          <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap' }}>
            <StatPill icon="mdi:database-outline"    label={`${rec.dataPoints} data points`}                bg="#e8f4fd" color="#1e6fa3" />
            <StatPill icon="mdi:calendar-outline"    label={`${rec.lookbackDays}-day window`}               bg="#f0fdf4" color="#166534" />
            <StatPill icon="mdi:moon-waning-crescent" label={`${offHoursPerWeek} off-hours/week suggested`} bg="#fef3c7" color="#92400e" />
            <StatPill icon="mdi:chip"                label={`CPU idle < ${rec.cpuIdleThreshold}%`}          bg="#f5f3ff" color="#5b21b6" />
            {rec.memAvailable
              ? <StatPill icon="mdi:memory"          label={`Mem idle < ${rec.memIdleThreshold}%`}          bg="#ecfdf5" color="#065f46" />
              : <StatPill icon="mdi:memory"          label="Memory: CWAgent not detected"                   bg="#fef3c7" color="#92400e" />
            }
          </div>

          {/* Memory not-available notice */}
          {!rec.memAvailable && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 10, padding: '8px 14px', marginBottom: 14 }}>
              <Icon icon="mdi:information-outline" color="#d97706" width={14} />
              <span style={{ fontSize: 12, color: '#92400e', fontFamily: 'Inter, sans-serif' }}>
                Memory metrics require the <strong>CloudWatch Agent</strong> installed on this instance.
                Recommendation is based on CPU only.
              </span>
            </div>
          )}

          {/* ── Two heatmaps side-by-side ── */}
          <div style={{ display: 'flex', gap: 16, marginBottom: 16 }}>
            <MetricGrid
              label="CPU Utilization"
              subLabel="(AWS/EC2 · CPUUtilization)"
              grid={rec.avgCpuGrid ?? {}}
              colorFn={cpuColor}
              tooltipFn={(v, day, h) => cellTooltip(v, rec.memAvailable && rec.avgMemGrid ? (rec.avgMemGrid[DAY_KEYS[DAYS.indexOf(day)]]?.[h] ?? null) : null, day, h)}
              dotThreshold={2}
            />
            {rec.memAvailable && rec.avgMemGrid && (
              <MetricGrid
                label="Memory Usage"
                subLabel="(CWAgent · mem_used_percent)"
                grid={rec.avgMemGrid}
                colorFn={memColor}
                tooltipFn={(v, day, h) => `${day} ${String(h).padStart(2, '0')}:00\nMemory: ${v.toFixed(1)}% (${memLabel(v)})`}
              />
            )}
          </div>

          {/* ── Legends ── */}
          <div style={{ display: 'flex', gap: 24, marginBottom: 16, flexWrap: 'wrap' }}>
            {/* CPU legend */}
            <div>
              <p style={{ fontSize: 11, fontWeight: 700, color: '#6a6a6a', fontFamily: 'Inter, sans-serif', margin: '0 0 6px', letterSpacing: '0.5px', textTransform: 'uppercase' }}>CPU</p>
              <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                {[
                  { bg: '#e8f4fd', label: '< 2% Consistently Idle', dot: true },
                  { bg: '#f5f0e0', label: '2–5% Idle' },
                  { bg: '#ffb084', label: '5–20% Light' },
                  { bg: '#b8a4ed', label: '20–50% Moderate' },
                  { bg: '#1a3a3a', label: '≥ 50% High' },
                ].map(({ bg, label, dot }) => (
                  <LegendItem key={label} bg={bg} label={label} dot={dot} />
                ))}
              </div>
            </div>

            {rec.memAvailable && (
              <div>
                <p style={{ fontSize: 11, fontWeight: 700, color: '#6a6a6a', fontFamily: 'Inter, sans-serif', margin: '0 0 6px', letterSpacing: '0.5px', textTransform: 'uppercase' }}>Memory</p>
                <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                  {[
                    { bg: '#f5f0e0', label: '< 20% Idle' },
                    { bg: '#a4d4c5', label: '20–50% Moderate' },
                    { bg: '#f59e0b', label: '50–80% High' },
                    { bg: '#ef4444', label: '≥ 80% Critical' },
                  ].map(({ bg, label }) => (
                    <LegendItem key={label} bg={bg} label={label} />
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* Threshold explanation */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 16 }}>
            <Icon icon="mdi:information-outline" color="#9a9a9a" width={12} />
            <span style={{ fontSize: 12, color: '#9a9a9a', fontFamily: 'Inter, sans-serif' }}>
              {rec.memAvailable
                ? `A slot is recommended OFF when CPU < ${rec.cpuIdleThreshold}% AND Memory < ${rec.memIdleThreshold}%`
                : `A slot is recommended OFF when CPU < ${rec.cpuIdleThreshold}% (memory unavailable)`}
            </span>
          </div>

          {/* Actions */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <button
              onClick={handleApply}
              style={{ display: 'flex', alignItems: 'center', gap: 8, background: '#0a0a0a', color: '#fff', border: 'none', borderRadius: 12, padding: '10px 20px', fontSize: 14, fontWeight: 600, fontFamily: 'Inter, sans-serif', cursor: 'pointer' }}
            >
              <Icon icon="mdi:check" width={14} color="#fff" />
              Apply Recommendation to Schedule
            </button>
            <button
              onClick={fetchRecommendation}
              style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: '1px solid #e5e5e5', borderRadius: 12, padding: '10px 16px', fontSize: 13, fontWeight: 500, color: '#6a6a6a', fontFamily: 'Inter, sans-serif', cursor: 'pointer' }}
            >
              <Icon icon="mdi:refresh" width={14} color="#6a6a6a" />
              Refresh
            </button>
          </div>
        </>
      )}
    </div>
  );
};

// ── Small helpers ─────────────────────────────────────────────────────────────
const StatPill: React.FC<{ icon: string; label: string; bg: string; color: string }> = ({ icon, label, bg, color }) => (
  <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, background: bg, borderRadius: 9999, padding: '4px 12px' }}>
    <Icon icon={icon} width={11} color={color} />
    <span style={{ fontSize: 12, fontWeight: 600, color, fontFamily: 'Inter, sans-serif' }}>{label}</span>
  </div>
);

const LegendItem: React.FC<{ bg: string; label: string; dot?: boolean }> = ({ bg, label, dot }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
    <div style={{ width: 13, height: 13, borderRadius: 3, background: bg, border: '1px solid #e5e5e5', flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      {dot && <div style={{ width: 4, height: 4, borderRadius: '50%', background: '#1e6fa3' }} />}
    </div>
    <span style={{ fontSize: 11, color: '#6a6a6a', fontFamily: 'Inter, sans-serif' }}>{label}</span>
  </div>
);

export default ScheduleRecommendationPanel;
