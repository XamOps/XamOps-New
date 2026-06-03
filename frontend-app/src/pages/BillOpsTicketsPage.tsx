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
import { AnimatedItem } from '../components/Shared/AnimatedList';
import { DialogRoot, DialogPanel } from '../components/ui';
import {
  BillOpsTicket,
  CreateTicketFormData,
  TicketSeverity,
  SERVICE_OPTIONS,
} from '../types/billopsTickets';

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

function getStatusBadgeClass(status: string): string {
  if (status === 'OPEN') return 'bg-emerald-50 text-emerald-700 border border-emerald-200';
  if (status === 'IN_PROGRESS') return 'bg-blue-50 text-blue-700 border border-blue-200';
  if (status === 'CLOSED') return 'bg-gray-100 text-gray-500 border border-gray-200';
  return 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)] border border-[rgba(0,0,0,0.12)]';
}

function getSeverityMeta(severity: string): { dotClass: string; textClass: string } {
  const s = severity.toLowerCase();
  if (s === 'critical' || s === 'high' || s === 'urgent') {
    return { dotClass: 'bg-rose-500', textClass: 'text-rose-600 font-bold' };
  }
  if (s === 'medium' || s === 'normal') {
    return { dotClass: 'bg-amber-500', textClass: 'text-amber-600 font-semibold' };
  }
  return { dotClass: 'bg-blue-500', textClass: 'text-blue-600 font-medium' };
}

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
    <main className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen">
      <div className="space-y-2">

        {/* Skeleton — only on true first-ever load */}
        {showSkeleton && (
          <div className="space-y-2">
            {[1, 2, 3, 4].map((i) => (
              <div
                key={i}
                className="flex items-center gap-4 px-5 py-4 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl animate-pulse"
              >
                <div className="flex-1 space-y-2">
                  <div className="h-3 w-[15%] bg-[rgba(0,0,0,0.06)] rounded" />
                  <div className="h-4 w-[55%] bg-[rgba(0,0,0,0.06)] rounded" />
                </div>
                <div className="h-6 w-20 bg-[rgba(0,0,0,0.06)] rounded" />
                <div className="h-6 w-[70px] bg-[rgba(0,0,0,0.06)] rounded" />
                <div className="h-6 w-[90px] bg-[rgba(0,0,0,0.06)] rounded" />
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
          <div className="space-y-2">
            {tickets.map((ticket, index) => {
              const { dotClass, textClass } = getSeverityMeta(ticket.severity);
              const accountName = ticket.accountId ? accountNameMap[ticket.accountId] : null;

              return (
                <AnimatedItem key={ticket.id} index={index} delay={index * 0.03}>
                <div
                  onClick={() => navigate(`/billops-ticket-detail?id=${ticket.id}`)}
                  className={`flex items-center gap-4 px-5 py-4 bg-white border rounded-xl hover:bg-[rgba(0,0,0,0.02)] hover:shadow-sm transition-all duration-200 cursor-pointer group ${
                    ticket.hasUnreadAdminReply
                      ? 'border border-indigo-200 bg-indigo-50/30'
                      : 'border-[rgba(0,0,0,0.08)] hover:border-indigo-300'
                  }`}
                >
                  {/* ID + Subject */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold text-[rgba(0,0,0,0.35)] uppercase tracking-widest">
                        Billing #{ticket.id}
                      </span>
                      {ticket.hasUnreadAdminReply && (
                        <span className="flex items-center gap-1 px-2 py-0.5 bg-indigo-600 text-white text-[9px] font-black rounded-full uppercase tracking-widest animate-pulse">
                          <span className="w-1.5 h-1.5 rounded-full bg-white" />
                          New Reply
                        </span>
                      )}
                    </div>
                    <p className="text-sm font-semibold text-[rgba(0,0,0,0.88)] group-hover:text-indigo-600 transition-colors truncate leading-snug">
                      {ticket.subject}
                    </p>
                  </div>

                  {/* Account */}
                  {accountName && (
                    <div className="hidden lg:flex flex-col items-end min-w-0 max-w-[160px]">
                      <span className="text-xs font-semibold text-[rgba(0,0,0,0.88)] truncate w-full text-right">
                        {accountName}
                      </span>
                      <span className="text-[10px] text-[rgba(0,0,0,0.5)] font-mono truncate w-full text-right">
                        {ticket.accountId}
                      </span>
                    </div>
                  )}

                  {/* Service */}
                  <span className="hidden md:flex items-center gap-1.5 px-2.5 py-1 bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)] rounded text-[10px] font-bold whitespace-nowrap border border-[rgba(0,0,0,0.08)]">
                    <Icon icon="mdi:file-document-outline" width={10} className="text-[rgba(0,0,0,0.35)]" />
                    {ticket.service}
                  </span>

                  {/* Severity */}
                  <div className="hidden lg:flex items-center gap-1.5">
                    <div className={`w-2 h-2 rounded-full ${dotClass}`} />
                    <span className={`text-[10px] uppercase tracking-wider ${textClass}`}>
                      {ticket.severity}
                    </span>
                  </div>

                  {/* Date */}
                  <span className="hidden xl:flex items-center gap-1 text-[10px] font-medium text-[rgba(0,0,0,0.35)] whitespace-nowrap">
                    <Icon icon="mdi:calendar-outline" width={12} />
                    {new Date(ticket.createdAt).toLocaleDateString('en-US', {
                      month: 'short',
                      day: 'numeric',
                    })}
                  </span>

                  {/* Status */}
                  <span
                    className={`flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider whitespace-nowrap ${getStatusBadgeClass(ticket.status)}`}
                  >
                    <span
                      className={`w-1.5 h-1.5 rounded-full ${ticket.status === 'OPEN' ? 'bg-emerald-500 animate-pulse' : ticket.status === 'IN_PROGRESS' ? 'bg-blue-500' : 'bg-gray-400'}`}
                    />
                    {ticket.status.replace('_', ' ')}
                  </span>

                  <Icon icon="mdi:chevron-right" width={16} className="text-[rgba(0,0,0,0.2)]" />
                </div>
                </AnimatedItem>
              );
            })}
          </div>
        )}

        {/* No Accounts */}
        {showNoAccounts && (
          <div className="flex flex-col items-center justify-center py-16">
            <div className="w-14 h-14 bg-amber-50 border border-amber-100 rounded-xl flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:power-plug-outline" width={28} className="text-amber-400" />
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
            <div className="w-14 h-14 bg-indigo-50 border border-indigo-100 rounded-xl flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:ticket-outline" width={28} className="text-indigo-400" />
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
                    <option value="Low">Low</option>
                    <option value="Medium">Medium</option>
                    <option value="High">High</option>
                    <option value="Critical">Critical</option>
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
