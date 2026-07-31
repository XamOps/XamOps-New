/**
 * BillOps Tickets Page
 *
 * Caching strategy — same 4-layer approach as TicketsPage:
 * 1. React Query in-memory cache (staleTime 60s)
 * 2. localStorage persistence via persistQueryClient — survives reload
 * 3. Stale-while-revalidate via placeholderData
 * 4. 30-second background poll
 *
 * Real-time: WebSocket STOMP on /topic/tickets marks stale (no immediate refetch,
 * so optimistic data is never wiped by a cache-warm response).
 *
 * Optimistic UI: new ticket appears instantly from POST response; 30s poll reconciles.
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import { DialogRoot, DialogPanel } from '../components/ui';
import {
  BillOpsTicket,
  CreateTicketFormData,
  TicketSeverity,
  SERVICE_OPTIONS,
} from '../types/billopsTickets';
import { SEVERITY_OPTIONS } from '../constants/ticketSeverity';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const POLL_INTERVAL_MS = 30_000;

const DEFAULT_FORM: CreateTicketFormData = {
  category: 'Account and Billing',
  service: 'Billing Inquiry',
  severity: '',
  subject: '',
  description: '',
  accountId: '',
  region: '',
};

// ---------------------------------------------------------------------------
// React Query key factory
// ---------------------------------------------------------------------------

export const billopsTicketKeys = {
  all: (accountIds: string[]) =>
    ['billops-tickets', [...accountIds].sort().join(',')] as const,
};

// ---------------------------------------------------------------------------
// Fetcher
// ---------------------------------------------------------------------------

async function fetchBillOpsTickets(
  accountIds: string[],
  signal?: AbortSignal
): Promise<BillOpsTicket[]> {
  const res = await fetch('/api/billops/tickets/category/Account%20and%20Billing', {
    credentials: 'include',
    signal,
  });

  if (res.status === 401 || res.status === 403) {
    window.location.href = '/login';
    return [];
  }
  if (!res.ok) throw new Error(`Failed to fetch billing tickets (${res.status})`);

  let data: BillOpsTicket[] = await res.json();
  if (!Array.isArray(data)) data = [];

  // Filter by selected accounts
  if (accountIds.length > 0) {
    data = data.filter((t) => t.accountId && accountIds.includes(t.accountId));
  }

  // Sort: most recently active first (reply or creation)
  data.sort((a, b) => {
    const aT = new Date(a.updatedAt ?? a.createdAt).getTime();
    const bT = new Date(b.updatedAt ?? b.createdAt).getTime();
    return bT - aT;
  });

  return data;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Status = the primary visual anchor: solid pill, saturation dialled back a touch. */
function getStatusPillClass(status: string): string {
  switch (status.toUpperCase()) {
    case 'OPEN': return 'bg-emerald-500/85 text-white';
    case 'IN_PROGRESS': return 'bg-amber-500/85 text-white';
    case 'CLOSED': return 'bg-[rgba(0,0,0,0.32)] text-white';
    default: return 'bg-[rgba(0,0,0,0.6)] text-white';
  }
}

function formatStatus(status: string): string {
  return status.replace(/_/g, ' ');
}

/**
 * Priority = coloured text + icon only (no pill background) so it never competes
 * with the status pill. Normal/Low stay neutral — only real escalations shout.
 */
function getSeverityMeta(severity: string): { icon: string; className: string } {
  const s = severity.toLowerCase();
  if (s === 'urgent' || s === 'critical') {
    return { icon: 'mdi:alert-circle', className: 'text-red-600 font-semibold' };
  }
  if (s === 'high') {
    return { icon: 'mdi:alert', className: 'text-orange-600 font-semibold' };
  }
  if (s === 'normal' || s === 'medium') {
    return { icon: 'mdi:minus-circle-outline', className: 'text-[rgba(0,0,0,0.55)] font-medium' };
  }
  return { icon: 'mdi:arrow-down-circle-outline', className: 'text-[rgba(0,0,0,0.4)] font-medium' };
}

/** Left-edge triage strip. Falls back to the black hover accent for calm rows. */
function getRowAccentClass(severity: string): string {
  const s = severity.toLowerCase();
  if (s === 'urgent' || s === 'critical') return 'bg-red-500';
  if (s === 'high') return 'bg-orange-400';
  return 'bg-transparent group-hover:bg-[rgba(0,0,0,0.88)]';
}

const SEVERITY_RANK: Record<string, number> = {
  critical: 0, urgent: 0, high: 1, normal: 2, medium: 2, low: 3, info: 4,
};

function severityRank(severity: string): number {
  return SEVERITY_RANK[severity.toLowerCase()] ?? 5;
}

const STATUS_RANK: Record<string, number> = { OPEN: 0, IN_PROGRESS: 1, CLOSED: 2 };

/**
 * Shared column template — the sticky header and every row use the exact same
 * grid so the pills always line up under their label.
 * Columns: Subject | Category | Priority | Date | Status | chevron
 */
const GRID_CLASS =
  'grid items-center gap-x-3 grid-cols-[minmax(0,1fr)_112px_24px] sm:grid-cols-[minmax(0,1fr)_92px_112px_24px] lg:grid-cols-[minmax(0,1fr)_150px_92px_82px_112px_24px]';

const PAGE_SIZE_OPTIONS = [10, 15, 25, 50];

type Density = 'comfortable' | 'compact';

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

const BillOpsTicketsPage: React.FC = () => {
  const {
    selectedAccountIds,
    accounts,
    isLoading: accountsLoading,
  } = useAccounts();
  const { setActions, clearActions } = useTopbarActions();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const hasAccounts = accounts.length > 0;

  const accountNameMap = React.useMemo(() => {
    const map: Record<string, string> = {};
    accounts.forEach((a) => { map[a.id] = a.name; });
    return map;
  }, [accounts]);

  // ---------------------------------------------------------------------------
  // React Query — fetch + cache + poll
  // ---------------------------------------------------------------------------

  const queryKey = billopsTicketKeys.all(selectedAccountIds);

  const {
    data: tickets = [],
    isFetching,
    isLoading: isFirstLoad,
    isError,
    refetch,
  } = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchBillOpsTickets(selectedAccountIds, signal),
    enabled: !accountsLoading && hasAccounts,
    staleTime: 60 * 1000,
    refetchInterval: POLL_INTERVAL_MS,
    refetchIntervalInBackground: false,
    placeholderData: (prev) => prev,
  });

  // ---------------------------------------------------------------------------
  // WebSocket — real-time stale marking (no immediate refetch)
  // ---------------------------------------------------------------------------

  useEffect(() => {
    if (!hasAccounts) return;

    let stompClient: any = null;

    const connect = async () => {
      try {
        const SockJS = (await import('sockjs-client')).default;
        const Stomp = (await import('stompjs')).default;

        const socket = new SockJS('/xamops-ws');
        stompClient = Stomp.over(socket);
        stompClient.debug = null;

        stompClient.connect({}, () => {
          stompClient.subscribe('/topic/tickets', () => {
            // Mark stale only — an immediate refetch would hit the still-warm Redis
            // cache and wipe any optimistic data from setQueryData.
            qc.invalidateQueries({ queryKey: ['billops-tickets'], refetchType: 'none' });
          });
        });
      } catch {
        // WebSocket not available — polling covers us
      }
    };

    connect();

    return () => {
      try { stompClient?.disconnect(); } catch { /* ignore */ }
    };
  }, [hasAccounts, qc]);

  // ---------------------------------------------------------------------------
  // Force-refetch on mount if user replied/closed from the detail page.
  // The detail page sets a localStorage flag (ticket:updated fires but this
  // component is unmounted by then, so the invalidate listener misses it).
  // ---------------------------------------------------------------------------

  useEffect(() => {
    if (localStorage.getItem('billops_user_tickets_needs_refresh') === '1') {
      localStorage.removeItem('billops_user_tickets_needs_refresh');
      qc.invalidateQueries({ queryKey: ['billops-tickets'] });
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---------------------------------------------------------------------------
  // Listen for ticket-updated event from detail page
  // ---------------------------------------------------------------------------

  useEffect(() => {
    const handler = () => qc.invalidateQueries({ queryKey: ['billops-tickets'] });
    globalThis.addEventListener('ticket-updated', handler);
    globalThis.addEventListener('ticket:updated', handler);
    return () => {
      globalThis.removeEventListener('ticket-updated', handler);
      globalThis.removeEventListener('ticket:updated', handler);
    };
  }, [qc]);

  // ---------------------------------------------------------------------------
  // Modal state
  // ---------------------------------------------------------------------------

  const [showModal, setShowModal] = useState(false);
  const [form, setForm] = useState<CreateTicketFormData>({
    ...DEFAULT_FORM,
    accountId: selectedAccountIds[0] ?? '',
  });

  useEffect(() => {
    setForm((prev) => ({ ...prev, accountId: selectedAccountIds[0] ?? '' }));
  }, [selectedAccountIds]);

  const openCreateModalRef = useRef<() => void>(() => { });

  const openCreateModal = useCallback(() => {
    setForm({ ...DEFAULT_FORM, accountId: selectedAccountIds[0] ?? '' });
    setShowModal(true);
  }, [selectedAccountIds]);

  useEffect(() => { openCreateModalRef.current = openCreateModal; }, [openCreateModal]);

  const closeModal = useCallback(() => setShowModal(false), []);

  // ---------------------------------------------------------------------------
  // Create ticket mutation — optimistic update
  // ---------------------------------------------------------------------------

  const createMutation = useMutation({
    mutationFn: async (payload: CreateTicketFormData) => {
      const res = await fetch('/api/billops/tickets', {
        credentials: 'include',
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!res.ok) throw new Error('Failed to submit ticket');
      return res.json() as Promise<BillOpsTicket>;
    },

    onMutate: async () => {
      setShowModal(false);
      await qc.cancelQueries({ queryKey });
    },

    onSuccess: (newTicket) => {
      qc.setQueryData<BillOpsTicket[]>(queryKey, (prev = []) => [
        newTicket,
        ...prev.filter((t) => t.id !== newTicket.id),
      ]);
    },

    onError: (err) => {
      console.error('Error creating billing ticket:', err);
      alert('There was an error submitting your case. Please try again.');
    },
  });

  const submitTicket = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.service || !form.subject.trim() || !form.description.trim() || !form.severity) {
      alert('Please fill in all required fields.');
      return;
    }
    createMutation.mutate({
      ...form,
      accountId: form.accountId || selectedAccountIds[0] || '',
    });
  };

  // ---------------------------------------------------------------------------
  // Derived render flags
  // ---------------------------------------------------------------------------

  const showSkeleton = (accountsLoading || isFirstLoad) && tickets.length === 0;
  const showNoAccounts = !accountsLoading && !hasAccounts;
  const showEmpty = !isFirstLoad && !accountsLoading && hasAccounts && tickets.length === 0 && !isError;
  const showError = isError && tickets.length === 0;
  const showTickets = tickets.length > 0;

  // ---------------------------------------------------------------------------
  // List controls — search / filters / sort / density / pagination
  // All client-side: the full ticket set is already in the cache.
  // ---------------------------------------------------------------------------

  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [serviceFilter, setServiceFilter] = useState('all');
  const [severityFilter, setSeverityFilter] = useState('all');
  const [sortDesc, setSortDesc] = useState(true);
  const [density, setDensity] = useState<Density>(() => {
    const stored = localStorage.getItem('tickets_density');
    return stored === 'compact' ? 'compact' : 'comfortable';
  });
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(15);

  useEffect(() => {
    localStorage.setItem('tickets_density', density);
  }, [density]);

  const serviceOptions = React.useMemo(() => {
    const set = new Set<string>();
    tickets.forEach((t) => { if (t.service) set.add(t.service); });
    return [...set].sort((a, b) => a.localeCompare(b));
  }, [tickets]);

  const statusOptions = React.useMemo(() => {
    const set = new Set<string>();
    tickets.forEach((t) => { if (t.status) set.add(t.status.toUpperCase()); });
    return [...set].sort((a, b) => (STATUS_RANK[a] ?? 9) - (STATUS_RANK[b] ?? 9));
  }, [tickets]);

  const severityOptions = React.useMemo(() => {
    const set = new Set<string>();
    tickets.forEach((t) => { if (t.severity) set.add(t.severity); });
    return [...set].sort((a, b) => severityRank(a) - severityRank(b));
  }, [tickets]);

  const filteredTickets = React.useMemo(() => {
    const q = search.trim().toLowerCase();
    const result = tickets.filter((t) => {
      if (statusFilter !== 'all' && t.status.toUpperCase() !== statusFilter) return false;
      if (serviceFilter !== 'all' && t.service !== serviceFilter) return false;
      if (severityFilter !== 'all' && t.severity !== severityFilter) return false;
      if (!q) return true;
      return (
        t.subject?.toLowerCase().includes(q) ||
        String(t.id).includes(q) ||
        t.service?.toLowerCase().includes(q) ||
        t.severity?.toLowerCase().includes(q) ||
        formatStatus(t.status).toLowerCase().includes(q)
      );
    });

    return result.sort((a, b) => {
      const aT = new Date(a.updatedAt ?? a.createdAt).getTime() || 0;
      const bT = new Date(b.updatedAt ?? b.createdAt).getTime() || 0;
      return sortDesc ? bT - aT : aT - bT;
    });
  }, [tickets, search, statusFilter, serviceFilter, severityFilter, sortDesc]);

  const hasActiveFilters =
    search.trim() !== '' || statusFilter !== 'all' || serviceFilter !== 'all' || severityFilter !== 'all';

  const clearFilters = useCallback(() => {
    setSearch('');
    setStatusFilter('all');
    setServiceFilter('all');
    setSeverityFilter('all');
  }, []);

  useEffect(() => {
    setPage(1);
  }, [search, statusFilter, serviceFilter, severityFilter, pageSize]);

  const totalPages = Math.max(1, Math.ceil(filteredTickets.length / pageSize));
  const currentPage = Math.min(page, totalPages);
  const rangeStart = filteredTickets.length === 0 ? 0 : (currentPage - 1) * pageSize + 1;
  const rangeEnd = Math.min(currentPage * pageSize, filteredTickets.length);

  const pagedTickets = React.useMemo(
    () => filteredTickets.slice((currentPage - 1) * pageSize, currentPage * pageSize),
    [filteredTickets, currentPage, pageSize]
  );

  // Account context — shown once at the top instead of on every row
  const accountContext = React.useMemo(() => {
    const ids = new Set<string>();
    tickets.forEach((t) => { if (t.accountId) ids.add(t.accountId); });
    const list = [...ids];
    if (list.length === 1) {
      return { multiple: false, id: list[0], name: accountNameMap[list[0]] ?? list[0], count: 1 };
    }
    return { multiple: list.length > 1, id: null, name: null, count: list.length };
  }, [tickets, accountNameMap]);

  const rowPadding = density === 'compact' ? 'py-2' : 'py-3.5';
  const subjectSize = density === 'compact' ? 'text-[12.5px]' : 'text-[13.5px]';

  // ---------------------------------------------------------------------------
  // Topbar
  // ---------------------------------------------------------------------------

  const openCount = tickets.filter((t) => t.status === 'OPEN').length;
  const inProgressCount = tickets.filter((t) => t.status === 'IN_PROGRESS').length;

  useEffect(() => {
    const actionsNode = (
      <div className="flex items-center gap-3">
        {showTickets && (
          <div className="hidden xl:flex items-center gap-4 pr-4 border-r border-[rgba(0,0,0,0.12)]">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
              <span className="text-[10px] font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Open</span>
              <span className="text-xs font-bold text-[rgba(0,0,0,0.88)]">{openCount}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-amber-500" />
              <span className="text-[10px] font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">In Progress</span>
              <span className="text-xs font-bold text-[rgba(0,0,0,0.88)]">{inProgressCount}</span>
            </div>
          </div>
        )}

        <button
          onClick={() => refetch()}
          disabled={isFetching}
          className="w-8 h-8 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] transition-colors disabled:opacity-50"
          title="Refresh Tickets"
        >
          <Icon icon="mdi:refresh" width={16} className={isFetching ? 'animate-spin' : ''} />
        </button>

        <button
          onClick={() => openCreateModalRef.current()}
          className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
        >
          <Icon icon="mdi:plus" width={14} />
          <span>Create New Case</span>
        </button>
      </div>
    );

    setActions(actionsNode);
    return () => clearActions();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openCount, inProgressCount, isFetching, showTickets]);

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <main className="flex-1 px-6 pt-4 pb-28 bg-[#fafafa] min-h-screen">
      <div className="space-y-3">

        {/* Account context — shown once here instead of repeated on every row */}
        {showTickets && (
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2 min-w-0">
              <Icon icon="mdi:cloud-outline" width={14} className="text-[rgba(0,0,0,0.35)] shrink-0" />
              {accountContext.multiple ? (
                <span className="text-[12px] font-medium text-[rgba(0,0,0,0.6)]">
                  {accountContext.count} accounts
                </span>
              ) : (
                <span className="flex items-baseline gap-2 min-w-0">
                  <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.72)] truncate">
                    {accountContext.name}
                  </span>
                  <span className="text-[10.5px] font-mono text-[rgba(0,0,0,0.35)] truncate">
                    {accountContext.id}
                  </span>
                </span>
              )}
            </div>

            {isFetching && !isFirstLoad && (
              <span className="flex items-center gap-1.5 text-[10px] font-medium text-[rgba(0,0,0,0.35)] uppercase tracking-wider">
                <Icon icon="mdi:refresh" width={10} className="animate-spin" />
                Refreshing
              </span>
            )}
          </div>
        )}

        {/* Toolbar — search, filters, sort, density */}
        {showTickets && (
          <div className="flex flex-wrap items-center gap-2 p-2 bg-white border border-[#e5e7eb] rounded-xl">
            {/* Search */}
            <div className="relative flex-1 min-w-[200px]">
              <Icon
                icon="mdi:magnify"
                width={14}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] pointer-events-none"
              />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search by subject, case # or service"
                aria-label="Search tickets"
                className="w-full h-8 pl-8 pr-8 rounded-lg border border-[#e5e7eb] bg-[#fafafa] text-[12px] text-[rgba(0,0,0,0.8)] placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:bg-white focus:border-[rgba(0,0,0,0.4)] transition-colors"
              />
              {search && (
                <button
                  onClick={() => setSearch('')}
                  aria-label="Clear search"
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.7)] transition-colors"
                >
                  <Icon icon="mdi:close-circle" width={14} />
                </button>
              )}
            </div>

            {/* Status filter */}
            <div className="relative">
              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                aria-label="Filter by status"
                className="h-8 pl-3 pr-8 rounded-lg border border-[#e5e7eb] bg-white text-[12px] font-medium text-[rgba(0,0,0,0.7)] appearance-none focus:outline-none focus:border-[rgba(0,0,0,0.4)] cursor-pointer capitalize"
              >
                <option value="all">All statuses</option>
                {statusOptions.map((s) => (
                  <option key={s} value={s} className="capitalize">{formatStatus(s.toLowerCase())}</option>
                ))}
              </select>
              <Icon icon="mdi:chevron-down" width={13} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)] pointer-events-none" />
            </div>

            {/* Category filter */}
            <div className="relative">
              <select
                value={serviceFilter}
                onChange={(e) => setServiceFilter(e.target.value)}
                aria-label="Filter by category"
                className="h-8 pl-3 pr-8 rounded-lg border border-[#e5e7eb] bg-white text-[12px] font-medium text-[rgba(0,0,0,0.7)] appearance-none focus:outline-none focus:border-[rgba(0,0,0,0.4)] cursor-pointer"
              >
                <option value="all">All categories</option>
                {serviceOptions.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
              <Icon icon="mdi:chevron-down" width={13} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)] pointer-events-none" />
            </div>

            {/* Priority filter */}
            <div className="relative">
              <select
                value={severityFilter}
                onChange={(e) => setSeverityFilter(e.target.value)}
                aria-label="Filter by priority"
                className="h-8 pl-3 pr-8 rounded-lg border border-[#e5e7eb] bg-white text-[12px] font-medium text-[rgba(0,0,0,0.7)] appearance-none focus:outline-none focus:border-[rgba(0,0,0,0.4)] cursor-pointer"
              >
                <option value="all">All priorities</option>
                {severityOptions.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
              <Icon icon="mdi:chevron-down" width={13} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)] pointer-events-none" />
            </div>

            {/* Sort by date */}
            <button
              onClick={() => setSortDesc((v) => !v)}
              title={sortDesc ? 'Newest first' : 'Oldest first'}
              className="h-8 flex items-center gap-1.5 px-3 rounded-lg border border-[#e5e7eb] bg-white text-[12px] font-medium text-[rgba(0,0,0,0.7)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
            >
              <Icon icon={sortDesc ? 'mdi:sort-calendar-descending' : 'mdi:sort-calendar-ascending'} width={14} />
              {sortDesc ? 'Newest' : 'Oldest'}
            </button>

            {/* Density toggle */}
            <div className="h-8 flex items-center p-0.5 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[#e5e7eb]">
              {([
                { key: 'comfortable' as Density, icon: 'mdi:view-agenda-outline', label: 'Comfortable' },
                { key: 'compact' as Density, icon: 'mdi:view-list-outline', label: 'Compact' },
              ]).map((opt) => (
                <button
                  key={opt.key}
                  onClick={() => setDensity(opt.key)}
                  title={`${opt.label} density`}
                  aria-pressed={density === opt.key}
                  className={`h-7 w-7 flex items-center justify-center rounded-md transition-all ${
                    density === opt.key
                      ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                      : 'text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.6)]'
                  }`}
                >
                  <Icon icon={opt.icon} width={14} />
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Skeleton — only on true first-ever load */}
        {showSkeleton && (
          <div className="bg-white border border-[#e5e7eb] rounded-xl overflow-hidden divide-y divide-[#e5e7eb] animate-pulse">
            {[1, 2, 3, 4, 5, 6].map((i) => (
              <div key={i} className="flex items-center gap-4 px-4 py-3.5">
                <div className="flex-1 space-y-1.5">
                  <div className="h-2.5 w-[12%] bg-[rgba(0,0,0,0.06)] rounded" />
                  <div className="h-4 w-[45%] bg-[rgba(0,0,0,0.06)] rounded" />
                </div>
                <div className="h-5 w-[110px] bg-[rgba(0,0,0,0.06)] rounded" />
                <div className="h-5 w-[70px] bg-[rgba(0,0,0,0.06)] rounded" />
                <div className="h-5 w-[90px] bg-[rgba(0,0,0,0.06)] rounded" />
              </div>
            ))}
          </div>
        )}

        {/* Error */}
        {showError && (
          <div className="flex flex-col items-center justify-center py-16 text-center">
            <div className="w-14 h-14 bg-red-50 border border-red-100 rounded-xl flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:alert-circle-outline" width={28} className="text-red-400" />
            </div>
            <p className="text-[rgba(0,0,0,0.88)] font-medium mb-4">Could not load billing tickets.</p>
            <button
              onClick={() => refetch()}
              className="inline-flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white text-sm font-medium rounded-lg hover:bg-black transition-colors"
            >
              <Icon icon="mdi:refresh" width={14} />
              Try Again
            </button>
          </div>
        )}

        {/* Ticket list */}
        {showTickets && (
          <div className="bg-white border border-[#e5e7eb] rounded-xl">

            {/* Sticky column header — offset by the 48px sticky topbar.
                NOTE: the card must not use overflow-hidden, that would break sticky. */}
            <div
              className={`${GRID_CLASS} sticky top-12 z-[5] px-4 py-2 bg-[#f9fafb]/95 backdrop-blur-sm border-b border-[#e5e7eb] rounded-t-xl`}
            >
              <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-widest">
                Case
              </span>
              <span className="hidden lg:block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-widest">
                Category
              </span>
              <span className="hidden sm:block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-widest">
                Priority
              </span>
              <button
                onClick={() => setSortDesc((v) => !v)}
                className="hidden lg:flex items-center gap-1 text-[10px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-widest hover:text-[rgba(0,0,0,0.88)] transition-colors"
              >
                Date
                <Icon icon={sortDesc ? 'mdi:arrow-down' : 'mdi:arrow-up'} width={10} />
              </button>
              <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-widest">
                Status
              </span>
              <span aria-hidden="true" />
            </div>

            {/* Rows */}
            {pagedTickets.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-14 text-center px-6">
                <div className="w-11 h-11 bg-[rgba(0,0,0,0.04)] rounded-xl flex items-center justify-center mb-3">
                  <Icon icon="mdi:filter-remove-outline" width={22} className="text-[rgba(0,0,0,0.35)]" />
                </div>
                <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.78)] mb-1">No matching cases</p>
                <p className="text-[12px] text-[rgba(0,0,0,0.45)] mb-4">
                  Try a different search term or clear the active filters.
                </p>
                <button
                  onClick={clearFilters}
                  className="px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-lg text-[12px] font-medium hover:bg-black transition-colors"
                >
                  Clear filters
                </button>
              </div>
            ) : (
              <div className="divide-y divide-[#e5e7eb]">
                {pagedTickets.map((ticket) => {
                  const { icon, className: sevClass } = getSeverityMeta(ticket.severity);
                  const isOpen = ticket.status.toUpperCase() === 'OPEN';

                  return (
                    <div
                      key={ticket.id}
                      role="button"
                      tabIndex={0}
                      onClick={() => navigate(`/billops-ticket-detail?id=${ticket.id}`)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          navigate(`/billops-ticket-detail?id=${ticket.id}`);
                        }
                      }}
                      className={`${GRID_CLASS} relative px-4 ${rowPadding} cursor-pointer group transition-colors hover:bg-[#fafafa] focus:outline-none focus-visible:bg-[#f5f5f5] ${
                        ticket.hasUnreadAdminReply ? 'bg-[#fcfcfc]' : ''
                      }`}
                    >
                      {/* Left-edge accent: triage colour for urgent/high, black on hover */}
                      <span
                        aria-hidden="true"
                        className={`absolute left-0 top-0 bottom-0 w-[3px] transition-colors ${getRowAccentClass(ticket.severity)}`}
                      />

                      {/* Case # + Subject (the row title) */}
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-[9.5px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest">
                            Billing #{ticket.id}
                          </span>
                          {ticket.hasUnreadAdminReply && (
                            <span className="flex items-center gap-1 px-1.5 py-0.5 bg-[rgba(0,0,0,0.88)] text-white text-[8.5px] font-bold rounded-full uppercase tracking-widest">
                              <span className="w-1 h-1 rounded-full bg-white animate-pulse" />
                              New Reply
                            </span>
                          )}
                        </div>
                        <p className={`${subjectSize} font-semibold text-[rgba(0,0,0,0.88)] truncate leading-snug`}>
                          {ticket.subject}
                        </p>
                        {/* Small-screen fallback for hidden columns */}
                        <div className="sm:hidden flex items-center gap-2 mt-1">
                          <Icon icon={icon} width={11} className={sevClass} />
                          <span className={`text-[10px] uppercase tracking-wider ${sevClass}`}>
                            {ticket.severity}
                          </span>
                          <span className="text-[10px] text-[rgba(0,0,0,0.35)]">
                            {new Date(ticket.createdAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
                          </span>
                        </div>
                      </div>

                      {/* Category — neutral outline pill */}
                      <span className="hidden lg:inline-flex items-center justify-center px-2 py-0.5 max-w-full border border-[rgba(0,0,0,0.16)] rounded-md text-[10.5px] font-semibold text-[rgba(0,0,0,0.6)] truncate">
                        {ticket.service}
                      </span>

                      {/* Priority — icon + coloured text, no pill */}
                      <div className="hidden sm:flex items-center gap-1">
                        <Icon icon={icon} width={12} className={sevClass} />
                        <span className={`text-[10.5px] uppercase tracking-wide truncate ${sevClass}`}>
                          {ticket.severity}
                        </span>
                      </div>

                      {/* Date */}
                      <span className="hidden lg:block text-[11px] font-medium text-[rgba(0,0,0,0.42)] whitespace-nowrap">
                        {new Date(ticket.createdAt).toLocaleDateString('en-US', {
                          month: 'short',
                          day: 'numeric',
                        })}
                      </span>

                      {/* Status — solid pill, the primary anchor */}
                      <span
                        className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[10px] font-semibold uppercase tracking-wider whitespace-nowrap w-fit ${getStatusPillClass(ticket.status)}`}
                      >
                        <span className={`w-1.5 h-1.5 rounded-full bg-white ${isOpen ? 'animate-pulse' : ''}`} />
                        {formatStatus(ticket.status)}
                      </span>

                      <Icon
                        icon="mdi:chevron-right"
                        width={16}
                        className="text-[rgba(0,0,0,0.18)] group-hover:text-[rgba(0,0,0,0.7)] group-hover:translate-x-0.5 transition-all"
                      />
                    </div>
                  );
                })}
              </div>
            )}

            {/* Pagination */}
            <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5 bg-[#f9fafb] border-t border-[#e5e7eb] rounded-b-xl">
              <div className="flex items-center gap-3">
                <span className="text-[11.5px] font-medium text-[rgba(0,0,0,0.5)]">
                  Showing {rangeStart}–{rangeEnd} of {filteredTickets.length}
                  {hasActiveFilters && tickets.length !== filteredTickets.length && (
                    <span className="text-[rgba(0,0,0,0.35)]"> (filtered from {tickets.length})</span>
                  )}
                </span>
                {hasActiveFilters && (
                  <button
                    onClick={clearFilters}
                    className="text-[11.5px] font-medium text-[rgba(0,0,0,0.5)] underline underline-offset-2 hover:text-[rgba(0,0,0,0.88)] transition-colors"
                  >
                    Clear
                  </button>
                )}
              </div>

              <div className="flex items-center gap-3">
                <div className="relative">
                  <select
                    value={pageSize}
                    onChange={(e) => setPageSize(Number(e.target.value))}
                    aria-label="Rows per page"
                    className="h-7 pl-2 pr-6 rounded-md border border-[#e5e7eb] bg-white text-[11.5px] font-medium text-[rgba(0,0,0,0.6)] appearance-none focus:outline-none focus:border-[rgba(0,0,0,0.4)] cursor-pointer"
                  >
                    {PAGE_SIZE_OPTIONS.map((n) => (
                      <option key={n} value={n}>{n} / page</option>
                    ))}
                  </select>
                  <Icon icon="mdi:chevron-down" width={12} className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)] pointer-events-none" />
                </div>

                <div className="flex items-center gap-1">
                  <button
                    onClick={() => setPage(Math.max(1, currentPage - 1))}
                    disabled={currentPage <= 1}
                    aria-label="Previous page"
                    className="w-7 h-7 flex items-center justify-center rounded-md border border-[#e5e7eb] bg-white text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] disabled:opacity-40 disabled:hover:border-[#e5e7eb] transition-colors"
                  >
                    <Icon icon="mdi:chevron-left" width={15} />
                  </button>
                  <span className="text-[11.5px] font-medium text-[rgba(0,0,0,0.55)] px-1 tabular-nums">
                    {currentPage} / {totalPages}
                  </span>
                  <button
                    onClick={() => setPage(Math.min(totalPages, currentPage + 1))}
                    disabled={currentPage >= totalPages}
                    aria-label="Next page"
                    className="w-7 h-7 flex items-center justify-center rounded-md border border-[#e5e7eb] bg-white text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] disabled:opacity-40 disabled:hover:border-[#e5e7eb] transition-colors"
                  >
                    <Icon icon="mdi:chevron-right" width={15} />
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* No Accounts */}
        {showNoAccounts && (
          <div className="flex flex-col items-center justify-center py-16">
            <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[#e5e7eb] rounded-xl flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:power-plug-outline" width={28} className="text-[rgba(0,0,0,0.45)]" />
            </div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Accounts Connected</h3>
            <p className="text-[13px] text-[rgba(0,0,0,0.4)] mb-6 text-center max-w-xs">
              You don't have any cloud accounts linked yet. Add an account to start creating billing support tickets.
            </p>
            <a
              href="/account-manager"
              className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
            >
              <Icon icon="mdi:plus" width={14} />
              Add Account
            </a>
          </div>
        )}

        {/* Empty State */}
        {showEmpty && (
          <div className="flex flex-col items-center justify-center py-16">
            <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[#e5e7eb] rounded-xl flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:ticket-outline" width={28} className="text-[rgba(0,0,0,0.45)]" />
            </div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Tickets</h3>
            <p className="text-[13px] text-[rgba(0,0,0,0.4)] mb-6 text-center max-w-xs">
              No billing or account support tickets found. Create a new case to get help.
            </p>
            <button
              onClick={() => openCreateModalRef.current()}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
            >
              <Icon icon="mdi:plus" width={14} />
              Create New Case
            </button>
          </div>
        )}

      </div>

      {/* Create Ticket Modal */}
      <DialogRoot isOpen={showModal} onClose={closeModal} maxWidth="max-w-lg">
        <DialogPanel
          title={
            <div className="flex items-center gap-3">
              <Icon icon="mdi:plus" width={18} className="text-indigo-600" />
              <div>
                <div className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Create Support Case</div>
                <p className="text-[rgba(0,0,0,0.4)] text-[13px] mt-0.5">Submit a new request for billing or account support</p>
              </div>
            </div>
          }
          onClose={closeModal}
          footer={
            <>
              <button
                type="button"
                onClick={closeModal}
                disabled={createMutation.isPending}
                className="px-4 py-2 rounded-lg border border-[rgba(0,0,0,0.12)] text-[13px] font-medium text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.04)]"
              >
                Cancel
              </button>
              <button
                type="submit"
                form="billops-ticket-form"
                disabled={createMutation.isPending}
                className="px-4 py-2 rounded-lg bg-[rgba(0,0,0,0.88)] text-white text-[13px] font-medium hover:bg-black disabled:opacity-50 flex items-center gap-2"
              >
                {createMutation.isPending ? (
                  <><Icon icon="mdi:loading" width={20} className="animate-spin text-[rgba(0,0,0,0.3)]" />Submitting...</>
                ) : (
                  <><Icon icon="mdi:check" width={14} />Submit Case</>
                )}
              </button>
            </>
          }
        >
          <form id="billops-ticket-form" onSubmit={submitTicket}>
            <div className="space-y-5">

              {/* Category */}
              <div>
                <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.08em] mb-1">Category</label>
                <div className="relative">
                  <select
                    value={form.category}
                    disabled
                    className="w-full px-3 py-2 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 appearance-none cursor-not-allowed"
                  >
                    <option>Account and Billing</option>
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-3 pointer-events-none text-[rgba(0,0,0,0.4)]">
                    <Icon icon="mdi:lock-outline" width={14} />
                  </div>
                </div>
              </div>

              {/* Service */}
              <div>
                <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.08em] mb-1">
                  Service <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <select
                    value={form.service}
                    onChange={(e) => setForm((prev) => ({ ...prev, service: e.target.value }))}
                    className="w-full px-3 py-2 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 appearance-none"
                    required
                  >
                    {SERVICE_OPTIONS['Account and Billing'].map((s) => (
                      <option key={s} value={s}>{s}</option>
                    ))}
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-3 pointer-events-none text-[rgba(0,0,0,0.4)]">
                    <Icon icon="mdi:chevron-down" width={16} />
                  </div>
                </div>
              </div>

              {/* Severity */}
              <div>
                <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.08em] mb-1">
                  Severity <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <select
                    value={form.severity}
                    onChange={(e) => setForm((prev) => ({ ...prev, severity: e.target.value as TicketSeverity }))}
                    className="w-full px-3 py-2 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 appearance-none"
                    required
                  >
                    <option disabled value="">Select Severity</option>
                    {SEVERITY_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label} — {option.hint}
                      </option>
                    ))}
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-3 pointer-events-none text-[rgba(0,0,0,0.4)]">
                    <Icon icon="mdi:chevron-down" width={16} />
                  </div>
                </div>
              </div>

              {/* Subject */}
              <div>
                <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.08em] mb-1">
                  Subject <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={form.subject}
                  onChange={(e) => setForm((prev) => ({ ...prev, subject: e.target.value }))}
                  maxLength={250}
                  className="w-full px-3 py-2 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 placeholder:text-[rgba(0,0,0,0.3)]"
                  placeholder="Brief summary of your billing issue"
                  required
                />
              </div>

              {/* Description */}
              <div>
                <label className="block text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.08em] mb-1">
                  Description <span className="text-red-500">*</span>
                </label>
                <textarea
                  value={form.description}
                  onChange={(e) => setForm((prev) => ({ ...prev, description: e.target.value }))}
                  maxLength={8000}
                  rows={4}
                  className="w-full px-3 py-2 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 placeholder:text-[rgba(0,0,0,0.3)] resize-none"
                  placeholder="Provide detailed information about your request..."
                  required
                />
              </div>

            </div>
          </form>
        </DialogPanel>
      </DialogRoot>
    </main>
  );
};

export default BillOpsTicketsPage;
