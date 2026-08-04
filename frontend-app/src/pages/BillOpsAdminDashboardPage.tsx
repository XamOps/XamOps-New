/**
 * BillOps Admin Dashboard Page — Tabbed Layout
 *
 * Redesigned to match the crisp AWS / FinOps aesthetic:
 *  - Clean white cards, tinted icon badges, condensed metric numerals
 *  - Iconify icons, segmented pill tabs, rounded status badges
 *
 * Tabs:
 *  1. Overview  — Stats, Charts, Top 10 Accounts (with MoM % change)
 *  2. Clients   — Per-client spend grouped from top accounts
 *  3. Invoices  — Recent invoices with status filter
 *  4. Tickets   — Recent tickets with severity/status filter
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Chart from 'chart.js/auto';
import { Icon } from '@iconify/react';
import { useBillOpsAdminDashboard } from '../hooks/useBillOpsAdminDashboard';
import { useAuth } from '../contexts/AuthContext';
import { useTopbar } from '../contexts/TopbarContext';
import type { TopSpendingAccount, AdminInvoice, AdminTicket, SpendingTrendItem } from '../types/billopsAdminDashboard';
import { apiFetch } from '../utils/apiClient';
import { useCurrency } from '../hooks/useCurrency';
import { useTabState } from '../hooks/useTabState';

interface CustomerSpotSavings {
  clientId: number;
  clientName: string;
  awsSavings: number;
  gcpSavings: number;
  azureSavings: number;
  totalSavings: number;
}

interface ClientsApiAccount {
  id: number;
  name: string;
  providerAccountId: string;
  provider: string;
  consumption: number;
  creditsApplied?: number;
}

interface ClientsApiData {
  id: number;
  name: string;
  accounts: ClientsApiAccount[];
}

type ActiveTab = 'overview' | 'clients' | 'invoices' | 'tickets';

const TABS: { id: ActiveTab; label: string; icon: string }[] = [
  { id: 'overview', label: 'Overview', icon: 'mdi:chart-pie' },
  { id: 'clients', label: 'Clients', icon: 'mdi:account-group-outline' },
  { id: 'invoices', label: 'Invoices', icon: 'mdi:file-document-outline' },
  { id: 'tickets', label: 'Tickets', icon: 'mdi:ticket-outline' },
];

const PROVIDER_META: Record<string, { icon: string; color: string }> = {
  AWS: { icon: 'mdi:aws', color: '#FF9900' },
  GCP: { icon: 'mdi:google-cloud', color: '#34A853' },
  Azure: { icon: 'mdi:microsoft-azure', color: '#0078D4' },
};

// Condensed numeral style used across FinOps/Cost pages
const NUM_STYLE: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '30px',
  fontWeight: 400,
  lineHeight: '30px',
  letterSpacing: '0.01em',
  fontVariantNumeric: 'tabular-nums',
};

// Spend figures live only as long as the server-side clients cache (5 min).
const CLIENTS_CACHE_TTL_MS = 5 * 60 * 1000;
// Written by earlier builds: not keyed by user, never expired, never refreshed.
const LEGACY_CLIENTS_CACHE_KEY = 'billops_admin_clients_data';

const splitCurrency = (str: string): { main: string; cents: string } => {
  const dot = str.lastIndexOf('.');
  return dot === -1 ? { main: str, cents: '' } : { main: str.slice(0, dot), cents: str.slice(dot) };
};

const BillOpsAdminDashboardPage: React.FC = () => {
  const { data, loading, isRefreshing, isRefreshingTopSpending, error, refreshDashboard, refreshTopSpending } = useBillOpsAdminDashboard();
  const { userProfile } = useAuth();
  const { setActions, clearActions } = useTopbar();
  const { loadRates, convert } = useCurrency();

  const [activeTab, setActiveTab] = useTabState<ActiveTab>('overview');
  const [invoiceFilter, setInvoiceFilter] = useState<string>('ALL');
  const [ticketFilter, setTicketFilter] = useState<string>('ALL');

  const [customerSavings, setCustomerSavings] = useState<CustomerSpotSavings[]>([]);
  const [customerSavingsLoading, setCustomerSavingsLoading] = useState(true);

  const [clientsData, setClientsData] = useState<ClientsApiData[] | null>(null);
  const [clientsLoading, setClientsLoading] = useState(true);
  const [clientsRefreshing, setClientsRefreshing] = useState(false);
  const [clientsError, setClientsError] = useState<string | null>(null);
  const [clientsStale, setClientsStale] = useState(false);

  // Keyed by username so two admins sharing a browser never see each other's spend,
  // and stamped with a timestamp so a copy expires instead of living forever.
  const clientsCacheKeyRef = useRef(`${LEGACY_CLIENTS_CACHE_KEY}:${userProfile?.username ?? 'unknown'}`);
  useEffect(() => {
    clientsCacheKeyRef.current = `${LEGACY_CLIENTS_CACHE_KEY}:${userProfile?.username ?? 'unknown'}`;
  }, [userProfile?.username]);

  const fetchClients = useCallback(async (silent = false) => {
    if (silent) setClientsRefreshing(true); else setClientsLoading(true);
    try {
      const res = await apiFetch('/api/xamops/billops-admin/clients', { credentials: 'include' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const d = await res.json() as ClientsApiData[];
      setClientsData(d);
      setClientsError(null);
      setClientsStale(false);
      localStorage.setItem(clientsCacheKeyRef.current, JSON.stringify({ data: d, ts: Date.now() }));
    } catch (err) {
      console.error('Failed to load clients data for spend total', err);
      setClientsError(err instanceof Error ? err.message : 'Failed to load spend data');
    } finally {
      setClientsLoading(false);
      setClientsRefreshing(false);
    }
  }, []);

  useEffect(() => {
    localStorage.removeItem(LEGACY_CLIENTS_CACHE_KEY);

    const cached = localStorage.getItem(clientsCacheKeyRef.current);
    let hasLocalData = false;

    if (cached) {
      try {
        const { data: d, ts } = JSON.parse(cached) as { data: ClientsApiData[]; ts: number };
        if (Array.isArray(d)) {
          setClientsData(d);
          setClientsStale(Date.now() - ts > CLIENTS_CACHE_TTL_MS);
          setClientsLoading(false);
          hasLocalData = true;
        }
      } catch (e) { /* ignore corrupt cache */ }
    }

    // Fetch fresh data in background (silent if we have local data, otherwise show loading)
    if (!hasLocalData) {
      fetchClients(false);
    } else {
      fetchClients(true);
    }
  }, [fetchClients]);

  useEffect(() => {
    const savingsCacheKey = `billops_admin_customer_savings:${userProfile?.username ?? 'unknown'}`;
    const cached = localStorage.getItem(savingsCacheKey);

    if (cached) {
      try {
        const { data: d } = JSON.parse(cached) as { data: CustomerSpotSavings[]; ts: number };
        if (Array.isArray(d)) {
          setCustomerSavings(d);
          setCustomerSavingsLoading(false);
        }
      } catch (e) { /* ignore corrupt cache */ }
    }

    apiFetch('/api/admin/autospotting/customer-savings', { credentials: 'include' })
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then((d: CustomerSpotSavings[]) => {
        setCustomerSavings(d);
        localStorage.setItem(savingsCacheKey, JSON.stringify({ data: d, ts: Date.now() }));
      })
      .catch(err => console.error('Failed to load customer spot savings', err))
      .finally(() => setCustomerSavingsLoading(false));
  }, [userProfile?.username]);

  // Chart refs
  const providerChartRef = useRef<HTMLCanvasElement>(null);
  const trendChartRef = useRef<HTMLCanvasElement>(null);
  const providerChartInstance = useRef<Chart<'bar'> | null>(null);
  const trendChartInstance = useRef<Chart<'line'> | null>(null);

  useEffect(() => { loadRates('inr'); }, [loadRates]);

  // ── Converted data (GCP INR → USD) ────────────────────────────────────────
  const convertedData = React.useMemo(() => {
    if (!data) return null;

    const needsConversion = (provider: string) => provider === 'GCP' || provider === 'Azure';

    const convertedTopAccounts = data.topSpendingAccounts.map(account => ({
      ...account,
      currentMonthSpending: needsConversion(account.provider)
        ? (convert(account.currentMonthSpending, 'usd') ?? account.currentMonthSpending)
        : account.currentMonthSpending,
      lastMonthSpending: needsConversion(account.provider)
        ? (convert(account.lastMonthSpending, 'usd') ?? account.lastMonthSpending)
        : account.lastMonthSpending,
    }));

    const spendingByProvider = {
      AWS: data.spendingByProvider.AWS || 0,
      Azure: data.spendingByProvider.Azure
        ? (convert(data.spendingByProvider.Azure, 'usd') ?? data.spendingByProvider.Azure)
        : 0,
      GCP: data.spendingByProvider.GCP
        ? (convert(data.spendingByProvider.GCP, 'usd') ?? data.spendingByProvider.GCP)
        : 0,
    };

    const totalSpending = Object.values(spendingByProvider).reduce((s, v) => s + (v || 0), 0);

    const convertedSpendingTrend = data.spendingTrend?.map(item => {
      if (!item.costByProvider) return item;
      const aws = item.costByProvider.AWS || 0;
      const azure = convert(item.costByProvider.Azure || 0, 'usd') ?? (item.costByProvider.Azure || 0);
      const gcp = convert(item.costByProvider.GCP || 0, 'usd') ?? (item.costByProvider.GCP || 0);
      return { ...item, cost: aws + azure + gcp, costByProvider: { AWS: aws, Azure: azure, GCP: gcp } };
    }) || [];

    const convertedRecentInvoices = data.recentInvoices.map(invoice => ({
      ...invoice,
      amount: needsConversion(invoice.provider ?? '')
        ? (convert(invoice.amount, 'usd') ?? invoice.amount)
        : invoice.amount,
    }));

    return {
      ...data,
      topSpendingAccounts: convertedTopAccounts,
      totalSpending,
      spendingByProvider,
      spendingTrend: convertedSpendingTrend,
      recentInvoices: convertedRecentInvoices,
    };
  }, [data, convert]);

  // ── Spend totals from clients endpoint (same source as Client Details page) ─
  const clientsSpend = React.useMemo(() => {
    if (!clientsData) return null;
    const byProvider: Record<string, number> = { AWS: 0, GCP: 0, Azure: 0 };
    let total = 0;
    for (const client of clientsData) {
      for (const account of client.accounts) {
        const raw = account.consumption || 0;
        const inUsd = (account.provider === 'GCP' || account.provider === 'Azure')
          ? (convert(raw, 'usd') ?? raw)
          : raw;
        byProvider[account.provider] = (byProvider[account.provider] || 0) + inUsd;
        total += inUsd;
      }
    }
    return { byProvider, total };
  }, [clientsData, convert]);

  // ── All accounts derived from clientsData (same source as Client Details page) ──
  const accountsFromClients = React.useMemo(() => {
    if (!clientsData) return null;

    // Build a lookup of lastMonthSpending from the backend top accounts by providerAccountId
    const lastMonthMap: Record<string, number> = {};
    if (data?.topSpendingAccounts) {
      for (const acc of data.topSpendingAccounts) {
        if (acc.accountIdentifier) lastMonthMap[acc.accountIdentifier] = acc.lastMonthSpending ?? 0;
      }
    }

    const needsConversion = (provider: string) => provider === 'GCP' || provider === 'Azure';

    const allAccounts: TopSpendingAccount[] = [];
    for (const client of clientsData) {
      for (const acc of client.accounts) {
        const raw = acc.consumption || 0;
        const currentMonthSpending = needsConversion(acc.provider)
          ? (convert(raw, 'usd') ?? raw)
          : raw;
        const rawLast = lastMonthMap[acc.providerAccountId] ?? 0;
        const lastMonthSpending = needsConversion(acc.provider)
          ? (convert(rawLast, 'usd') ?? rawLast)
          : rawLast;
        allAccounts.push({
          id: String(acc.id),
          accountName: acc.name,
          accountIdentifier: acc.providerAccountId,
          provider: acc.provider as 'AWS' | 'GCP' | 'Azure',
          clientName: client.name,
          status: 'ACTIVE',
          currentMonthSpending,
          lastMonthSpending,
        });
      }
    }

    return allAccounts.sort((a, b) => (b.currentMonthSpending ?? 0) - (a.currentMonthSpending ?? 0));
  }, [clientsData, data, convert]);

  const top10FromClients = React.useMemo(
    () => accountsFromClients?.slice(0, 10) ?? [],
    [accountsFromClients],
  );

  // ── Clients grouped from the same account list the totals are built from ──
  const clientGroups = React.useMemo(() => {
    if (!accountsFromClients) return [];
    const map: Record<string, { clientName: string; totalSpend: number; accounts: TopSpendingAccount[] }> = {};
    for (const acc of accountsFromClients) {
      if (!map[acc.clientName]) map[acc.clientName] = { clientName: acc.clientName, totalSpend: 0, accounts: [] };
      map[acc.clientName].totalSpend += acc.currentMonthSpending || 0;
      map[acc.clientName].accounts.push(acc);
    }
    return Object.values(map).sort((a, b) => b.totalSpend - a.totalSpend);
  }, [accountsFromClients]);

  // ── Filtered invoices/tickets ──────────────────────────────────────────────
  const filteredInvoices = React.useMemo(() => {
    if (!convertedData?.recentInvoices) return [];
    return invoiceFilter === 'ALL'
      ? convertedData.recentInvoices
      : convertedData.recentInvoices.filter(i => i.status === invoiceFilter);
  }, [convertedData, invoiceFilter]);

  const filteredTickets = React.useMemo(() => {
    if (!data?.recentTickets) return [];
    return ticketFilter === 'ALL'
      ? data.recentTickets
      : data.recentTickets.filter(t => t.status === ticketFilter || t.severity === ticketFilter);
  }, [data, ticketFilter]);

  // ── Format helpers ─────────────────────────────────────────────────────────
  const formatCurrency = (value: number | null | undefined): string => {
    if (value === null || value === undefined) return '$0.00';
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2 }).format(value);
  };

  const getMoMChange = (current: number, last: number): { pct: number; up: boolean } | null => {
    if (!last || last === 0) return null;
    const pct = ((current - last) / last) * 100;
    return { pct: Math.abs(pct), up: pct >= 0 };
  };

  const getStatusClass = (status: string): string => ({
    ACTIVE: 'admin-status-active', PENDING: 'admin-status-pending', INACTIVE: 'admin-status-inactive',
    DRAFT: 'admin-status-draft', FINALIZED: 'admin-status-finalized', PAID: 'admin-status-paid',
    OVERDUE: 'admin-status-overdue', OPEN: 'admin-status-open', IN_PROGRESS: 'admin-status-in-progress',
    CLOSED: 'admin-status-closed',
  }[status] || '');

  const getSeverityClass = (severity?: string): string => ({
    LOW: 'admin-severity-low', MEDIUM: 'admin-severity-medium',
    HIGH: 'admin-severity-high', CRITICAL: 'admin-severity-critical',
  }[severity || ''] || '');

  const ProviderBadge: React.FC<{ provider: string }> = ({ provider }) => {
    const meta = PROVIDER_META[provider] ?? { icon: 'mdi:cloud-outline', color: '#6b7280' };
    return (
      <span className="admin-provider-badge">
        <Icon icon={meta.icon} width={13} color={meta.color} />
        <span>{provider}</span>
      </span>
    );
  };

  // ── Charts ─────────────────────────────────────────────────────────────────
  useEffect(() => {
    const spendingByProvider = clientsSpend?.byProvider;
    if (!providerChartRef.current || !spendingByProvider) return;
    if (providerChartInstance.current) providerChartInstance.current.destroy();
    const ctx = providerChartRef.current.getContext('2d');
    if (!ctx) return;
    const labels = Object.keys(spendingByProvider);
    const values = Object.values(spendingByProvider) as number[];
    if (values.every(v => v === 0)) return;
    const total = values.reduce((s, v) => s + v, 0);
    const colorMap: Record<string, { bar: string; hover: string }> = {
      AWS:   { bar: '#FF9900', hover: '#E88B00' },
      Azure: { bar: '#0078D4', hover: '#0369B4' },
      GCP:   { bar: '#34A853', hover: '#2C9247' },
    };
    const bgColors   = labels.map(l => colorMap[l]?.bar   ?? '#6366f1');
    const hoverColors = labels.map(l => colorMap[l]?.hover ?? '#4f46e5');
    providerChartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels,
        datasets: [{
          data: values,
          backgroundColor: bgColors,
          hoverBackgroundColor: hoverColors,
          borderRadius: 6,
          borderSkipped: false,
          barThickness: 30,
        }],
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: '#ffffff',
            titleColor: '#0a0a0a',
            bodyColor: 'rgba(0,0,0,0.6)',
            borderColor: 'rgba(0,0,0,0.1)',
            borderWidth: 1,
            padding: 12,
            cornerRadius: 10,
            titleFont: { family: 'Inter', size: 12, weight: '600' },
            bodyFont: { family: 'Inter', size: 12 },
            callbacks: {
              label: (item) => {
                const pct = total > 0 ? ((item.raw as number) / total * 100).toFixed(1) : '0.0';
                return `  ${formatCurrency(item.raw as number)}   ${pct}%`;
              },
            },
          },
        },
        scales: {
          x: {
            grid: { color: 'rgba(0,0,0,0.05)' },
            ticks: {
              font: { family: 'Inter', size: 11 },
              color: 'rgba(0,0,0,0.4)',
              callback: (v) => `$${Number(v).toLocaleString()}`,
            },
          },
          y: {
            grid: { display: false },
            ticks: { font: { family: 'Inter', size: 13, weight: '600' }, color: 'rgba(0,0,0,0.75)' },
          },
        },
      },
    });
    return () => { providerChartInstance.current?.destroy(); };
  }, [clientsSpend]);

  useEffect(() => {
    if (!trendChartRef.current || !convertedData?.spendingTrend?.length) return;
    if (trendChartInstance.current) trendChartInstance.current.destroy();
    const ctx = trendChartRef.current.getContext('2d');
    if (!ctx) return;
    // Vertical gradient fill: blue at top → transparent at bottom
    const gradient = ctx.createLinearGradient(0, 0, 0, 220);
    gradient.addColorStop(0, 'rgba(66,133,244,0.20)');
    gradient.addColorStop(0.6, 'rgba(66,133,244,0.05)');
    gradient.addColorStop(1, 'rgba(66,133,244,0)');

    trendChartInstance.current = new Chart(ctx, {
      type: 'line',
      data: {
        labels: convertedData.spendingTrend.map((i: SpendingTrendItem) => i.date),
        datasets: [{
          label: 'Monthly Spending (USD)',
          data: convertedData.spendingTrend.map((i: SpendingTrendItem) => i.cost),
          borderColor: '#4285F4',
          backgroundColor: gradient,
          borderWidth: 2.5,
          fill: true,
          tension: 0.45,
          pointBackgroundColor: '#4285F4',
          pointBorderColor: '#fff',
          pointBorderWidth: 2,
          pointRadius: 4,
          pointHoverRadius: 6,
          pointHoverBackgroundColor: '#1a73e8',
          pointHoverBorderColor: '#fff',
          pointHoverBorderWidth: 2,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: '#ffffff',
            titleColor: '#0a0a0a',
            bodyColor: 'rgba(0,0,0,0.6)',
            borderColor: 'rgba(0,0,0,0.1)',
            borderWidth: 1,
            padding: 12,
            cornerRadius: 10,
            displayColors: false,
            titleFont: { family: 'Inter', size: 12, weight: '600' },
            bodyFont: { family: 'Inter', size: 12 },
            callbacks: {
              title: (items) => items[0].label,
              label: (item) => `  Spend: ${formatCurrency(item.raw as number)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: false,
            grid: { color: 'rgba(0,0,0,0.05)', lineWidth: 1 },
            ticks: {
              callback: (v) => `$${Number(v).toLocaleString()}`,
              font: { family: 'Inter', size: 11 },
              color: 'rgba(0,0,0,0.4)',
              maxTicksLimit: 5,
            },
          },
          x: {
            grid: { display: false },
            ticks: {
              font: { family: 'Inter', size: 11 },
              color: 'rgba(0,0,0,0.45)',
            },
          },
        },
      },
    });
    return () => { trendChartInstance.current?.destroy(); };
  }, [convertedData?.spendingTrend]);

  // ── Topbar actions ─────────────────────────────────────────────────────────
  // Refresh has to cover BOTH sources — the spend figures come from the clients
  // endpoint, so refreshing only the dashboard endpoint leaves them untouched.
  const refreshAll = useCallback(async () => {
    refreshDashboard();
    try {
      await apiFetch('/api/xamops/billops-admin/clients/cache', { method: 'DELETE', credentials: 'include' });
    } catch (err) {
      console.error('Failed to evict server clients cache', err);
    }
    fetchClients(true);
  }, [refreshDashboard, fetchClients]);

  useEffect(() => {
    const busy = isRefreshing || clientsRefreshing;
    setActions(
      <div className="flex items-center gap-2">
        <div className="flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse flex-shrink-0" />
          <span className="text-[11px] font-medium uppercase tracking-wide text-[rgba(0,0,0,0.4)]">Live</span>
        </div>
        <span className="text-[rgba(0,0,0,0.15)] text-[12px] select-none">|</span>
        <button
          onClick={refreshAll} disabled={busy}
          className="flex items-center gap-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-colors disabled:opacity-40"
        >
          <Icon icon="mdi:refresh" width={14} className={busy ? 'animate-spin' : ''} />
          {busy ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, data?.lastUpdated, isRefreshing, clientsRefreshing, refreshAll]);

  // ── Shimmer skeleton ───────────────────────────────────────────────────────
  const Shimmer = ({ className }: { className: string }) => (
    <div className={`admin-shimmer rounded-md ${className}`} />
  );

  // ── Reusable metric card ─────────────────────────────────────────────────
  interface MetricCardProps {
    label: string;
    icon: string;
    iconColor: string;
    index: number;
    loading?: boolean;
    onClick?: () => void;
    currency?: string;      // pre-formatted currency string ($1,234.56)
    count?: React.ReactNode; // plain numeric / node value
    sub?: React.ReactNode;
    right?: React.ReactNode;
  }
  const MetricCard: React.FC<MetricCardProps> = ({ label, icon, iconColor, index, loading, onClick, currency, count, sub, right }) => {
    const split = currency ? splitCurrency(currency) : null;
    return (
      <div
        onClick={onClick}
        className={`bo-card p-5 relative overflow-hidden card-hover-lift ${onClick ? 'cursor-pointer' : ''}`}
        style={{ animation: `fadeInUp 0.5s cubic-bezier(0.22, 1, 0.36, 1) ${0.05 + index * 0.08}s both` }}
      >
        <div className="bo-metric-cloud" aria-hidden>
          <Icon icon="mdi:cloud" width={120} />
        </div>
        <div className="relative z-10">
          <div className="flex items-start justify-between">
            <p className="text-[11px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.55)]">{label}</p>
            <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0"
              style={{ backgroundColor: `${iconColor}14` }}>
              <Icon icon={icon} width={17} color={iconColor} />
            </div>
          </div>
          {loading ? (
            <div className="space-y-2 mt-3"><Shimmer className="h-7 w-28" /><Shimmer className="h-2.5 w-24" /></div>
          ) : (
            <div className="admin-fade-in mt-2.5">
              <div className="flex items-end justify-between">
                <p className="leading-none flex items-baseline gap-0.5 text-[rgba(10,10,10,1)]">
                  {split ? (
                    <>
                      <span style={NUM_STYLE}>{split.main}</span>
                      {split.cents && <span className="text-[14px] text-[rgba(0,0,0,0.5)]">{split.cents}</span>}
                    </>
                  ) : (
                    <span style={NUM_STYLE}>{count}</span>
                  )}
                </p>
                {right}
              </div>
              {sub && <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-2">{sub}</p>}
            </div>
          )}
        </div>
      </div>
    );
  };

  // ── Section card header ──────────────────────────────────────────────────
  const SectionHeader: React.FC<{ icon: string; color: string; title: string; subtitle: string; right?: React.ReactNode }> =
    ({ icon, color, title, subtitle, right }) => (
      <div className="flex items-center justify-between px-5 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0"
            style={{ background: `linear-gradient(135deg, ${color}22, ${color}11)` }}>
            <Icon icon={icon} width={18} color={color} />
          </div>
          <div className="min-w-0">
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] truncate">{title}</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] truncate">{subtitle}</p>
          </div>
        </div>
        {right && <div className="flex-shrink-0">{right}</div>}
      </div>
    );

  // Segmented filter pills (Cost Explorer style)
  const FilterPills: React.FC<{ options: { value: string; label: string }[]; value: string; onChange: (v: string) => void }> =
    ({ options, value, onChange }) => (
      <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5 gap-0.5">
        {options.map(o => (
          <button key={o.value} onClick={() => onChange(o.value)}
            className={`px-2.5 py-1 rounded-md text-[12px] font-medium transition-all ${
              value === o.value ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'
            }`}>{o.label}</button>
        ))}
      </div>
    );

  const totalInvoices = (data?.pendingInvoices || 0) + (data?.finalizedInvoices || 0);

  // ══════════════════════════════════════════════════════════════════════════
  return (
    <div className="admin-dashboard flex-1 flex flex-col h-full overflow-hidden">
      <main className="flex-1 overflow-y-auto px-6 pt-4 pb-6 space-y-5 admin-scrollbar">

        {/* Error */}
        {error && (
          <div className="bo-card border-l-4 border-l-red-500 p-3.5 flex items-center gap-2.5">
            <Icon icon="mdi:alert-circle-outline" width={18} color="#dc2626" />
            <span className="text-red-700 text-[13px] font-medium">{error}</span>
          </div>
        )}

        {/* ── Stats Grid (always visible) ─────────────────────────────────── */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          <MetricCard
            index={0}
            label="Total System Spend"
            icon="mdi:currency-usd"
            iconColor="#4285F4"
            loading={clientsLoading}
            currency={clientsSpend ? formatCurrency(clientsSpend.total) : undefined}
            count={clientsSpend ? undefined : '—'}
            sub={
              clientsSpend
                ? (clientsError
                    ? <span className="text-amber-600">Cached copy — refresh failed</span>
                    : clientsStale
                      ? <span className="text-amber-600">Cached · refreshing…</span>
                      : 'All clients · current month')
                : <span className="text-red-600">Spend data unavailable — press Refresh</span>
            }
          />

          <MetricCard
            index={1}
            label="Cloud Accounts"
            icon="mdi:layers-triple-outline"
            iconColor="#7C3AED"
            loading={loading}
            count={data?.totalAccounts || 0}
            right={
              <div className="admin-provider-stack mb-0.5">
                <div className="admin-provider-icon" title="AWS"><Icon icon="mdi:aws" width={12} color="#FF9900" /></div>
                <div className="admin-provider-icon" title="GCP"><Icon icon="mdi:google-cloud" width={12} color="#34A853" /></div>
                <div className="admin-provider-icon" title="Azure"><Icon icon="mdi:microsoft-azure" width={12} color="#0078D4" /></div>
              </div>
            }
            sub={`${data?.awsAccountsCount || 0} AWS · ${data?.gcpAccountsCount || 0} GCP · ${data?.azureAccountsCount || 0} Azure`}
          />

          <MetricCard
            index={2}
            label="Invoices"
            icon="mdi:file-document-multiple-outline"
            iconColor="#0891B2"
            loading={loading}
            onClick={() => setActiveTab('invoices')}
            count={totalInvoices}
            sub={
              <>
                <span className="text-amber-600 font-semibold">{data?.pendingInvoices || 0} pending</span>
                <span className="mx-1 text-[rgba(0,0,0,0.2)]">·</span>
                <span className="text-[#158045] font-semibold">{data?.finalizedInvoices || 0} finalized</span>
              </>
            }
          />

          <MetricCard
            index={3}
            label="Open Tickets"
            icon="mdi:ticket-confirmation-outline"
            iconColor="#EA580C"
            loading={loading}
            onClick={() => setActiveTab('tickets')}
            count={data?.openTickets || 0}
            sub={<><span className="text-[rgba(0,0,0,0.7)] font-semibold">{data?.totalClients || 0}</span> total clients</>}
          />
        </div>

        {/* ── Tab Navigation ───────────────────────────────────────────────── */}
        <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-xl p-1 gap-1 w-fit">
          {TABS.map(tab => {
            const isActive = activeTab === tab.id;
            const badge =
              tab.id === 'invoices' && (data?.pendingInvoices || 0) > 0 ? { text: String(data?.pendingInvoices), cls: 'bg-amber-100 text-amber-700' }
              : tab.id === 'tickets' && (data?.openTickets || 0) > 0 ? { text: String(data?.openTickets), cls: 'bg-red-100 text-red-600' }
              : null;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`flex items-center gap-1.5 px-3.5 py-2 rounded-lg text-[13px] font-medium transition-all ${
                  isActive ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.8)]'
                }`}
              >
                <Icon icon={tab.icon} width={15} />
                {tab.label}
                {badge && (
                  <span className={`ml-0.5 min-w-[18px] h-[18px] px-1 flex items-center justify-center text-[10px] font-bold rounded-full ${badge.cls}`}>
                    {badge.text}
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {/* ══════════════════════════════════════════════════════════════════
            TAB 1 — OVERVIEW
        ══════════════════════════════════════════════════════════════════ */}
        {activeTab === 'overview' && (
          <div className="space-y-5">
            {/* Charts Row */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
              <div className="bo-card flex flex-col">
                <SectionHeader
                  icon="mdi:chart-donut" color="#4285F4"
                  title="Global Spending Distribution"
                  subtitle="By cloud provider · current month"
                />
                <div className="p-5">
                  {!clientsLoading && !Object.values(clientsSpend?.byProvider ?? {}).some(v => v > 0) ? (
                    <div className="flex flex-col items-center justify-center h-[220px] text-[rgba(0,0,0,0.25)]">
                      <Icon icon={clientsError ? 'mdi:cloud-off-outline' : 'mdi:chart-pie'} width={44} />
                      <p className="text-[13px] font-medium mt-2 text-[rgba(0,0,0,0.35)]">
                        {clientsError ? 'Spend data unavailable — press Refresh to retry' : 'No spending data available'}
                      </p>
                    </div>
                  ) : (
                    <div className="admin-chart-container"><canvas ref={providerChartRef} /></div>
                  )}
                </div>
              </div>

              <div className="bo-card flex flex-col">
                <SectionHeader
                  icon="mdi:chart-areaspline" color="#7C3AED"
                  title="Spending Trend"
                  subtitle="Aggregated cloud spend in USD · last 6 months"
                />
                <div className="p-5">
                  {!loading && !convertedData?.spendingTrend?.length ? (
                    <div className="flex flex-col items-center justify-center h-[220px] text-[rgba(0,0,0,0.25)]">
                      <Icon icon="mdi:chart-line" width={44} />
                      <p className="text-[13px] font-medium mt-2 text-[rgba(0,0,0,0.35)]">No trend data available</p>
                    </div>
                  ) : (
                    <div className="admin-chart-container"><canvas ref={trendChartRef} /></div>
                  )}
                </div>
              </div>
            </div>

            {/* Top 10 Accounts */}
            <div className="bo-card overflow-hidden">
              <SectionHeader
                icon="mdi:trophy-outline" color="#0891B2"
                title="Top 10 Spending Accounts"
                subtitle="Ranked by current month spend"
                right={
                  <button
                    onClick={refreshTopSpending}
                    disabled={isRefreshingTopSpending}
                    title="Fetch live current-month cost from CE API"
                    className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium text-[#0891B2] bg-[#0891B212] hover:bg-[#0891B21f] border border-[#0891B233] rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <Icon icon="mdi:lightning-bolt" width={13} className={isRefreshingTopSpending ? 'animate-pulse' : ''} />
                    {isRefreshingTopSpending ? 'Fetching…' : 'Live Refresh'}
                  </button>
                }
              />
              <div className="overflow-x-auto">
                {clientsLoading ? (
                  <div className="p-5 space-y-2">{[1, 2, 3].map(i => <Shimmer key={i} className="h-9 w-full" />)}</div>
                ) : (
                  <table className="bo-table min-w-full">
                    <thead>
                      <tr>
                        <th className="bo-th text-left">#</th>
                        <th className="bo-th text-left">Account</th>
                        <th className="bo-th text-left">Provider</th>
                        <th className="bo-th text-left">Client</th>
                        <th className="bo-th text-left">Status</th>
                        <th className="bo-th text-right">This Month</th>
                        <th className="bo-th text-right">Last Month</th>
                        <th className="bo-th text-right">MoM</th>
                      </tr>
                    </thead>
                    <tbody>
                      {top10FromClients?.length ? (
                        top10FromClients.map((account: TopSpendingAccount, idx: number) => {
                          const mom = getMoMChange(account.currentMonthSpending, account.lastMonthSpending);
                          return (
                            <tr key={account.id} className="bo-row">
                              <td className="bo-td text-[rgba(0,0,0,0.35)] font-medium">
                                <span className={`bo-rank ${idx < 3 ? 'bo-rank-top' : ''}`}>{idx + 1}</span>
                              </td>
                              <td className="bo-td whitespace-nowrap">
                                <div className="text-[13px] font-medium text-[rgba(0,0,0,0.85)]">{account.accountName}</div>
                                {account.accountIdentifier && (
                                  <div className="text-[10px] text-[rgba(0,0,0,0.4)] font-mono mt-0.5">{account.accountIdentifier}</div>
                                )}
                              </td>
                              <td className="bo-td whitespace-nowrap"><ProviderBadge provider={account.provider} /></td>
                              <td className="bo-td whitespace-nowrap text-[13px] text-[rgba(0,0,0,0.6)]">{account.clientName}</td>
                              <td className="bo-td whitespace-nowrap">
                                <span className={`admin-status-badge ${getStatusClass(account.status)}`}>{account.status}</span>
                              </td>
                              <td className="bo-td whitespace-nowrap text-[13px] text-right font-semibold text-[rgba(0,0,0,0.85)]">
                                {formatCurrency(account.currentMonthSpending)}
                              </td>
                              <td className="bo-td whitespace-nowrap text-[13px] text-right text-[rgba(0,0,0,0.4)]">
                                {formatCurrency(account.lastMonthSpending)}
                              </td>
                              <td className="bo-td whitespace-nowrap text-[13px] text-right">
                                {mom ? (
                                  <span className={`inline-flex items-center gap-1 font-semibold text-[12px] ${mom.up ? 'text-red-600' : 'text-[#158045]'}`}>
                                    <Icon icon={mom.up ? 'mdi:trending-up' : 'mdi:trending-down'} width={13} />
                                    {mom.pct.toFixed(1)}%
                                  </span>
                                ) : <span className="text-[rgba(0,0,0,0.2)]">—</span>}
                              </td>
                            </tr>
                          );
                        })
                      ) : (
                        <tr><td colSpan={8} className="px-4 py-10 text-center text-[rgba(0,0,0,0.4)] text-[13px]">
                          {clientsError ? 'Spend data unavailable — press Refresh to retry' : 'No account data available'}
                        </td></tr>
                      )}
                    </tbody>
                  </table>
                )}
              </div>
            </div>

            {/* AutoSpotting Savings */}
            <div className="bo-card overflow-hidden">
              <SectionHeader
                icon="mdi:lightning-bolt-outline" color="#16A34A"
                title="AutoSpotting Savings"
                subtitle="Customers with AutoSpotting enabled"
                right={
                  <Link to="/autospotting-admin" className="text-[12px] font-medium text-[#16A34A] hover:text-[#0f7a37] hover:underline">
                    Full Report →
                  </Link>
                }
              />
              <div className="overflow-x-auto">
                {customerSavingsLoading ? (
                  <div className="p-5 space-y-2">{[1, 2, 3].map(i => <Shimmer key={i} className="h-9 w-full" />)}</div>
                ) : customerSavings.length === 0 ? (
                  <div className="px-6 py-12 text-center text-[rgba(0,0,0,0.4)] text-[13px]">
                    <Icon icon="mdi:lightning-bolt-outline" width={40} className="mx-auto mb-2 text-[rgba(0,0,0,0.2)]" />
                    No customers have AutoSpotting enabled yet
                  </div>
                ) : (
                  <table className="bo-table min-w-full">
                    <thead>
                      <tr>
                        <th className="bo-th text-left">Customer</th>
                        <th className="bo-th text-right"><Icon icon="mdi:aws" width={13} color="#FF9900" className="inline mr-1" />AWS</th>
                        <th className="bo-th text-right"><Icon icon="mdi:google-cloud" width={13} color="#34A853" className="inline mr-1" />GCP</th>
                        <th className="bo-th text-right"><Icon icon="mdi:microsoft-azure" width={13} color="#0078D4" className="inline mr-1" />Azure</th>
                        <th className="bo-th text-right">Total Saved</th>
                      </tr>
                    </thead>
                    <tbody>
                      {customerSavings.map(row => (
                        <tr key={row.clientId} className="bo-row">
                          <td className="bo-td text-[13px] font-medium text-[rgba(0,0,0,0.85)]">{row.clientName}</td>
                          <td className="bo-td text-[13px] text-right text-[rgba(0,0,0,0.6)]">{row.awsSavings > 0 ? formatCurrency(row.awsSavings) : <span className="text-[rgba(0,0,0,0.2)]">—</span>}</td>
                          <td className="bo-td text-[13px] text-right text-[rgba(0,0,0,0.6)]">{row.gcpSavings > 0 ? formatCurrency(row.gcpSavings) : <span className="text-[rgba(0,0,0,0.2)]">—</span>}</td>
                          <td className="bo-td text-[13px] text-right text-[rgba(0,0,0,0.6)]">{row.azureSavings > 0 ? formatCurrency(row.azureSavings) : <span className="text-[rgba(0,0,0,0.2)]">—</span>}</td>
                          <td className="bo-td text-[13px] text-right font-semibold text-[#158045]">{formatCurrency(row.totalSavings)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </div>
          </div>
        )}

        {/* ══════════════════════════════════════════════════════════════════
            TAB 2 — CLIENTS
        ══════════════════════════════════════════════════════════════════ */}
        {activeTab === 'clients' && (
          <div className="bo-card overflow-hidden">
            <SectionHeader
              icon="mdi:account-group-outline" color="#7C3AED"
              title="Client Spending Summary"
              subtitle={`${clientGroups.length} client${clientGroups.length !== 1 ? 's' : ''} · current month spend`}
            />
            <div className="overflow-x-auto">
              {clientsLoading ? (
                <div className="p-5 space-y-2">{[1, 2, 3].map(i => <Shimmer key={i} className="h-12 w-full" />)}</div>
              ) : clientGroups.length === 0 ? (
                <div className="px-6 py-12 text-center text-[rgba(0,0,0,0.4)] text-[13px]">
                  <Icon icon={clientsError ? 'mdi:cloud-off-outline' : 'mdi:account-group-outline'} width={40} className="mx-auto mb-2 text-[rgba(0,0,0,0.2)]" />
                  {clientsError ? 'Spend data unavailable — press Refresh to retry' : 'No client data available'}
                </div>
              ) : (
                <table className="bo-table min-w-full">
                  <thead>
                    <tr>
                      <th className="bo-th text-left">Client</th>
                      <th className="bo-th text-center">Accounts</th>
                      <th className="bo-th text-center">Providers</th>
                      <th className="bo-th text-right">This Month</th>
                      <th className="bo-th text-left">Top Account</th>
                    </tr>
                  </thead>
                  <tbody>
                    {clientGroups.map(group => {
                      const providers = [...new Set(group.accounts.map(a => a.provider))];
                      const topAccount = group.accounts[0];
                      return (
                        <tr key={group.clientName} className="bo-row">
                          <td className="bo-td whitespace-nowrap">
                            <div className="flex items-center gap-2.5">
                              <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-[#7C3AED] to-[#6366f1] flex items-center justify-center text-white font-semibold text-[12px]">
                                {group.clientName.charAt(0).toUpperCase()}
                              </div>
                              <span className="text-[13px] font-medium text-[rgba(0,0,0,0.85)]">{group.clientName}</span>
                            </div>
                          </td>
                          <td className="bo-td text-center">
                            <span className="inline-flex items-center justify-center px-2 py-0.5 bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.6)] text-[12px] font-semibold rounded-full">
                              {group.accounts.length}
                            </span>
                          </td>
                          <td className="bo-td text-center">
                            <div className="flex items-center justify-center gap-1">
                              {providers.map(p => <ProviderBadge key={p} provider={p} />)}
                            </div>
                          </td>
                          <td className="bo-td whitespace-nowrap text-right font-semibold text-[rgba(0,0,0,0.85)] text-[13px]">
                            {formatCurrency(group.totalSpend)}
                          </td>
                          <td className="bo-td whitespace-nowrap text-[13px] text-[rgba(0,0,0,0.5)]">
                            {topAccount?.accountName}
                            {topAccount?.accountIdentifier && (
                              <span className="ml-2 text-[10px] text-[rgba(0,0,0,0.4)] font-mono">{topAccount.accountIdentifier}</span>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )}

        {/* ══════════════════════════════════════════════════════════════════
            TAB 3 — INVOICES
        ══════════════════════════════════════════════════════════════════ */}
        {activeTab === 'invoices' && (
          <div className="bo-card overflow-hidden">
            <SectionHeader
              icon="mdi:file-document-outline" color="#0891B2"
              title="Recent Invoices"
              subtitle="Last 10 invoices across all clients"
              right={
                <div className="flex items-center gap-3">
                  <FilterPills
                    value={invoiceFilter}
                    onChange={setInvoiceFilter}
                    options={[
                      { value: 'ALL', label: 'All' }, { value: 'DRAFT', label: 'Draft' },
                      { value: 'FINALIZED', label: 'Finalized' }, { value: 'PAID', label: 'Paid' },
                      { value: 'OVERDUE', label: 'Overdue' },
                    ]}
                  />
                  <Link to="/billops-admin-invoices" className="text-[12px] font-medium text-[#4285F4] hover:underline whitespace-nowrap">
                    View All →
                  </Link>
                </div>
              }
            />
            <div className="overflow-x-auto">
              {loading ? (
                <div className="p-5 space-y-2">{[1, 2, 3, 4].map(i => <Shimmer key={i} className="h-10 w-full" />)}</div>
              ) : filteredInvoices.length === 0 ? (
                <div className="px-6 py-12 text-center text-[rgba(0,0,0,0.4)] text-[13px]">
                  <Icon icon="mdi:file-document-outline" width={40} className="mx-auto mb-2 text-[rgba(0,0,0,0.2)]" />
                  {invoiceFilter === 'ALL' ? 'No recent invoices found' : `No ${invoiceFilter.toLowerCase()} invoices`}
                </div>
              ) : (
                <table className="bo-table min-w-full">
                  <thead>
                    <tr>
                      <th className="bo-th text-left">Invoice #</th>
                      <th className="bo-th text-left">Client</th>
                      <th className="bo-th text-left">Account</th>
                      <th className="bo-th text-left">Provider</th>
                      <th className="bo-th text-right">Amount</th>
                      <th className="bo-th text-center">Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredInvoices.map((invoice: AdminInvoice) => (
                      <tr key={invoice.id} className={`bo-row ${invoice.status === 'OVERDUE' ? 'bg-red-50/40' : ''}`}>
                        <td className="bo-td whitespace-nowrap">
                          <div className="flex items-center gap-2">
                            {invoice.status === 'OVERDUE' && <Icon icon="mdi:alert-circle" width={14} color="#ef4444" />}
                            <span className="text-[13px] font-medium text-[rgba(0,0,0,0.85)]">{invoice.invoiceNumber}</span>
                          </div>
                        </td>
                        <td className="bo-td whitespace-nowrap text-[13px] text-[rgba(0,0,0,0.7)]">{invoice.clientName}</td>
                        <td className="bo-td whitespace-nowrap text-[13px] text-[rgba(0,0,0,0.5)]">{invoice.accountName}</td>
                        <td className="bo-td whitespace-nowrap">
                          {invoice.provider && <ProviderBadge provider={invoice.provider} />}
                        </td>
                        <td className="bo-td whitespace-nowrap text-[13px] text-right font-semibold text-[rgba(0,0,0,0.85)]">
                          {formatCurrency(invoice.amount)}
                        </td>
                        <td className="bo-td whitespace-nowrap text-center">
                          <span className={`admin-status-badge ${getStatusClass(invoice.status)}`}>{invoice.status}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )}

        {/* ══════════════════════════════════════════════════════════════════
            TAB 4 — TICKETS
        ══════════════════════════════════════════════════════════════════ */}
        {activeTab === 'tickets' && (
          <div className="bo-card overflow-hidden">
            <SectionHeader
              icon="mdi:ticket-outline" color="#EA580C"
              title="Recent Tickets"
              subtitle="Last 10 tickets across all clients"
              right={
                <div className="flex items-center gap-3">
                  <FilterPills
                    value={ticketFilter}
                    onChange={setTicketFilter}
                    options={[
                      { value: 'ALL', label: 'All' }, { value: 'OPEN', label: 'Open' },
                      { value: 'IN_PROGRESS', label: 'In Progress' }, { value: 'CLOSED', label: 'Closed' },
                      { value: 'HIGH', label: 'High' }, { value: 'CRITICAL', label: 'Critical' },
                    ]}
                  />
                  <Link to="/billops-admin-tickets" className="text-[12px] font-medium text-[#4285F4] hover:underline whitespace-nowrap">
                    View All →
                  </Link>
                </div>
              }
            />
            <div className="overflow-x-auto">
              {loading ? (
                <div className="p-5 space-y-2">{[1, 2, 3, 4].map(i => <Shimmer key={i} className="h-10 w-full" />)}</div>
              ) : filteredTickets.length === 0 ? (
                <div className="px-6 py-12 text-center text-[rgba(0,0,0,0.4)] text-[13px]">
                  <Icon icon="mdi:ticket-outline" width={40} className="mx-auto mb-2 text-[rgba(0,0,0,0.2)]" />
                  {ticketFilter === 'ALL' ? 'No recent tickets found' : `No ${ticketFilter.toLowerCase()} tickets`}
                </div>
              ) : (
                <table className="bo-table min-w-full">
                  <thead>
                    <tr>
                      <th className="bo-th text-left">Subject</th>
                      <th className="bo-th text-left">Category</th>
                      <th className="bo-th text-center">Severity</th>
                      <th className="bo-th text-center">Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredTickets.map((ticket: AdminTicket) => (
                      <tr key={ticket.id} className={`bo-row ${ticket.severity === 'CRITICAL' ? 'bg-red-50/30' : ''}`}>
                        <td className="bo-td">
                          <div className="flex items-center gap-2">
                            {ticket.severity === 'CRITICAL' && <Icon icon="mdi:fire" width={14} color="#ef4444" />}
                            <span className="text-[13px] font-medium text-[rgba(0,0,0,0.85)]">{ticket.subject}</span>
                          </div>
                        </td>
                        <td className="bo-td whitespace-nowrap text-[11px] text-[rgba(0,0,0,0.5)] uppercase font-medium tracking-wide">
                          {ticket.category}
                        </td>
                        <td className="bo-td whitespace-nowrap text-center">
                          {ticket.severity ? (
                            <span className={`text-[11px] font-semibold uppercase ${getSeverityClass(ticket.severity)}`}>
                              {ticket.severity}
                            </span>
                          ) : <span className="text-[rgba(0,0,0,0.2)]">—</span>}
                        </td>
                        <td className="bo-td whitespace-nowrap text-center">
                          <span className={`admin-status-badge ${getStatusClass(ticket.status)}`}>
                            {ticket.status.replace('_', ' ')}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )}

      </main>
    </div>
  );
};

export default BillOpsAdminDashboardPage;
