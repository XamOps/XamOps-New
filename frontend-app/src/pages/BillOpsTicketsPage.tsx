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
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import Skeleton from '../components/Shared/Skeleton';
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

  // Sort newest-first
  data.sort((a, b) => b.id - a.id);

  return data;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function getStatusBadgeClass(status: string): string {
  if (status === 'OPEN') return 'bg-emerald-500 text-white';
  if (status === 'IN_PROGRESS') return 'bg-amber-500 text-white';
  return 'bg-slate-400 text-white';
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
      <div className="flex items-center gap-6">
        <div className="hidden lg:flex items-center gap-3 pr-6 border-r border-slate-200/50">
          <div className="w-9 h-9 flex items-center justify-center rounded-xl bg-purple-50 text-purple-600 shadow-sm border border-purple-100/50">
            <i className="fas fa-file-invoice-dollar" />
          </div>
          <div>
            <h1 className="text-sm font-bold text-slate-900 leading-none">Billing Tickets</h1>
            <p className="text-[10px] text-slate-500 font-medium mt-1">Account & Billing Support</p>
          </div>
        </div>

        {showTickets && (
          <div className="hidden xl:flex items-center gap-4">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
              <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Open</span>
              <span className="text-xs font-bold text-slate-700">{openCount}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-amber-500" />
              <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">In Progress</span>
              <span className="text-xs font-bold text-slate-700">{inProgressCount}</span>
            </div>
          </div>
        )}

        <div className="flex items-center gap-3">
          <button
            onClick={() => refetch()}
            disabled={isFetching}
            className="w-10 h-10 flex items-center justify-center text-slate-500 hover:text-indigo-600 hover:bg-slate-100 rounded-xl transition-all border border-transparent hover:border-slate-200/50 disabled:opacity-50"
            title="Refresh Tickets"
          >
            <i className={`fas fa-sync-alt ${isFetching ? 'animate-spin' : ''}`} />
          </button>

          <button
            onClick={() => openCreateModalRef.current()}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 transition-all hover:-translate-y-0.5 active:scale-95"
          >
            <i className="fas fa-plus" />
            <span>Create New Case</span>
          </button>
        </div>
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
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      <main className="flex-1 overflow-y-auto custom-scrollbar p-8">
        <div className="space-y-2">

          {/* Skeleton — only on true first-ever load */}
          {showSkeleton && (
            <div className="space-y-2">
              {[1, 2, 3, 4].map((i) => (
                <div
                  key={i}
                  className="flex items-center gap-4 px-5 py-4 bg-white border border-slate-200 rounded-2xl"
                >
                  <div className="flex-1 space-y-2">
                    <Skeleton variant="text" height={12} width="15%" />
                    <Skeleton variant="text" height={16} width="55%" />
                  </div>
                  <Skeleton variant="text" height={24} width={80} />
                  <Skeleton variant="text" height={24} width={70} />
                  <Skeleton variant="text" height={24} width={90} />
                </div>
              ))}
            </div>
          )}

          {/* Error */}
          {showError && (
            <div className="flex flex-col items-center justify-center py-20 text-center">
              <i className="fas fa-exclamation-circle text-4xl mb-4 text-red-400" />
              <p className="text-red-600 font-medium mb-4">Could not load billing tickets.</p>
              <button
                onClick={() => refetch()}
                className="inline-flex items-center gap-2 px-5 py-2.5 bg-indigo-600 text-white text-sm font-bold rounded-xl shadow hover:bg-indigo-700 transition-all"
              >
                <i className="fas fa-sync-alt" />
                Try Again
              </button>
            </div>
          )}

          {/* Ticket list */}
          {showTickets && (
            <div className="space-y-2">
              {tickets.map((ticket) => {
                const { dotClass, textClass } = getSeverityMeta(ticket.severity);
                const accountName = ticket.accountId ? accountNameMap[ticket.accountId] : null;

                return (
                  <div
                    key={ticket.id}
                    onClick={() => navigate(`/billops-ticket-detail?id=${ticket.id}`)}
                    className={`flex items-center gap-4 px-5 py-4 bg-white border rounded-2xl hover:shadow-sm transition-all duration-200 cursor-pointer group ${
                      ticket.hasUnreadAdminReply
                        ? 'border-indigo-300 ring-1 ring-indigo-200/50'
                        : 'border-slate-200 hover:border-indigo-300'
                    }`}
                  >
                    {/* ID + Subject */}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">
                          Billing #{ticket.id}
                        </span>
                        {ticket.hasUnreadAdminReply && (
                          <span className="flex items-center gap-1 px-2 py-0.5 bg-indigo-600 text-white text-[9px] font-black rounded-full uppercase tracking-widest animate-pulse">
                            <span className="w-1.5 h-1.5 rounded-full bg-white" />
                            New Reply
                          </span>
                        )}
                      </div>
                      <p className="text-sm font-semibold text-slate-800 group-hover:text-indigo-600 transition-colors truncate leading-snug">
                        {ticket.subject}
                      </p>
                    </div>

                    {/* Account */}
                    {accountName && (
                      <div className="hidden lg:flex flex-col items-end min-w-0 max-w-[160px]">
                        <span className="text-xs font-semibold text-slate-700 truncate w-full text-right">
                          {accountName}
                        </span>
                        <span className="text-[10px] text-slate-400 font-mono truncate w-full text-right">
                          {ticket.accountId}
                        </span>
                      </div>
                    )}

                    {/* Service */}
                    <span className="hidden md:flex items-center gap-1.5 px-2.5 py-1 bg-purple-50 text-purple-600 rounded-lg text-[10px] font-bold whitespace-nowrap border border-purple-100/50">
                      <i className="fas fa-file-invoice text-[8px] text-purple-400" />
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
                    <span className="hidden xl:block text-[10px] font-medium text-slate-400 whitespace-nowrap">
                      <i className="far fa-calendar-alt mr-1" />
                      {new Date(ticket.createdAt).toLocaleDateString('en-US', {
                        month: 'short',
                        day: 'numeric',
                      })}
                    </span>

                    {/* Status */}
                    <span
                      className={`flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider shadow-sm whitespace-nowrap ${getStatusBadgeClass(ticket.status)}`}
                    >
                      <span
                        className={`w-1.5 h-1.5 rounded-full bg-white ${ticket.status === 'OPEN' ? 'animate-pulse' : ''}`}
                      />
                      {ticket.status.replace('_', ' ')}
                    </span>

                    <i className="fas fa-chevron-right text-slate-300 text-xs group-hover:text-indigo-400 transition-colors" />
                  </div>
                );
              })}
            </div>
          )}

          {/* No Accounts */}
          {showNoAccounts && (
            <div className="max-w-md mx-auto text-center py-20 bg-white/50 backdrop-blur-xl border border-white rounded-[3rem] shadow-xl shadow-indigo-500/5">
              <div className="w-24 h-24 bg-amber-50 rounded-full flex items-center justify-center mx-auto mb-8 shadow-inner border border-amber-100/50">
                <i className="fas fa-plug text-amber-300 text-4xl" />
              </div>
              <h3 className="text-2xl font-black text-slate-800 mb-3 tracking-tight">No Accounts Connected</h3>
              <p className="text-slate-500 text-sm px-10 leading-relaxed mb-10">
                You don't have any cloud accounts linked yet. Add an account to start creating billing support tickets.
              </p>
              <a
                href="/account-manager"
                className="inline-flex items-center gap-3 px-8 py-4 bg-amber-500 text-white rounded-2xl text-sm font-bold shadow-xl shadow-amber-500/20 hover:bg-amber-600 transition-all hover:-translate-y-1 active:scale-95"
              >
                <i className="fas fa-plus" />
                Add Account
              </a>
            </div>
          )}

          {/* Empty State */}
          {showEmpty && (
            <div className="max-w-md mx-auto text-center py-20 bg-white/50 backdrop-blur-xl border border-white rounded-[3rem] shadow-xl shadow-indigo-500/5">
              <div className="w-24 h-24 bg-purple-50 rounded-full flex items-center justify-center mx-auto mb-8 shadow-inner border border-purple-100/50">
                <i className="fas fa-file-invoice-dollar text-purple-300 text-4xl" />
              </div>
              <h3 className="text-2xl font-black text-slate-800 mb-3 tracking-tight">No Billing Tickets</h3>
              <p className="text-slate-500 text-sm px-10 leading-relaxed mb-10">
                No billing or account support tickets found. Create a new case to get help.
              </p>
              <button
                onClick={() => openCreateModalRef.current()}
                className="inline-flex items-center gap-3 px-8 py-4 bg-indigo-600 text-white rounded-2xl text-sm font-bold shadow-xl shadow-indigo-500/20 hover:bg-indigo-700 transition-all hover:-translate-y-1 active:scale-95"
              >
                <i className="fas fa-plus" />
                Create New Case
              </button>
            </div>
          )}

        </div>
      </main>

      {/* Create Ticket Modal */}
      <DialogRoot isOpen={showModal} onClose={closeModal} maxWidth="max-w-lg">
        <DialogPanel
          title={
            <div className="flex items-center gap-3">
              <i className="fas fa-plus-circle text-indigo-600" />
              <div>
                <div className="text-xl font-bold text-gray-900">Create Support Case</div>
                <p className="text-gray-500 text-sm mt-0.5">Submit a new request for billing or account support</p>
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
                className="px-5 py-2.5 text-sm font-medium text-gray-700 bg-white border border-gray-200 rounded-xl hover:bg-gray-50 hover:text-gray-900 transition-colors shadow-sm"
              >
                Cancel
              </button>
              <button
                type="submit"
                form="billops-ticket-form"
                disabled={createMutation.isPending}
                className="px-5 py-2.5 text-sm font-medium text-white bg-indigo-600 rounded-xl hover:bg-indigo-700 disabled:opacity-70 disabled:cursor-not-allowed transition-all shadow-sm flex items-center gap-2"
              >
                {createMutation.isPending ? (
                  <><i className="fas fa-circle-notch fa-spin" />Submitting...</>
                ) : (
                  <><i className="fas fa-check" />Submit Case</>
                )}
              </button>
            </>
          }
        >
          <form id="billops-ticket-form" onSubmit={submitTicket}>
            <div className="space-y-5">

              {/* Category */}
              <div>
                <label className="block text-sm font-semibold text-gray-700 mb-2">Category</label>
                <div className="relative">
                  <select
                    value={form.category}
                    disabled
                    className="w-full rounded-xl border-gray-200 bg-gray-50 text-gray-500 py-2.5 pl-4 pr-10 shadow-sm appearance-none cursor-not-allowed"
                  >
                    <option>Account and Billing</option>
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-gray-400">
                    <i className="fas fa-lock text-xs" />
                  </div>
                </div>
              </div>

              {/* Service */}
              <div>
                <label className="block text-sm font-semibold text-gray-700 mb-2">
                  Service <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <select
                    value={form.service}
                    onChange={(e) => setForm((prev) => ({ ...prev, service: e.target.value }))}
                    className="w-full rounded-xl border-gray-200 py-2.5 pl-4 pr-10 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 appearance-none bg-white hover:bg-gray-50 transition-colors"
                    required
                  >
                    {SERVICE_OPTIONS['Account and Billing'].map((s) => (
                      <option key={s} value={s}>{s}</option>
                    ))}
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-gray-500">
                    <i className="fas fa-chevron-down text-xs" />
                  </div>
                </div>
              </div>

              {/* Severity */}
              <div>
                <label className="block text-sm font-semibold text-gray-700 mb-2">
                  Severity <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <select
                    value={form.severity}
                    onChange={(e) => setForm((prev) => ({ ...prev, severity: e.target.value as TicketSeverity }))}
                    className="w-full rounded-xl border-gray-200 py-2.5 pl-4 pr-10 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 appearance-none bg-white hover:bg-gray-50 transition-colors"
                    required
                  >
                    <option disabled value="">Select Severity</option>
                    <option value="Low">Low</option>
                    <option value="Medium">Medium</option>
                    <option value="High">High</option>
                    <option value="Critical">Critical</option>
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-gray-500">
                    <i className="fas fa-chevron-down text-xs" />
                  </div>
                </div>
              </div>

              {/* Subject */}
              <div>
                <label className="block text-sm font-semibold text-gray-700 mb-2">
                  Subject <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={form.subject}
                  onChange={(e) => setForm((prev) => ({ ...prev, subject: e.target.value }))}
                  maxLength={250}
                  className="w-full rounded-xl border-gray-200 py-2.5 px-4 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 placeholder-gray-400"
                  placeholder="Brief summary of your billing issue"
                  required
                />
              </div>

              {/* Description */}
              <div>
                <label className="block text-sm font-semibold text-gray-700 mb-2">
                  Description <span className="text-red-500">*</span>
                </label>
                <textarea
                  value={form.description}
                  onChange={(e) => setForm((prev) => ({ ...prev, description: e.target.value }))}
                  maxLength={8000}
                  rows={4}
                  className="w-full rounded-xl border-gray-200 py-2.5 px-4 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 placeholder-gray-400 resize-none"
                  placeholder="Provide detailed information about your request..."
                  required
                />
              </div>

            </div>
          </form>
        </DialogPanel>
      </DialogRoot>
    </div>
  );
};

export default BillOpsTicketsPage;
