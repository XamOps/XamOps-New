import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { Cell, PieChart, Pie, ResponsiveContainer } from 'recharts';
import { fmtMoneyShort } from '../../utils/currency';

interface SummaryCardsSectionProps {
  accountId: string | null;
  isLoading?: boolean;
  provider?: 'AWS' | 'GCP' | 'Azure';
}

// ── helpers ───────────────────────────────────────────────────────────────────
const fmt = (v: number, provider?: string) => fmtMoneyShort(v, provider, 2);

// ── skeleton ──────────────────────────────────────────────────────────────────
const CardSkeleton = () => (
  <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-2xl overflow-hidden animate-pulse">
    <div className="h-[108px] bg-[rgba(0,0,0,0.04)]" />
    <div className="h-px bg-[rgba(0,0,0,0.06)] mx-4" />
    <div className="p-3 flex items-center gap-2">
      <div className="w-6 h-6 rounded-lg bg-[rgba(0,0,0,0.06)]" />
      <div className="flex-1 space-y-1.5">
        <div className="h-2.5 w-20 bg-[rgba(0,0,0,0.07)] rounded" />
        <div className="h-2 w-28 bg-[rgba(0,0,0,0.04)] rounded" />
      </div>
    </div>
  </div>
);

// ── compact top-N labeled bar list ────────────────────────────────────────────
// Shows the top items by value with name + amount visible (not tooltip-only):
//   label ................... $value
//   ▇▇▇▇▇▇▇▇▇░░░░░░░░░░  (length ∝ value, single hue = the card accent)
const TopList: React.FC<{
  data: { label: string; v: number }[];
  color: string;
  provider?: string;
  suffix?: string;
}> = ({ data, color, provider, suffix }) => {
  const rows = data.slice(0, 3);
  const max = Math.max(...rows.map(d => d.v), 1);
  return (
    <div className="h-[96px] px-4 py-2 flex flex-col justify-center gap-[7px]">
      {rows.map((d, i) => (
        <div key={`${d.label}-${i}`}>
          <div className="flex items-baseline justify-between gap-2 mb-[3px]">
            <span className="text-[10.5px] text-[rgba(0,0,0,0.55)] truncate leading-tight" title={d.label}>
              {d.label}
            </span>
            <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.78)] tabular-nums whitespace-nowrap leading-tight">
              {fmt(d.v, provider)}
              {suffix && <span className="text-[9px] font-normal text-[rgba(0,0,0,0.35)]">{suffix}</span>}
            </span>
          </div>
          <div className="h-[5px] rounded-full overflow-hidden" style={{ backgroundColor: `${color}1c` }}>
            <div
              className="h-full rounded-r-full"
              style={{ width: `${Math.max((d.v / max) * 100, 4)}%`, backgroundColor: color }}
            />
          </div>
        </div>
      ))}
    </div>
  );
};

// ── mini donut ────────────────────────────────────────────────────────────────
interface DonutSeg { label: string; value: number; color: string }

const MiniDonut: React.FC<{ segs: DonutSeg[]; sub: string; isLoading?: boolean }> = ({ segs, sub, isLoading }) => {
  const total  = segs.reduce((s, d) => s + d.value, 0);
  const filled = segs.some(s => s.value > 0);
  const displaySegs = filled ? segs : [{ label: 'none', value: 1, color: '#e5e7eb' }];

  if (isLoading) {
    return (
      <div className="h-[108px] flex items-center justify-center">
        <div className="w-5 h-5 rounded-full border-2 border-[#dc2626] border-t-transparent animate-spin" />
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3 px-4 pt-3 pb-2">
      <div className="relative flex-shrink-0" style={{ width: 72, height: 72 }}>
        <ResponsiveContainer width={72} height={72}>
          <PieChart>
            <Pie data={displaySegs} dataKey="value" cx="50%" cy="50%"
              innerRadius={22} outerRadius={34} paddingAngle={filled ? 2 : 0}
              startAngle={90} endAngle={-270} stroke="none">
              {displaySegs.map((s, i) => <Cell key={i} fill={s.color} />)}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
        <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
          <span className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] leading-none tabular-nums">
            {filled ? total.toLocaleString() : '—'}
          </span>
          <span className="text-[9px] text-[rgba(0,0,0,0.4)] leading-none mt-0.5">{sub}</span>
        </div>
      </div>
      <div className="flex-1 space-y-1">
        {segs.map(s => (
          <div key={s.label} className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: s.color }} />
              <span className="text-[10.5px] text-[rgba(0,0,0,0.5)]">{s.label}</span>
            </div>
            <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.75)] tabular-nums">{s.value.toLocaleString()}</span>
          </div>
        ))}
        <div className="flex items-center justify-between gap-2 border-t border-[rgba(0,0,0,0.06)] pt-1 mt-0.5">
          <span className="text-[10px] text-[rgba(0,0,0,0.3)]">Total</span>
          <span className="text-[11px] font-bold text-[rgba(0,0,0,0.65)] tabular-nums">{total.toLocaleString()}</span>
        </div>
      </div>
    </div>
  );
};

// ── empty / count state ────────────────────────────────────────────────────────
const ChartEmpty: React.FC<{ color: string; icon: string; line1: string; line2?: string }> = ({ color, icon, line1, line2 }) => (
  <div className="h-[96px] flex flex-col items-center justify-center gap-1 px-4">
    <Icon icon={icon} width={26} color={color} />
    <p className="text-[12px] font-semibold tabular-nums" style={{ color }}>{line1}</p>
    {line2 && <p className="text-[10.5px] text-[rgba(0,0,0,0.35)]">{line2}</p>}
  </div>
);

// ── card shell ────────────────────────────────────────────────────────────────
interface ShellProps {
  accentColor: string; icon: string; title: string; subtitle: string;
  badge?: string; link: string; children: React.ReactNode;
}
const CardShell: React.FC<ShellProps> = ({ accentColor, icon, title, subtitle, badge, link, children }) => (
  <div className="bg-white border border-[rgba(0,0,0,0.1)] rounded-2xl overflow-hidden flex flex-col hover:shadow-md transition-shadow duration-200">
    <div className="relative">{children}</div>
    <div className="h-px bg-[rgba(0,0,0,0.06)] mx-4" />
    <div className="px-3.5 py-2.5 flex items-center justify-between gap-2 flex-shrink-0">
      <div className="flex items-center gap-2 min-w-0">
        <div className="w-6 h-6 rounded-lg flex items-center justify-center flex-shrink-0"
          style={{ backgroundColor: `${accentColor}18` }}>
          <Icon icon={icon} width={13} color={accentColor} />
        </div>
        <div className="min-w-0">
          <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] leading-tight truncate">{title}</p>
          <p className="text-[10px] text-[rgba(0,0,0,0.4)] truncate leading-tight">{subtitle}</p>
        </div>
      </div>
      <div className="flex items-center gap-1.5 flex-shrink-0">
        {badge && (
          <span className="text-[9px] font-semibold bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.4)] px-1.5 py-0.5 rounded-full border border-[rgba(0,0,0,0.07)]">
            {badge}
          </span>
        )}
        <Link to={link}
          className="flex items-center gap-0.5 text-[11px] font-semibold whitespace-nowrap hover:opacity-70 transition-opacity"
          style={{ color: accentColor }}>
          View Details <Icon icon="mdi:arrow-right" width={10} />
        </Link>
      </div>
    </div>
  </div>
);

// ══════════════════════════════════════════════════════════════════════════════
// Shared fetch helpers
// ══════════════════════════════════════════════════════════════════════════════

// CloudGuard alerts → /api/xamops/cloudguard/alerts
interface RawAlert { type: 'QUOTA' | 'ANOMALY' | string; service?: string; usage?: number; limit?: number; description?: string }
interface AlertSummary { total: number; critical: number; warning: number; healthy: number; anomalies: { label: string; v: number }[] }

const computeAlertSummary = (raw: RawAlert[]): AlertSummary => {
  const getPct = (a: RawAlert) => a.type === 'QUOTA' && a.limit ? ((a.usage || 0) / a.limit) * 100 : 100;
  const critical  = raw.filter(a => a.type === 'ANOMALY' || getPct(a) > 90).length;
  const warning   = raw.filter(a => a.type === 'QUOTA' && getPct(a) > 75 && getPct(a) <= 90).length;
  const healthy   = raw.filter(a => a.type === 'QUOTA' && getPct(a) <= 75).length;
  const anomalies = raw
    .filter(a => a.type === 'ANOMALY' && (a.usage || 0) > 0)
    .map(a => ({ label: a.service || 'Unknown', v: a.usage || 0 }))
    .sort((a, b) => b.v - a.v)
    .slice(0, 8);
  return { total: raw.length, critical, warning, healthy, anomalies };
};

// Waste → /api/xamops/waste
interface WasteResource { resourceType: string; monthlySavings: number }
interface WasteSummary { total: number; totalSavings: number; byType: { label: string; v: number }[]; lambdaCount: number; lambdaSavings: number }

const computeWasteSummary = (raw: WasteResource[]): WasteSummary => {
  const typeMap: Record<string, number> = {};
  let totalSavings = 0;
  let lambdaCount = 0;
  let lambdaSavings = 0;
  for (const r of raw) {
    const savings = r.monthlySavings || 0;
    totalSavings += savings;
    typeMap[r.resourceType] = (typeMap[r.resourceType] || 0) + savings;
    if (r.resourceType === 'Lambda') { lambdaCount++; lambdaSavings += savings; }
  }
  const byType = Object.entries(typeMap)
    .map(([label, v]) => ({ label, v }))
    .sort((a, b) => b.v - a.v)
    .slice(0, 8);
  return { total: raw.length, totalSavings, byType, lambdaCount, lambdaSavings };
};

// Rightsizing → /api/xamops/rightsizing/recommendations
interface RightsizingRec { estimatedMonthlySavings: number; instanceName?: string; resourceId?: string }
interface RightsizingSummary { total: number; totalSavings: number; byInstance: { label: string; v: number }[] }

const computeRightsizingSummary = (raw: RightsizingRec[]): RightsizingSummary => {
  const totalSavings = raw.reduce((s, r) => s + (r.estimatedMonthlySavings || 0), 0);
  const byInstance   = raw
    .map(r => ({ label: r.instanceName || r.resourceId || 'Unknown instance', v: r.estimatedMonthlySavings || 0 }))
    .filter(d => d.v > 0)
    .sort((a, b) => b.v - a.v)
    .slice(0, 8);
  return { total: raw.length, totalSavings, byInstance };
};

// Tickets → /api/{xamops,billops}/tickets/category/{Technical, Account and Billing}
interface RawTicket { id: number; status?: string; category?: string; accountId?: string }
interface TicketSummary { open: number; closed: number; total: number; technical: number; accountBilling: number }

const computeTicketSummary = (raw: RawTicket[], accountId: string | null): TicketSummary => {
  // Deduplicate by id (a ticket may be returned by more than one endpoint)
  const seen = new Set<number>();
  let tickets = raw.filter(t => {
    if (t == null || t.id == null || seen.has(t.id)) return false;
    seen.add(t.id);
    return true;
  });
  // Scope to the selected account when present — mirrors TicketsPage
  if (accountId) {
    tickets = tickets.filter(t => t.accountId && t.accountId === accountId);
  }
  let open = 0, closed = 0, technical = 0, accountBilling = 0;
  for (const t of tickets) {
    if ((t.status || '').toUpperCase() === 'CLOSED') closed++; else open++;
    if ((t.category || '').toLowerCase().includes('billing')) accountBilling++; else technical++;
  }
  return { open, closed, total: tickets.length, technical, accountBilling };
};

// ══════════════════════════════════════════════════════════════════════════════
// 1 ── ALERTS card  (real: /api/xamops/cloudguard/alerts)
// ══════════════════════════════════════════════════════════════════════════════
const AlertsCard: React.FC<{ summary: AlertSummary | null; loading: boolean; provider?: string }> = ({ summary, loading, provider }) => {
  if (loading) return (
    <CardShell accentColor="#ef4444" icon="mdi:bell-alert-outline" title="Alerts" subtitle="Loading…" link="/alerts">
      <div className="h-[96px] flex items-center justify-center">
        <div className="w-5 h-5 rounded-full border-2 border-[#ef4444] border-t-transparent animate-spin" />
      </div>
    </CardShell>
  );

  const subtitle = summary
    ? `${summary.total} total · ${summary.critical} critical · ${summary.warning} warning`
    : 'No alert data';

  return (
    <CardShell accentColor="#ef4444" icon="mdi:bell-alert-outline" title="Alerts & Anomalies" subtitle={subtitle} link="/alerts">
      {summary && summary.anomalies.length > 0
        ? <TopList data={summary.anomalies} color="#ef4444" provider={provider} />
        : summary && summary.critical > 0
          ? <ChartEmpty color="#ef4444" icon="mdi:alert-circle" line1={`${summary.critical} critical`} line2="alerts require action" />
          : <ChartEmpty color="#10b981" icon="mdi:check-circle-outline" line1="No active alerts" />}
    </CardShell>
  );
};

// ══════════════════════════════════════════════════════════════════════════════
// 2 ── SECURITY card  (real: /api/xamops/security/findings)
// ══════════════════════════════════════════════════════════════════════════════
interface SecCounts { Critical: number; High: number; Medium: number; Low: number }

const SecurityCard: React.FC<{ accountId: string | null; apiBase: string }> = ({ accountId, apiBase }) => {
  const [counts, setCounts] = useState<SecCounts | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!accountId) return;
    setLoading(true);
    fetch(`${apiBase}/security/findings?accountId=${accountId}`, { credentials: 'include' })
      .then(r => r.ok ? r.json() : null)
      .then((data: any) => {
        if (!data) return;
        const arr: any[] = Array.isArray(data) ? data : (data.findings || []);
        setCounts({
          Critical: arr.filter(f => (f.severity || '').toUpperCase() === 'CRITICAL').length,
          High:     arr.filter(f => (f.severity || '').toUpperCase() === 'HIGH').length,
          Medium:   arr.filter(f => (f.severity || '').toUpperCase() === 'MEDIUM').length,
          Low:      arr.filter(f => (f.severity || '').toUpperCase() === 'LOW').length,
        });
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [accountId]);

  const segs: DonutSeg[] = [
    { label: 'Critical', value: counts?.Critical || 0, color: '#dc2626' },
    { label: 'High',     value: counts?.High     || 0, color: '#f97316' },
    { label: 'Medium',   value: counts?.Medium   || 0, color: '#f59e0b' },
    { label: 'Low',      value: counts?.Low      || 0, color: '#facc15' },
  ];
  const total = segs.reduce((s, d) => s + d.value, 0);

  return (
    <CardShell accentColor="#dc2626" icon="mdi:shield-alert-outline" title="Security"
      subtitle={loading ? 'Scanning…' : counts ? `${total.toLocaleString()} total findings` : !accountId ? 'No account' : 'No scan data'}
      link="/security">
      <MiniDonut segs={segs} sub="findings" isLoading={loading && !counts} />
    </CardShell>
  );
};

// ══════════════════════════════════════════════════════════════════════════════
// 3 ── WASTE card  (real: /api/xamops/waste)
// ══════════════════════════════════════════════════════════════════════════════
const WasteCard: React.FC<{ summary: WasteSummary | null; loading: boolean; provider?: string }> = ({ summary, loading, provider }) => {
  if (loading) return (
    <CardShell accentColor="#f59e0b" icon="mdi:delete-sweep-outline" title="Waste" subtitle="Loading…" link="/waste">
      <div className="h-[96px] flex items-center justify-center">
        <div className="w-5 h-5 rounded-full border-2 border-[#f59e0b] border-t-transparent animate-spin" />
      </div>
    </CardShell>
  );

  const count = summary?.total || 0;
  const savings = summary?.totalSavings || 0;
  const subtitle = count > 0
    ? `${count} items · ${fmt(savings, provider)}/mo savings`
    : 'No waste detected';

  return (
    <CardShell accentColor="#f59e0b" icon="mdi:delete-sweep-outline" title="Waste" subtitle={subtitle} link="/waste">
      {summary && summary.byType.length > 0
        ? <TopList data={summary.byType} color="#f59e0b" suffix="/mo" provider={provider} />
        : <ChartEmpty color="#10b981" icon="mdi:check-circle-outline" line1="No waste detected" />}
    </CardShell>
  );
};

// ══════════════════════════════════════════════════════════════════════════════
// 4 ── RIGHTSIZING card  (real: /api/xamops/rightsizing/recommendations)
// ══════════════════════════════════════════════════════════════════════════════
const RightsizingCard: React.FC<{ summary: RightsizingSummary | null; loading: boolean; provider?: string }> = ({ summary, loading, provider }) => {
  if (loading) return (
    <CardShell accentColor="#8b5cf6" icon="mdi:server-outline" title="Rightsizing" subtitle="Loading…" link="/rightsizing">
      <div className="h-[96px] flex items-center justify-center">
        <div className="w-5 h-5 rounded-full border-2 border-[#8b5cf6] border-t-transparent animate-spin" />
      </div>
    </CardShell>
  );

  const count = summary?.total || 0;
  const savings = summary?.totalSavings || 0;
  const subtitle = count > 0
    ? `${count} instance${count !== 1 ? 's' : ''} · ${fmt(savings, provider)}/mo savings`
    : 'All instances right-sized';

  return (
    <CardShell accentColor="#8b5cf6" icon="mdi:server-outline" title="Rightsizing" subtitle={subtitle} link="/rightsizing">
      {summary && summary.byInstance.length > 0
        ? <TopList data={summary.byInstance} color="#8b5cf6" suffix="/mo" provider={provider} />
        : count > 0
          ? <ChartEmpty color="#8b5cf6" icon="mdi:server" line1={`${count} instance${count !== 1 ? 's' : ''}`} line2="awaiting savings data" />
          : <ChartEmpty color="#10b981" icon="mdi:check-circle-outline" line1="All right-sized" />}
    </CardShell>
  );
};

// ══════════════════════════════════════════════════════════════════════════════
// 5 ── LAMBDA card  (derived from waste data, resourceType === 'Lambda')
// ══════════════════════════════════════════════════════════════════════════════
const LambdaCard: React.FC<{ wasteSummary: WasteSummary | null; loading: boolean; provider?: string }> = ({ wasteSummary, loading, provider }) => {
  if (loading) return (
    <CardShell accentColor="#0ea5e9" icon="mdi:lightning-bolt-outline" title="Lambda" subtitle="Loading…" link="/waste">
      <div className="h-[96px] flex items-center justify-center">
        <div className="w-5 h-5 rounded-full border-2 border-[#0ea5e9] border-t-transparent animate-spin" />
      </div>
    </CardShell>
  );

  const count   = wasteSummary?.lambdaCount   || 0;
  const savings = wasteSummary?.lambdaSavings || 0;
  const subtitle = count > 0
    ? `${count} function${count !== 1 ? 's' : ''} · ${fmt(savings, provider)}/mo savings`
    : 'No Lambda waste';

  return (
    <CardShell accentColor="#0ea5e9" icon="mdi:lightning-bolt-outline" title="Lambda Waste" subtitle={subtitle} link="/waste">
      {count > 0
        ? <ChartEmpty color="#0ea5e9" icon="mdi:lightning-bolt" line1={`${count} function${count !== 1 ? 's' : ''}`} line2={`${fmt(savings, provider)}/mo waste detected`} />
        : <ChartEmpty color="#10b981" icon="mdi:check-circle-outline" line1="No Lambda waste" />}
    </CardShell>
  );
};

// ══════════════════════════════════════════════════════════════════════════════
// 6 ── TICKETS card  (real: /api/{xamops,billops}/tickets/category/{...})
// ══════════════════════════════════════════════════════════════════════════════
const TicketsCard: React.FC<{ summary: TicketSummary | null; loading: boolean }> = ({ summary, loading }) => {
  const segs: DonutSeg[] = [
    { label: 'Open',   value: summary?.open   || 0, color: '#059669' },
    { label: 'Closed', value: summary?.closed || 0, color: '#94a3b8' },
  ];
  const subtitle = loading
    ? 'Loading…'
    : summary
      ? `${summary.technical} technical · ${summary.accountBilling} billing`
      : 'No ticket data';

  return (
    <CardShell accentColor="#0d9488" icon="mdi:ticket-outline" title="Support Tickets" subtitle={subtitle} link="/tickets">
      <MiniDonut segs={segs} sub="tickets" isLoading={loading && !summary} />
    </CardShell>
  );
};

// ══════════════════════════════════════════════════════════════════════════════
// Section — fetches data and distributes to cards
// ══════════════════════════════════════════════════════════════════════════════
const SummaryCardsSection: React.FC<SummaryCardsSectionProps> = ({ accountId, isLoading, provider = 'AWS' }) => {
  const apiBase = provider === 'GCP' ? '/api/xamops/gcp' : provider === 'Azure' ? '/api/xamops/azure' : '/api/xamops';

  const [alertSummary,      setAlertSummary]      = useState<AlertSummary | null>(null);
  const [wasteSummary,      setWasteSummary]      = useState<WasteSummary | null>(null);
  const [rightsizeSummary,  setRightsizeSummary]  = useState<RightsizingSummary | null>(null);
  const [ticketSummary,     setTicketSummary]     = useState<TicketSummary | null>(null);
  const [alertLoading,      setAlertLoading]      = useState(false);
  const [wasteLoading,      setWasteLoading]      = useState(false);
  const [rightsizeLoading,  setRightsizeLoading]  = useState(false);
  const [ticketLoading,     setTicketLoading]     = useState(false);

  useEffect(() => {
    if (!accountId) return;

    // Alerts
    setAlertLoading(true);
    fetch(`${apiBase}/cloudguard/alerts?accountId=${accountId}`, { credentials: 'include' })
      .then(r => r.ok ? r.json() : [])
      .then((data: any) => {
        const arr: RawAlert[] = Array.isArray(data) ? data : (data.alerts || data.data || []);
        setAlertSummary(computeAlertSummary(arr));
      })
      .catch(() => setAlertSummary({ total: 0, critical: 0, warning: 0, healthy: 0, anomalies: [] }))
      .finally(() => setAlertLoading(false));

    // Waste
    setWasteLoading(true);
    fetch(`${apiBase}/waste?accountIds=${accountId}`, { credentials: 'include' })
      .then(r => r.ok ? r.json() : [])
      .then((data: any) => {
        const arr: WasteResource[] = Array.isArray(data) ? data : (data.resources || data.data || []);
        setWasteSummary(computeWasteSummary(arr));
      })
      .catch(() => setWasteSummary({ total: 0, totalSavings: 0, byType: [], lambdaCount: 0, lambdaSavings: 0 }))
      .finally(() => setWasteLoading(false));

    // Rightsizing
    setRightsizeLoading(true);
    fetch(`${apiBase}/rightsizing/recommendations?accountId=${accountId}`, { credentials: 'include' })
      .then(r => r.ok ? r.json() : [])
      .then((data: any) => {
        const arr: RightsizingRec[] = Array.isArray(data) ? data : (data.recommendations || data.data || []);
        setRightsizeSummary(computeRightsizingSummary(arr));
      })
      .catch(() => setRightsizeSummary({ total: 0, totalSavings: 0, byInstance: [] }))
      .finally(() => setRightsizeLoading(false));
  }, [accountId, apiBase]);

  // Tickets — cross-service (xamops + billops) and cross-category. Not
  // provider-specific, so fetch regardless of provider and scope by accountId.
  useEffect(() => {
    const categories = ['Technical', 'Account and Billing'];
    const services = ['xamops', 'billops'];
    const urls = services.flatMap(svc =>
      categories.map(cat => `/api/${svc}/tickets/category/${encodeURIComponent(cat)}`)
    );

    setTicketLoading(true);
    Promise.all(
      urls.map(u =>
        fetch(u, { credentials: 'include' })
          .then(r => (r.ok ? r.json() : []))
          .then((data: any) => (Array.isArray(data) ? data : []))
          .catch(() => [])
      )
    )
      .then((lists: RawTicket[][]) => {
        setTicketSummary(computeTicketSummary(lists.flat(), accountId));
      })
      .catch(() => setTicketSummary({ open: 0, closed: 0, total: 0, technical: 0, accountBilling: 0 }))
      .finally(() => setTicketLoading(false));
  }, [accountId]);

  if (isLoading) {
    return (
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
        {[1, 2, 3, 4, 5, 6].map(i => <CardSkeleton key={i} />)}
      </div>
    );
  }

  // Lambda is an AWS service. On the Azure and GCP dashboards this card rendered
  // "No Lambda waste" against a provider that has no Lambda, which reads as a
  // verified all-clear for something that was never checked.
  const showLambdaCard = !provider || provider.toUpperCase() === 'AWS';

  return (
    <div className={`grid grid-cols-2 md:grid-cols-3 gap-4 ${showLambdaCard ? 'lg:grid-cols-6' : 'lg:grid-cols-5'}`}>
      <AlertsCard     summary={alertSummary}     loading={alertLoading} provider={provider} />
      <SecurityCard   accountId={accountId} apiBase={apiBase} />
      <WasteCard      summary={wasteSummary}      loading={wasteLoading} provider={provider} />
      <RightsizingCard summary={rightsizeSummary} loading={rightsizeLoading} provider={provider} />
      {showLambdaCard && (
        <LambdaCard   wasteSummary={wasteSummary} loading={wasteLoading} provider={provider} />
      )}
      <TicketsCard    summary={ticketSummary}     loading={ticketLoading} />
    </div>
  );
};

export default SummaryCardsSection;
