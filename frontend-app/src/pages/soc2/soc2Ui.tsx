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
