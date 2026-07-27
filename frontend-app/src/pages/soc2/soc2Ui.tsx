import React from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';

// Shared primitives for the comp.ai-style SOC 2 drill-down pages
// (Frameworks -> Framework -> Control, Policy detail, Documents).

export type Tone = 'success' | 'warning' | 'danger' | 'neutral';

export const TONE_CLASSES: Record<Tone, string> = {
  success: 'bg-emerald-50 text-emerald-700 border border-emerald-200',
  warning: 'bg-amber-50 text-amber-700 border border-amber-200',
  danger: 'bg-red-50 text-red-700 border border-red-200',
  neutral: 'bg-gray-50 text-gray-500 border border-gray-200',
};

export const StatusPill: React.FC<{ text: string; tone?: Tone }> = ({ text, tone = 'neutral' }) => (
  <span className={`px-2 py-0.5 rounded-full text-[11px] font-medium capitalize ${TONE_CLASSES[tone]}`}>{text || '—'}</span>
);

export const policyTone = (status: string | null): Tone => {
  const s = (status || '').toLowerCase();
  if (s === 'published') return 'success';
  if (s === 'needs_review' || s === 'in_review' || s === 'draft') return 'warning';
  return 'neutral';
};

const CATEGORY_CLASSES: Record<string, string> = {
  CC: 'bg-gray-50 text-gray-600 border border-gray-200',
  A: 'bg-emerald-50 text-emerald-700 border border-emerald-200',
  C: 'bg-amber-50 text-amber-700 border border-amber-200',
  P: 'bg-orange-50 text-orange-700 border border-orange-200',
  PI: 'bg-rose-50 text-rose-700 border border-rose-200',
};

const categorySeries = (category: string): string => {
  if (category.startsWith('PI')) return 'PI';
  const letters = category.match(/^[A-Za-z]+/)?.[0] ?? '';
  return letters || category;
};

export const CategoryBadge: React.FC<{ category: string }> = ({ category }) => (
  <span className={`inline-block px-2 py-0.5 rounded-full text-[11px] font-medium ${CATEGORY_CLASSES[categorySeries(category)] ?? CATEGORY_CLASSES.CC}`}>
    {category}
  </span>
);

export const fmtDate = (iso: string | null): string => {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
};

export interface Crumb { label: string; to?: string; }

export const Breadcrumbs: React.FC<{ items: Crumb[] }> = ({ items }) => (
  <nav className="flex items-center gap-1.5 text-[13px] text-gray-500 mb-3 flex-wrap">
    {items.map((c, i) => (
      <React.Fragment key={i}>
        {i > 0 && <Icon icon="mdi:chevron-right" width={14} className="text-gray-300" />}
        {c.to && i < items.length - 1
          ? <Link to={c.to} className="hover:text-gray-900">{c.label}</Link>
          : <span className={i === items.length - 1 ? 'text-gray-900 font-medium' : ''}>{c.label}</span>}
      </React.Fragment>
    ))}
  </nav>
);

/** Green completion bar with an inline percentage, matching comp.ai's control table. */
export const ComplianceBar: React.FC<{ percent: number; width?: string }> = ({ percent, width = 'w-24' }) => {
  const p = Math.max(0, Math.min(100, percent));
  return (
    <div className="flex items-center gap-2">
      <div className={`${width} h-1.5 rounded-full bg-gray-100 overflow-hidden`}>
        <div className="h-full bg-emerald-500 rounded-full" style={{ width: `${p}%`, transition: 'width 0.4s ease' }} />
      </div>
      <span className="text-[12px] text-gray-600 tabular-nums w-9 text-right">{p}%</span>
    </div>
  );
};

export const controlStatusPill = (percent: number): { text: string; tone: Tone } => {
  if (percent >= 100) return { text: 'Satisfied', tone: 'success' };
  if (percent > 0) return { text: 'In Progress', tone: 'warning' };
  return { text: 'Not Started', tone: 'neutral' };
};

export const Th: React.FC<{ children: React.ReactNode; className?: string }> = ({ children, className }) => (
  <th className={`p-3 text-left text-[11px] font-semibold uppercase tracking-wide text-gray-500 ${className || ''}`}>{children}</th>
);

export const TableCard: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white overflow-hidden">
    <table className="w-full text-sm">{children}</table>
  </div>
);

export const Tabs: React.FC<{ tabs: { id: string; label: string; count?: number }[]; active: string; onSelect: (id: string) => void }> =
  ({ tabs, active, onSelect }) => (
    <div className="flex items-center gap-5 border-b border-gray-200 mb-4">
      {tabs.map((t) => (
        <button
          key={t.id}
          onClick={() => onSelect(t.id)}
          className={`pb-2 -mb-px text-[13px] font-medium border-b-2 transition-colors ${
            active === t.id ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-800'
          }`}
        >
          {t.label}{typeof t.count === 'number' && <span className="ml-1.5 text-gray-400">{t.count}</span>}
        </button>
      ))}
    </div>
  );

/** comp.ai-style "x/10" risk score chip, colored green/amber/red by magnitude. */
export const RiskScoreBadge: React.FC<{ score: number | null }> = ({ score }) => {
  if (score == null) return <span className="text-[12px] text-gray-400">—</span>;
  const tone = score >= 7 ? 'bg-red-50 text-red-700 border-red-200'
    : score >= 4 ? 'bg-amber-50 text-amber-700 border-amber-200'
    : 'bg-emerald-50 text-emerald-700 border-emerald-200';
  return <span className={`inline-block px-2 py-0.5 rounded-md text-[11px] font-semibold border tabular-nums ${tone}`}>{score}/10</span>;
};

export interface DonutSegment { label: string; value: number; color: string; }

/** Donut chart with total in the center and a legend, matching comp.ai's "by status" panels. */
export const StatusDonut: React.FC<{ segments: DonutSegment[]; total?: number }> = ({ segments, total }) => {
  const sum = total ?? segments.reduce((a, s) => a + s.value, 0);
  const size = 120, stroke = 14, radius = (size - stroke) / 2, circ = 2 * Math.PI * radius;
  let offset = 0;
  return (
    <div className="flex items-center gap-5">
      <div className="relative shrink-0" style={{ width: size, height: size }}>
        <svg width={size} height={size} className="-rotate-90">
          <circle cx={size / 2} cy={size / 2} r={radius} stroke="#f1f1f1" strokeWidth={stroke} fill="none" />
          {sum > 0 && segments.filter((s) => s.value > 0).map((s, i) => {
            const len = (s.value / sum) * circ;
            const el = (
              <circle key={i} cx={size / 2} cy={size / 2} r={radius} stroke={s.color} strokeWidth={stroke} fill="none"
                strokeDasharray={`${len} ${circ - len}`} strokeDashoffset={-offset} />
            );
            offset += len;
            return el;
          })}
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-xl font-bold text-gray-900">{sum}</span>
        </div>
      </div>
      <div className="flex flex-col gap-1.5">
        {segments.map((s) => (
          <div key={s.label} className="flex items-center gap-2 text-[12px] text-gray-600">
            <span className="w-2.5 h-2.5 rounded-full shrink-0" style={{ backgroundColor: s.color }} />
            {s.label} <span className="font-semibold text-gray-900 ml-auto pl-3">{s.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
};

const IMPACT_LABELS = ['Insignificant', 'Minor', 'Moderate', 'Major', 'Severe'];
const LIKELIHOOD_LABELS = ['Very Unlikely', 'Unlikely', 'Possible', 'Likely', 'Very Likely'];

export const likelihoodLabel = (n: number | null): string => (n && n >= 1 && n <= 5 ? LIKELIHOOD_LABELS[n - 1] : '—');
export const impactLabel = (n: number | null): string => (n && n >= 1 && n <= 5 ? IMPACT_LABELS[n - 1] : '—');

/** Colour band for a raw likelihood×impact score (1-25), mirroring comp.ai's zones. */
const matrixBand = (raw: number): string => {
  if (raw >= 17) return 'bg-red-200';
  if (raw >= 10) return 'bg-orange-200';
  if (raw >= 5) return 'bg-yellow-100';
  if (raw >= 2) return 'bg-lime-100';
  return 'bg-emerald-100';
};

/** 5×5 likelihood × impact heatmap with an optional marker at (likelihood, impact). */
export const RiskMatrix: React.FC<{ likelihood: number | null; impact: number | null }> = ({ likelihood, impact }) => {
  const rows = [5, 4, 3, 2, 1];
  const cols = [1, 2, 3, 4, 5];
  return (
    <div>
      <div className="flex">
        <div className="flex flex-col-reverse justify-between text-[10px] text-gray-400 pr-2 py-0.5" style={{ height: 200 }}>
          {rows.slice().reverse().map((l) => <span key={l} className="h-8 flex items-center">{l}</span>)}
        </div>
        <div className="grid grid-cols-5 grid-rows-5 gap-1 flex-1" style={{ maxWidth: 360 }}>
          {rows.map((l) => cols.map((i) => {
            const here = l === likelihood && i === impact;
            return (
              <div key={`${l}-${i}`} className={`rounded ${matrixBand(l * i)} relative`} style={{ height: 32 }}>
                {here && <span className="absolute inset-0 m-auto w-3.5 h-3.5 rounded-full bg-gray-900 ring-2 ring-white" />}
              </div>
            );
          }))}
        </div>
      </div>
      <div className="grid grid-cols-5 gap-1 ml-6 mt-1" style={{ maxWidth: 360 }}>
        {IMPACT_LABELS.map((l) => <span key={l} className="text-[9px] text-gray-400 text-center leading-tight">{l}</span>)}
      </div>
      <div className="flex items-center gap-3 mt-3 flex-wrap">
        {[
          { label: 'Very Low', c: 'bg-emerald-100' }, { label: 'Low', c: 'bg-lime-100' },
          { label: 'Medium', c: 'bg-yellow-100' }, { label: 'High', c: 'bg-orange-200' },
          { label: 'Very High', c: 'bg-red-200' },
        ].map((s) => (
          <span key={s.label} className="flex items-center gap-1 text-[10px] text-gray-500">
            <span className={`w-2.5 h-2.5 rounded ${s.c}`} /> {s.label}
          </span>
        ))}
      </div>
    </div>
  );
};

/** Inherent → residual reduction summary with the negligible→critical scale bar. */
export const TreatmentReduction: React.FC<{ from: number | null; to: number | null }> = ({ from, to }) => {
  const band = (s: number | null) => s == null ? 'LOW' : s >= 17 ? 'CRITICAL' : s >= 10 ? 'HIGH' : s >= 5 ? 'MEDIUM' : s >= 2 ? 'LOW' : 'NEGLIGIBLE';
  const color = (s: number | null) => s == null ? 'text-gray-400' : s >= 10 ? 'text-red-600' : s >= 5 ? 'text-amber-600' : 'text-emerald-600';
  const changed = from !== to;
  return (
    <div>
      <div className="text-[11px] text-gray-400 uppercase tracking-wide mb-2">Risk reduction — treatment impact</div>
      <div className="flex items-end gap-3">
        <span className={`text-4xl font-bold ${color(from)}`}>{from ?? '—'}<span className="text-lg text-gray-400">/10</span></span>
        <Icon icon="mdi:arrow-right" width={22} className="text-gray-400 mb-2" />
        <span className={`text-4xl font-bold ${color(to)}`}>{to ?? '—'}<span className="text-lg text-gray-400">/10</span></span>
      </div>
      <p className="text-[12px] text-gray-500 mt-2">
        {changed
          ? <>The plan moves this risk from <b>{band(from)}</b> to <b>{band(to)}</b>.</>
          : <>The plan keeps this risk at <b>{band(from)}</b> — no change since no mitigation plan is in place yet.</>}
      </p>
      <div className="mt-3 h-2 rounded-full bg-gradient-to-r from-emerald-300 via-yellow-200 to-red-300" />
      <div className="flex justify-between text-[9px] text-gray-400 mt-1">
        <span>NEGLIGIBLE</span><span>LOW</span><span>MEDIUM</span><span>HIGH</span><span>CRITICAL</span>
      </div>
    </div>
  );
};

export const SearchInput: React.FC<{ value: string; onChange: (v: string) => void; placeholder?: string }> =
  ({ value, onChange, placeholder }) => (
    <div className="relative max-w-sm">
      <Icon icon="mdi:magnify" width={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder || 'Search…'}
        className="w-full text-[13px] border border-gray-300 rounded-lg pl-9 pr-3 py-2 text-gray-900 focus:outline-none focus:ring-1 focus:ring-gray-400"
      />
    </div>
  );
