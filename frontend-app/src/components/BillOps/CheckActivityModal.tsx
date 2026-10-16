/**
 * "Check Activity" window for one user, opened from the Users tab on Client Details.
 *
 * Answers two different questions on one surface: whether this person is using the product
 * and where their time goes, and who else has been inside their account.
 *
 * Styled to the AWS FinOps / Cost Management page rather than inventing a theme, since an
 * admin moves between the two and a second visual language for the same product reads as a
 * different product. That means the token set from `FinOpsPage` and `FinOpsCharts`:
 * rgba-black text alphas instead of slate greys, `rounded-xl` cards on
 * `rgba(0,0,0,0.12)` borders, explicit px type sizes, AWS orange `#FF9900` as the accent,
 * the shared `CHART_COLORS` ramp, Bebas Neue condensed numerals for headline figures, and
 * `@iconify/react` mdi icons.
 *
 * Built on the shared `DialogRoot`/`DialogPanel` primitives, which supply focus trapping,
 * Esc-to-close and ARIA roles from react-aria-components. The calendar heatmap is a CSS grid
 * rather than a chart component because recharts has no calendar primitive.
 */

import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Pie, PieChart,
  ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { DialogPanel, DialogRoot } from '../ui';
import { useUserActivity } from '../../hooks/useUserActivity';
import type { PageStat, UserActivitySummary } from '../../types/userActivity';

interface CheckActivityModalProps {
  isOpen: boolean;
  onClose: () => void;
  userId: number | null;
  /** Shown in the header while the request is still in flight. */
  fallbackName?: string;
}

/* ─── theme tokens, lifted from FinOpsCharts so the two pages cannot drift ──── */

const AWS_ORANGE = '#FF9900';
const AWS_EMBER = '#FF6B35';

/** The FinOps categorical ramp. Orange first, then the cool half for secondary series. */
const CHART_COLORS = [
  '#FF9900', '#FF6B35', '#3dc3e8', '#4caefe', '#2dd9db',
  '#1feeaf', '#0ff3a0', '#00e887', '#23e274', '#35d96f',
];

/** Heatmap ramp: transparent-to-solid orange, matching the accent rather than indigo. */
const HEAT_RAMP = ['rgba(0,0,0,0.04)', '#FFE4BF', '#FFC977', '#FFAE3D', AWS_ORANGE];

const CARD = 'bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm';

/** Condensed numerals for headline figures, as on the FinOps KPI cards. */
const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '30px',
  fontWeight: 400,
  lineHeight: '30px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

/* ─── formatting ────────────────────────────────────────────────────────────── */

/** Compact duration: 2h 14m, 14m, 45s. Zero renders as a dash, not "0s". */
function formatDuration(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return '—';
  const totalSeconds = Math.round(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  if (minutes > 0) return seconds > 0 ? `${minutes}m ${seconds}s` : `${minutes}m`;
  return `${seconds}s`;
}

/** Splits a duration so the unit can be set in Inter beside condensed numerals. */
function splitDuration(ms: number | null | undefined): { main: string; unit: string } {
  const formatted = formatDuration(ms);
  if (formatted === '—') return { main: '—', unit: '' };
  const firstAlpha = formatted.search(/[a-z]/i);
  return firstAlpha === -1
    ? { main: formatted, unit: '' }
    : { main: formatted.slice(0, firstAlpha), unit: formatted.slice(firstAlpha) };
}

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? '—'
    : d.toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' });
}

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('en-US', {
    month: 'short', day: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

/** Relative age in whole units. Reads better than a raw date for recency. */
function formatRelative(iso: string | null | undefined): string {
  if (!iso) return 'never';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return 'never';
  const mins = Math.round((Date.now() - then) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days}d ago`;
  const months = Math.round(days / 30);
  return months < 12 ? `${months}mo ago` : `${Math.round(months / 12)}y ago`;
}

const STATUS_META: Record<string, { label: string; icon: string; color: string; bg: string }> = {
  ACTIVE: { label: 'Active', icon: 'mdi:check-circle', color: '#22c55e', bg: 'rgba(34,197,94,0.1)' },
  IDLE: { label: 'Idle', icon: 'mdi:clock-alert-outline', color: AWS_ORANGE, bg: 'rgba(255,153,0,0.12)' },
  DORMANT: { label: 'Dormant', icon: 'mdi:sleep', color: '#ef4444', bg: 'rgba(239,68,68,0.1)' },
  NEVER: { label: 'Never signed in', icon: 'mdi:account-off-outline', color: 'rgba(0,0,0,0.4)', bg: 'rgba(0,0,0,0.04)' },
};

const PROVIDER_LABELS: Record<string, string> = {
  LOCAL: 'Password',
  OTP: 'Email code',
  GOOGLE: 'Google',
  MICROSOFT: 'Microsoft',
  UNKNOWN: 'Unrecorded',
};

const ACTION_META: Record<string, { label: string; icon: string }> = {
  LOGIN: { label: 'Signed in', icon: 'mdi:login-variant' },
  LOGIN_FAILED: { label: 'Sign-in failed', icon: 'mdi:alert-circle-outline' },
  LOGOUT: { label: 'Signed out', icon: 'mdi:logout-variant' },
  IMPERSONATION_STARTED: { label: 'Admin signed in as this user', icon: 'mdi:shield-account-outline' },
};

/* ─── building blocks ───────────────────────────────────────────────────────── */

/**
 * KPI tile, mirroring the FinOps card: uppercase tracked label, condensed numeral, tinted
 * icon chip, and a staggered fade-in keyed off position.
 */
const Kpi: React.FC<{
  label: string;
  value: string;
  unit?: string;
  sub?: string;
  icon: string;
  iconColor?: string;
  warn?: boolean;
  index: number;
}> = ({ label, value, unit, sub, icon, iconColor = AWS_ORANGE, warn, index }) => (
  <div
    className={`${CARD} p-4 card-hover-lift`}
    style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${0.05 + index * 0.07}s both` }}
  >
    <p className="text-[11px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.88)] mb-2">
      {label}
    </p>
    <div className="flex items-end justify-between">
      <p className="flex items-baseline gap-0.5">
        <span style={NUM_STYLE}>{value}</span>
        {unit && (
          <span className="text-[13px] font-normal text-[rgba(10,10,10,1)]">{unit}</span>
        )}
      </p>
      <div
        className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0"
        style={{ background: `linear-gradient(135deg,${iconColor}22,${AWS_EMBER}18)` }}
      >
        <Icon icon={icon} width={17} color={iconColor} />
      </div>
    </div>
    {sub && (
      <p className={`mt-1.5 text-[11px] ${warn ? 'text-[#b45309] font-medium' : 'text-[rgba(0,0,0,0.45)]'}`}>
        {sub}
      </p>
    )}
  </div>
);

const Section: React.FC<{
  title: string;
  hint?: string;
  icon: string;
  aside?: React.ReactNode;
  children: React.ReactNode;
}> = ({ title, hint, icon, aside, children }) => (
  <section className={`${CARD} p-5`}>
    <div className="mb-4 flex items-start gap-3">
      <div
        className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0"
        style={{ background: `linear-gradient(135deg,${AWS_ORANGE}22,${AWS_EMBER}22)` }}
      >
        <Icon icon={icon} width={18} color={AWS_ORANGE} />
      </div>
      <div className="min-w-0 flex-1">
        <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] leading-tight">{title}</h3>
        {hint && <p className="mt-0.5 text-[12px] text-[rgba(0,0,0,0.45)]">{hint}</p>}
      </div>
      {aside}
    </div>
    {children}
  </section>
);

const Empty: React.FC<{ icon?: string; children: React.ReactNode }> = ({
  icon = 'mdi:chart-timeline-variant', children,
}) => (
  <div className="py-8 text-center">
    <Icon icon={icon} width={22} color="rgba(0,0,0,0.18)" className="mx-auto mb-2" />
    <p className="text-[12px] text-[rgba(0,0,0,0.4)]">{children}</p>
  </div>
);

/** Chart tooltip in the FinOps shell: white card, soft border, tabular figures. */
const ChartTooltip: React.FC<{
  active?: boolean;
  payload?: any[];
  label?: any;
  labelPrefix?: string;
  format: (v: number) => string;
  seriesLabel: string;
}> = ({ active, payload, label, labelPrefix = '', format, seriesLabel }) => {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-lg shadow-lg px-3 py-2">
      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.72)] mb-1">
        {labelPrefix}{label}
      </p>
      <div className="flex items-center gap-2">
        <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ background: AWS_ORANGE }} />
        <span className="text-[11px] text-[rgba(0,0,0,0.5)]">{seriesLabel}</span>
        <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] ml-auto tabular-nums">
          {format(Number(payload[0]?.value ?? 0))}
        </span>
      </div>
    </div>
  );
};

/**
 * Calendar heatmap of the last ~13 weeks.
 *
 * Buckets on visible time rather than visit count: ten seconds on a page and forty minutes
 * on it are not the same day's work, and colouring by visits would make them identical.
 */
const Heatmap: React.FC<{ summary: UserActivitySummary }> = ({ summary }) => {
  const { cells, max } = useMemo(() => {
    const byDay = new Map<string, number>();
    for (const bucket of summary.pageTimePerDay) {
      if (bucket.day) byDay.set(bucket.day.slice(0, 10), bucket.visibleMs);
    }
    // Falls back to logins when no page time exists yet, so the panel still reads during the
    // window between the login writes shipping and the tracked frontend bundle going live.
    if (byDay.size === 0) {
      for (const bucket of summary.loginsPerDay) {
        if (bucket.day) byDay.set(bucket.day.slice(0, 10), bucket.logins);
      }
    }

    const days: { key: string; value: number }[] = [];
    const today = new Date();
    for (let i = 90; i >= 0; i--) {
      const d = new Date(today);
      d.setDate(d.getDate() - i);
      const key = d.toISOString().slice(0, 10);
      days.push({ key, value: byDay.get(key) ?? 0 });
    }
    return { cells: days, max: Math.max(...days.map((d) => d.value), 1) };
  }, [summary]);

  const shade = (value: number): string => {
    if (value <= 0) return HEAT_RAMP[0];
    const ratio = value / max;
    if (ratio > 0.75) return HEAT_RAMP[4];
    if (ratio > 0.5) return HEAT_RAMP[3];
    if (ratio > 0.25) return HEAT_RAMP[2];
    return HEAT_RAMP[1];
  };

  return (
    <div>
      <div className="grid grid-flow-col grid-rows-7 gap-[3px]" style={{ gridAutoColumns: '12px' }}>
        {cells.map((cell) => (
          <div
            key={cell.key}
            title={`${cell.key}: ${cell.value > 0 ? formatDuration(cell.value) : 'no activity'}`}
            className="h-3 w-3 rounded-[3px]"
            style={{ backgroundColor: shade(cell.value) }}
          />
        ))}
      </div>
      <div className="mt-3 flex items-center gap-1.5 text-[11px] text-[rgba(0,0,0,0.4)]">
        <span>Less</span>
        {HEAT_RAMP.map((c) => (
          <span key={c} className="h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: c }} />
        ))}
        <span>More</span>
        <span className="ml-auto font-semibold text-[rgba(0,0,0,0.55)] tabular-nums">
          Longest gap: {summary.longestGapDays} day{summary.longestGapDays === 1 ? '' : 's'}
        </span>
      </div>
    </div>
  );
};

/**
 * Time per page as horizontal bars.
 *
 * Mirrors the FinOps cost-by-service breakdown, which is the same question asked of a
 * different unit: a ranked list of where a finite total went. Bars carry the categorical
 * ramp for the same reason that chart does, so position and colour agree.
 */
const TopPages: React.FC<{ pages: PageStat[] }> = ({ pages }) => {
  const top = pages.slice(0, 10);
  const max = Math.max(...top.map((p) => p.visibleMs), 1);
  const total = top.reduce((sum, p) => sum + p.visibleMs, 0);

  if (top.length === 0) {
    return <Empty icon="mdi:timer-sand-empty">No page activity recorded in this window.</Empty>;
  }

  return (
    <div className="space-y-2.5">
      {top.map((page, i) => {
        const share = total > 0 ? (page.visibleMs / total) * 100 : 0;
        return (
          <div key={page.route ?? page.label}>
            <div className="flex items-center justify-between gap-3 mb-1">
              <span
                className="text-[12px] text-[rgba(0,0,0,0.72)] truncate"
                title={page.route ?? ''}
              >
                {page.label ?? page.route}
              </span>
              <span className="flex items-baseline gap-1.5 flex-shrink-0 tabular-nums">
                <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)]">
                  {formatDuration(page.visibleMs)}
                </span>
                <span className="text-[11px] text-[rgba(0,0,0,0.38)]">
                  ({share.toFixed(1)}%)
                </span>
              </span>
            </div>
            <div className="flex items-center gap-2.5">
              <div className="h-2 flex-1 overflow-hidden rounded-full bg-[rgba(0,0,0,0.05)]">
                <div
                  className="h-2 rounded-full"
                  style={{
                    width: `${Math.max((page.visibleMs / max) * 100, 2)}%`,
                    backgroundColor: CHART_COLORS[i % CHART_COLORS.length],
                  }}
                />
              </div>
              {/* Visits and mean time per visit: forty eight-second bounces and three
                  twenty-minute sessions produce the same total and mean opposite things. */}
              <span
                className="w-[104px] flex-shrink-0 text-right text-[11px] text-[rgba(0,0,0,0.4)] tabular-nums"
                title="Visits × mean visible time per visit"
              >
                {page.visits} × {formatDuration(page.avgVisibleMs)}
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
};

/* ─── the window ────────────────────────────────────────────────────────────── */

const WINDOWS = [30, 90, 180] as const;

const CheckActivityModal: React.FC<CheckActivityModalProps> = ({
  isOpen, onClose, userId, fallbackName,
}) => {
  const [days, setDays] = useState<number>(90);
  const { data, loading, error } = useUserActivity(isOpen ? userId : null, days);

  const status = STATUS_META[data?.status ?? 'NEVER'] ?? STATUS_META.NEVER;

  const providerData = useMemo(
    () => (data?.providers ?? []).map((p) => ({
      name: PROVIDER_LABELS[p.provider ?? 'UNKNOWN'] ?? p.provider ?? 'Unrecorded',
      value: p.logins,
    })),
    [data],
  );

  const trendData = useMemo(
    () => (data?.pageTimePerDay ?? []).map((b) => ({
      day: b.day ? b.day.slice(5, 10) : '',
      minutes: Math.round(b.visibleMs / 60000),
    })),
    [data],
  );

  const sessionSplit = splitDuration(data?.avgSessionMs);
  const timeSplit = splitDuration(data?.totalVisibleMs);

  const header = (
    <div className="flex items-center gap-2.5 min-w-0">
      <Icon icon="mdi:account-clock-outline" width={18} color={AWS_ORANGE} />
      <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">User Activity</span>
      <span className="text-[rgba(0,0,0,0.2)]">/</span>
      <span className="text-[14px] font-normal text-[rgba(0,0,0,0.6)] truncate">
        {data?.username ?? fallbackName ?? 'User'}
      </span>
      <span
        className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-[11px] font-semibold flex-shrink-0"
        style={{ background: status.bg, color: status.color }}
      >
        <Icon icon={status.icon} width={12} />
        {status.label}
      </span>
    </div>
  );

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-6xl">
      <DialogPanel title={header} onClose={onClose}>
        {/* Range picker, in the FinOps control idiom: bordered pills, orange when chosen. */}
        <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            {WINDOWS.map((w) => (
              <button
                key={w}
                type="button"
                onClick={() => setDays(w)}
                aria-pressed={days === w}
                className={`flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium border rounded-lg transition-colors ${
                  days === w
                    ? 'border-[#FF9900] text-[#FF9900] bg-[#FF990012]'
                    : 'border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)]'
                }`}
              >
                {days === w && <Icon icon="mdi:check" width={13} />}
                Last {w} days
              </button>
            ))}
          </div>
          {data?.collectingSince && (
            <div className="flex items-center gap-1.5 text-[12px] text-[rgba(0,0,0,0.5)]">
              <Icon icon="mdi:database-outline" width={13} />
              <span>Recording since {formatDate(data.collectingSince)}</span>
            </div>
          )}
        </div>

        {loading && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
              {[...Array(5)].map((_, i) => (
                <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[104px] rounded-xl" />
              ))}
            </div>
            <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-64 rounded-xl" />
            <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-48 rounded-xl" />
          </div>
        )}

        {error && !loading && (
          <div className={`${CARD} p-8 text-center`}>
            <div className="w-12 h-12 bg-red-50 rounded-xl flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:alert-circle-outline" width={24} color="#dc2626" />
            </div>
            <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">
              Could not load activity
            </h4>
            <p className="text-[13px] text-[rgba(0,0,0,0.45)]">{error}</p>
          </div>
        )}

        {data && !loading && (
          <div className="space-y-4">
            {/*
              Stated up front rather than left implicit. These tables began collecting on
              deploy and there is nothing to backfill from, so for the first weeks the window
              is near-empty for everyone. Without this line that reads as "this user does
              nothing", which is the opposite of the truth.
            */}
            {!data.collectingSince && (
              <div className="flex items-start gap-3 rounded-xl border border-[#FF990055] bg-[#FF990010] px-4 py-3">
                <Icon icon="mdi:information-outline" width={17} color={AWS_ORANGE} className="flex-shrink-0 mt-0.5" />
                <p className="text-[12px] text-[rgba(0,0,0,0.6)]">
                  No activity recorded yet for this user. Tracking began when this feature was
                  deployed &mdash; sessions before that were never stored.
                </p>
              </div>
            )}

            {/* KPI strip */}
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
              <Kpi
                index={0}
                label="Last login"
                value={formatDate(data.lastLoginAt)}
                sub={formatRelative(data.lastLoginAt)}
                icon="mdi:clock-outline"
              />
              <Kpi
                index={1}
                label="Time in product"
                value={timeSplit.main}
                unit={timeSplit.unit}
                sub={
                  data.engagementRatio != null
                    ? `${Math.round(data.engagementRatio * 100)}% tab visible`
                    : undefined
                }
                warn={data.engagementRatio != null && data.engagementRatio < 0.5}
                icon="mdi:timer-outline"
              />
              <Kpi
                index={2}
                label="Logins"
                value={String(data.loginCount)}
                sub={`${data.windowDays}-day window`}
                icon="mdi:login-variant"
              />
              <Kpi
                index={3}
                label="Active days"
                value={String(data.activeDays)}
                unit={`/${data.windowDays}`}
                sub={`${Math.round((data.activeDays / Math.max(data.windowDays, 1)) * 100)}% of days`}
                icon="mdi:calendar-check-outline"
              />
              <Kpi
                index={4}
                label="Avg session"
                value={sessionSplit.main}
                unit={sessionSplit.unit}
                /*
                  Sample size shown because only deliberate sign-outs are measurable — closing
                  a tab produces no logout event. Publishing the mean alone would invite
                  reading it as typical when it describes a self-selected subset.
                */
                sub={
                  data.closedSessionCount > 0
                    ? `from ${data.closedSessionCount} closed session${data.closedSessionCount === 1 ? '' : 's'}`
                    : 'no completed sign-outs'
                }
                warn={data.closedSessionCount > 0 && data.closedSessionCount < data.loginCount / 2}
                icon="mdi:timer-sand"
              />
            </div>

            {/* Time per page — the centrepiece */}
            <Section
              title="Where their time goes"
              hint="Visible time per page. Trailing figures are visit count and mean time per visit."
              icon="mdi:chart-timeline-variant"
              aside={
                <span className="text-[12px] text-[rgba(0,0,0,0.45)] tabular-nums flex-shrink-0">
                  {data.totalPageVisits.toLocaleString()} page views
                </span>
              }
            >
              <TopPages pages={data.pages} />
            </Section>

            <div className="grid gap-4 lg:grid-cols-2">
              <Section
                title="Daily engagement"
                hint="Visible minutes per day"
                icon="mdi:chart-areaspline"
              >
                {trendData.length === 0 ? (
                  <Empty icon="mdi:chart-areaspline">No page time recorded yet.</Empty>
                ) : (
                  <ResponsiveContainer width="100%" height={170}>
                    <AreaChart data={trendData} margin={{ top: 4, right: 8, bottom: 0, left: -18 }}>
                      <defs>
                        <linearGradient id="activityArea" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="0%" stopColor={AWS_ORANGE} stopOpacity={0.28} />
                          <stop offset="100%" stopColor={AWS_ORANGE} stopOpacity={0.02} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.05)" vertical={false} />
                      <XAxis
                        dataKey="day"
                        tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.45)' }}
                        axisLine={false}
                        tickLine={false}
                        interval="preserveStartEnd"
                      />
                      <YAxis
                        tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.4)' }}
                        axisLine={false}
                        tickLine={false}
                      />
                      <Tooltip
                        content={
                          <ChartTooltip
                            format={(v) => `${v} min`}
                            seriesLabel="Visible time"
                          />
                        }
                      />
                      <Area
                        type="monotone"
                        dataKey="minutes"
                        stroke={AWS_ORANGE}
                        strokeWidth={2.5}
                        fill="url(#activityArea)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                )}
              </Section>

              <Section
                title="Activity calendar"
                hint="Last 90 days, shaded by time on site"
                icon="mdi:calendar-blank-outline"
              >
                <Heatmap summary={data} />
              </Section>
            </div>

            <div className="grid gap-4 lg:grid-cols-2">
              <Section
                title="When they work"
                hint="Logins by hour of day, in the viewer's timezone"
                icon="mdi:clock-time-four-outline"
              >
                {data.loginCount === 0 ? (
                  <Empty icon="mdi:clock-outline">No logins recorded yet.</Empty>
                ) : (
                  <ResponsiveContainer width="100%" height={160}>
                    <BarChart data={data.loginsPerHour} margin={{ top: 4, right: 8, bottom: 0, left: -18 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.05)" vertical={false} />
                      <XAxis
                        dataKey="hour"
                        tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.45)' }}
                        axisLine={false}
                        tickLine={false}
                        interval={3}
                      />
                      <YAxis
                        tick={{ fontSize: 11, fill: 'rgba(0,0,0,0.4)' }}
                        axisLine={false}
                        tickLine={false}
                        allowDecimals={false}
                      />
                      <Tooltip
                        cursor={{ fill: 'rgba(0,0,0,0.03)' }}
                        content={
                          <ChartTooltip
                            labelPrefix=""
                            format={(v) => String(v)}
                            seriesLabel="Logins"
                          />
                        }
                      />
                      <Bar dataKey="logins" fill={AWS_ORANGE} radius={[4, 4, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </Section>

              <Section
                title="How they sign in"
                hint="Provider used per login, not the account's configured default"
                icon="mdi:shield-key-outline"
              >
                {providerData.length === 0 ? (
                  <Empty icon="mdi:shield-key-outline">No logins recorded yet.</Empty>
                ) : (
                  <div className="flex items-center gap-4">
                    <ResponsiveContainer width="45%" height={150}>
                      <PieChart>
                        <Pie
                          data={providerData}
                          dataKey="value"
                          nameKey="name"
                          innerRadius={36}
                          outerRadius={58}
                          paddingAngle={2}
                        >
                          {providerData.map((_, i) => (
                            <Cell
                              key={i}
                              fill={CHART_COLORS[i % CHART_COLORS.length]}
                              stroke="#fff"
                              strokeWidth={2}
                            />
                          ))}
                        </Pie>
                      </PieChart>
                    </ResponsiveContainer>
                    <ul className="flex-1 space-y-2">
                      {providerData.map((p, i) => (
                        <li key={p.name} className="flex items-center gap-2">
                          <span
                            className="w-2.5 h-2.5 rounded-sm flex-shrink-0"
                            style={{ backgroundColor: CHART_COLORS[i % CHART_COLORS.length] }}
                          />
                          <span className="text-[12px] text-[rgba(0,0,0,0.65)] flex-1 truncate">
                            {p.name}
                          </span>
                          <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] tabular-nums">
                            {p.value}
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </Section>
            </div>

            {/*
              The most directly actionable panel: a catalogued feature nobody has opened is a
              prompt for whoever owns the account, in a way that "spent 4h in Billing" is not.
            */}
            <Section
              title="Never opened"
              hint="Catalogued product areas this user has not visited, across all recorded time"
              icon="mdi:eye-off-outline"
              aside={
                data.neverVisited.length > 0 ? (
                  <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.45)] tabular-nums flex-shrink-0">
                    {data.neverVisited.length} areas
                  </span>
                ) : undefined
              }
            >
              {data.neverVisited.length === 0 ? (
                <Empty icon="mdi:check-all">This user has opened every tracked area.</Empty>
              ) : (
                <div className="flex flex-wrap gap-1.5">
                  {data.neverVisited.map((p) => (
                    <span
                      key={p.route ?? p.label}
                      title={p.route ?? ''}
                      className="rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] px-2.5 py-1 text-[11px] text-[rgba(0,0,0,0.6)]"
                    >
                      {p.label ?? p.route}
                    </span>
                  ))}
                </div>
              )}
            </Section>

            {/* Admin access ledger. Orange rather than a separate alarm palette: this is
                notable, not broken, and the page already reads orange as "attend to this". */}
            <section className="rounded-xl border border-[#FF990055] bg-[#FF990008] p-5">
              <div className="mb-3 flex items-start gap-3">
                <div
                  className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0"
                  style={{ background: `linear-gradient(135deg,${AWS_ORANGE}28,${AWS_EMBER}28)` }}
                >
                  <Icon icon="mdi:shield-account-outline" width={18} color={AWS_ORANGE} />
                </div>
                <div className="min-w-0 flex-1">
                  <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] leading-tight">
                    Admin access to this account
                  </h3>
                  <p className="mt-0.5 text-[12px] text-[rgba(0,0,0,0.5)]">
                    Every &ldquo;Login as&rdquo; against this user. Each grants an 8-hour session.
                  </p>
                </div>
                <span className="text-[12px] font-semibold text-[#b45309] tabular-nums flex-shrink-0">
                  {data.impersonations.length} session{data.impersonations.length === 1 ? '' : 's'}
                </span>
              </div>
              {data.impersonations.length === 0 ? (
                <p className="py-3 text-center text-[12px] text-[rgba(0,0,0,0.45)]">
                  No admin has signed in as this user since recording began.
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left">
                    <thead>
                      <tr className="border-b border-[#FF990033]">
                        {['When', 'Admin', 'Source IP'].map((h) => (
                          <th
                            key={h}
                            className="pb-2 text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)]"
                          >
                            {h}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {data.impersonations.slice(0, 8).map((entry, i) => (
                        <tr key={i} className="border-b border-[#FF990018] last:border-0">
                          <td className="py-2 text-[12px] text-[rgba(0,0,0,0.6)] whitespace-nowrap">
                            {formatDateTime(entry.at)}
                          </td>
                          <td className="py-2 text-[12px] font-medium text-[rgba(0,0,0,0.88)]">
                            {entry.actorLabel}
                          </td>
                          <td className="py-2 text-[11px] text-[rgba(0,0,0,0.5)] tabular-nums">
                            {entry.ipAddress ?? '—'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>

            {/* Devices and addresses */}
            <div className="grid gap-4 lg:grid-cols-2">
              <Section
                title="Devices"
                hint="Grouped from the browser's user agent"
                icon="mdi:devices"
              >
                {data.devices.length === 0 ? (
                  <Empty icon="mdi:devices">No devices recorded yet.</Empty>
                ) : (
                  <ul className="space-y-2">
                    {data.devices.slice(0, 5).map((d) => (
                      <li key={d.label} className="flex items-center gap-2">
                        <Icon icon="mdi:monitor" width={13} color="rgba(0,0,0,0.3)" />
                        <span className="text-[12px] text-[rgba(0,0,0,0.65)] flex-1 truncate">
                          {d.label}
                        </span>
                        <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] tabular-nums">
                          {d.hits}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </Section>

              <Section
                title="Source addresses"
                hint="Shown for context only; never used for access decisions"
                icon="mdi:ip-network-outline"
              >
                {data.ipAddresses.length === 0 ? (
                  <Empty icon="mdi:ip-network-outline">No addresses recorded yet.</Empty>
                ) : (
                  <ul className="space-y-2">
                    {data.ipAddresses.slice(0, 5).map((ip) => (
                      <li key={ip.ipAddress} className="flex items-center gap-2">
                        <span className="text-[12px] text-[rgba(0,0,0,0.65)] tabular-nums">
                          {ip.ipAddress}
                        </span>
                        <span className="text-[11px] text-[rgba(0,0,0,0.35)]">
                          {formatRelative(ip.lastSeenAt)}
                        </span>
                        <span className="ml-auto text-[12px] font-semibold text-[rgba(0,0,0,0.88)] tabular-nums">
                          {ip.hits}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </Section>
            </div>

            {/* Timeline */}
            <Section
              title="Event timeline"
              hint="Most recent sign-ins, sign-outs and admin access"
              icon="mdi:history"
            >
              {data.timeline.length === 0 ? (
                <Empty icon="mdi:history">No events recorded yet.</Empty>
              ) : (
                <div className="max-h-72 overflow-y-auto">
                  <table className="w-full text-left">
                    <thead className="sticky top-0 bg-white">
                      <tr className="border-b border-[rgba(0,0,0,0.07)]">
                        {['When', 'Event', 'How', 'From'].map((h) => (
                          <th
                            key={h}
                            className="pb-2 text-[10px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)]"
                          >
                            {h}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {data.timeline.map((entry, i) => {
                        const failed = entry.outcome === 'FAILURE';
                        const impersonated = entry.action === 'IMPERSONATION_STARTED';
                        const meta = ACTION_META[entry.action]
                          ?? { label: entry.action, icon: 'mdi:circle-small' };
                        return (
                          <tr key={i} className="border-b border-[rgba(0,0,0,0.04)] last:border-0">
                            <td className="py-2 pr-3 text-[12px] text-[rgba(0,0,0,0.5)] whitespace-nowrap">
                              {formatDateTime(entry.at)}
                            </td>
                            <td className="py-2 pr-3 whitespace-nowrap">
                              <span className="inline-flex items-center gap-1.5">
                                <Icon
                                  icon={meta.icon}
                                  width={13}
                                  color={
                                    failed ? '#ef4444'
                                      : impersonated ? AWS_ORANGE
                                        : 'rgba(0,0,0,0.35)'
                                  }
                                />
                                <span
                                  className={`text-[12px] ${
                                    failed ? 'text-red-500'
                                      : impersonated ? 'font-semibold text-[rgba(0,0,0,0.88)]'
                                        : 'font-medium text-[rgba(0,0,0,0.72)]'
                                  }`}
                                >
                                  {meta.label}
                                </span>
                              </span>
                            </td>
                            <td className="py-2 pr-3 text-[12px] text-[rgba(0,0,0,0.55)]">
                              {failed
                                ? entry.failureReason ?? 'Rejected'
                                : PROVIDER_LABELS[entry.authProvider ?? ''] ?? '—'}
                            </td>
                            <td className="py-2 text-[11px] text-[rgba(0,0,0,0.4)] tabular-nums">
                              {entry.ipAddress ?? '—'}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </Section>

            <p className="flex items-start gap-1.5 pt-1 text-[11px] text-[rgba(0,0,0,0.35)]">
              <Icon icon="mdi:information-outline" width={13} className="flex-shrink-0 mt-px" />
              Time on page is measured in the browser and counts only while the tab is visible.
              It reflects tab focus rather than attention.
            </p>
          </div>
        )}
      </DialogPanel>
    </DialogRoot>
  );
};

export default CheckActivityModal;
