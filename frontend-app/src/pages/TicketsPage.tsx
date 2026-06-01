/**
 * Support Tickets Page
 *
 * Caching strategy — 4 layers:
 * 1. React Query in-memory cache (staleTime 60s) — instant render within session
 * 2. localStorage persistence via @tanstack/react-query-persist-client — survives reload
 * 3. Stale-while-revalidate — cached data shown immediately, fresh fetch in background
 * 4. 30-second polling with visibility-aware pause/resume
 *
 * Real-time: WebSocket STOMP subscription on /topic/tickets invalidates the query
 * so all connected clients see new/updated tickets without polling delay.
 *
 * Optimistic UI: new ticket appears instantly from POST response; background
 * reconcile follows.
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Icon } from '@iconify/react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../contexts/AuthContext';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import Skeleton from '../components/Shared/Skeleton';
import { AnimatedItem } from '../components/Shared/AnimatedList';
import { DialogRoot, DialogPanel } from '../components/ui';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface Ticket {
  id: number;
  subject: string;
  description: string;
  category: string;
  service: string;
  severity: string;
  status: string;
  accountId?: string;
  region?: string;
  createdAt: string;
  creatorId?: number;
  _source?: string;
}

interface TicketForm {
  category: string;
  service: string;
  severity: string;
  subject: string;
  description: string;
  accountId: string;
  region: string;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const SERVICE_OPTIONS: Record<string, string[]> = {
  'Account and Billing': [
    'Billing',
    'Pricing',
    'Reserved Instances',
    'Detailed Billing Report (DBR)',
  ],
  Technical: [
    'Compute / VMs',
    'Storage',
    'Networking',
    'Database',
    'Security & IAM',
    'Monitoring & Logs',
    'Serverless',
    'Other',
  ],
};

const SEVERITY_OPTIONS = ['Info', 'Low', 'Normal', 'High', 'Urgent'];
const POLL_INTERVAL_MS = 30_000;

const DEFAULT_FORM: Omit<TicketForm, 'accountId'> = {
  category: 'Technical',
  service: 'Compute / VMs',
  severity: '',
  subject: '',
  description: '',
  region: '',
};

// ---------------------------------------------------------------------------
// React Query key factory
// ---------------------------------------------------------------------------

export const ticketKeys = {
  all: (accountIds: string[]) =>
    ['tickets', [...accountIds].sort().join(',')] as const,
};

// ---------------------------------------------------------------------------
// Fetcher — merges xamops + billops, deduplicates, filters by account, sorts
// ---------------------------------------------------------------------------

async function fetchTickets(
  accountIds: string[],
  signal?: AbortSignal
): Promise<Ticket[]> {
  const [xamopsRes, billopsRes] = await Promise.allSettled([
    fetch('/api/xamops/tickets/category/Technical', {
      credentials: 'include',
      signal,
    }),
    fetch('/api/billops/tickets/category/Technical', {
      credentials: 'include',
      signal,
    }),
  ]);

  let xamopsData: Ticket[] = [];
  if (xamopsRes.status === 'fulfilled' && xamopsRes.value.ok) {
    try {
      xamopsData = await xamopsRes.value.json();
    } catch { /* ignore */ }
    if (!Array.isArray(xamopsData)) xamopsData = [];
  }

  let billopsData: Ticket[] = [];
  if (billopsRes.status === 'fulfilled' && billopsRes.value.ok) {
    try {
      billopsData = await billopsRes.value.json();
    } catch { /* ignore */ }
    if (!Array.isArray(billopsData)) billopsData = [];
    billopsData = billopsData.map((t) => ({ ...t, _source: 'billops' }));
  }

  // Deduplicate by id (xamops wins on collision)
  const seen = new Set<number>();
  let data = [...xamopsData, ...billopsData].filter((t) => {
    if (seen.has(t.id)) return false;
    seen.add(t.id);
    return true;
  });

  // Filter by selected accounts
  if (accountIds.length > 0) {
    data = data.filter((t) => t.accountId && accountIds.includes(t.accountId));
  }

  // Sort newest-first
  data.sort((a, b) => {
    const aT = a.createdAt ? new Date(a.createdAt).getTime() : 0;
    const bT = b.createdAt ? new Date(b.createdAt).getTime() : 0;
    return bT - aT;
  });

  return data;
}

// ---------------------------------------------------------------------------
// Helpers (pure, defined outside component)
// ---------------------------------------------------------------------------

function getStatusBadgeClass(status: string): string {
  switch (status.toLowerCase()) {
    case 'open': return 'bg-emerald-500 text-white';
    case 'in_progress': return 'bg-amber-500 text-white';
    case 'closed': return 'bg-[rgba(0,0,0,0.3)] text-white';
    default: return 'bg-indigo-500 text-white';
  }
}

function getSeverityMeta(severity: string): { icon: string; className: string } {
  const s = severity.toLowerCase();
  if (s === 'urgent' || s === 'high' || s === 'critical') {
    return { icon: 'mdi:alert-outline', className: 'text-red-600 font-bold' };
  }
  if (s === 'normal' || s === 'medium') {
    return { icon: 'mdi:alert-circle-outline', className: 'text-orange-500 font-semibold' };
  }
  return { icon: 'mdi:information-outline', className: 'text-blue-500 font-medium' };
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

const TicketsPage: React.FC = () => {
  const { userProfile } = useAuth();
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

  const queryKey = ticketKeys.all(selectedAccountIds);

  const {
    data: tickets = [],
    isFetching,
    isLoading: isFirstLoad,
    isError,
    refetch,
  } = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchTickets(selectedAccountIds, signal),
    enabled: !accountsLoading && hasAccounts,
    staleTime: 60 * 1000,            // treat data as fresh for 60s
    refetchInterval: POLL_INTERVAL_MS, // background poll every 30s
    refetchIntervalInBackground: false, // pause poll when tab hidden
    placeholderData: (prev) => prev,  // keep previous data while refetching (no flash)
  });

  // ---------------------------------------------------------------------------
  // WebSocket — real-time invalidation
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
        stompClient.debug = null; // silence STOMP debug logs

        stompClient.connect({}, () => {
          stompClient.subscribe('/topic/tickets', () => {
            // Mark stale only — do NOT force an immediate refetch.
            // An immediate refetch here hits the still-warm Redis cache and wipes any
            // optimistic data set by setQueryData. Marking stale means React Query will
            // refetch on the next natural opportunity (30 s poll or tab focus), by which
            // time @CacheEvict has completed and the DB has fresh data.
            qc.invalidateQueries({ queryKey: ['tickets'], refetchType: 'none' });
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
  // Listen for ticket-updated event from TicketDetailPage
  // ---------------------------------------------------------------------------

  useEffect(() => {
    const handler = () => qc.invalidateQueries({ queryKey: ['tickets'] });
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
  const [form, setForm] = useState<TicketForm>({
    ...DEFAULT_FORM,
    accountId: selectedAccountIds[0] ?? '',
  });

  // Keep accountId in sync with selected account
  useEffect(() => {
    setForm((prev) => ({ ...prev, accountId: selectedAccountIds[0] ?? '' }));
  }, [selectedAccountIds]);

  // Stable ref so topbar button never captures stale openCreateModal
  const openCreateModalRef = useRef<() => void>(() => { });

  const openCreateModal = useCallback(() => {
    setForm({ ...DEFAULT_FORM, accountId: selectedAccountIds[0] ?? '' });
    setShowModal(true);
  }, [selectedAccountIds]);

  useEffect(() => { openCreateModalRef.current = openCreateModal; }, [openCreateModal]);

  const closeModal = useCallback(() => setShowModal(false), []);

  const handleFormChange = (field: keyof TicketForm, value: string) => {
    setForm((prev) => {
      const updated = { ...prev, [field]: value };
      if (field === 'category') updated.service = SERVICE_OPTIONS[value]?.[0] ?? '';
      return updated;
    });
  };

  // ---------------------------------------------------------------------------
  // Create ticket mutation — optimistic update
  // ---------------------------------------------------------------------------

  const createMutation = useMutation({
    mutationFn: async (payload: TicketForm & { creatorId: number }) => {
      const res = await fetch('/api/xamops/tickets', {
        credentials: 'include',
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!res.ok) throw new Error('Failed to submit ticket');
      return res.json() as Promise<Ticket>;
    },

    onMutate: async () => {
      // Close modal immediately — don't wait for network
      setShowModal(false);
      // Cancel any in-flight refetch so it doesn't overwrite our optimistic update
      await qc.cancelQueries({ queryKey });
    },

    onSuccess: (newTicket) => {
      // Prepend the real ticket from server response — visible instantly, no flash or spinner.
      // Reconciliation is handled by the WebSocket (2 s delayed) and the 30 s poll.
      qc.setQueryData<Ticket[]>(queryKey, (prev = []) => [
        newTicket,
        ...prev.filter((t) => t.id !== newTicket.id),
      ]);
    },

    onError: (err) => {
      console.error('Error creating ticket:', err);
      alert('There was an error submitting your case. Please try again.');
    },
  });

  const submitTicket = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.service || !form.subject.trim() || !form.description.trim() || !form.severity) {
      alert('Please fill in all required fields.');
      return;
    }
    if (!userProfile?.id) {
      alert('Could not identify current user. Please refresh and log in.');
      return;
    }
    createMutation.mutate({ ...form, creatorId: userProfile.id });
  };

  // ---------------------------------------------------------------------------
  // Derived render flags
  // ---------------------------------------------------------------------------

  // isFirstLoad = true only when there's no cached data at all (truly first ever visit)
  // isFetching = true on every background refetch/poll (we show subtle indicator only)
  const showSkeleton = (accountsLoading || isFirstLoad) && tickets.length === 0;
  const showNoAccounts = !accountsLoading && !hasAccounts;
  const showEmpty = !isFirstLoad && !accountsLoading && hasAccounts && tickets.length === 0 && !isError;
  const showError = isError && tickets.length === 0;
  const showTickets = tickets.length > 0;

  // ---------------------------------------------------------------------------
  // Topbar
  // ---------------------------------------------------------------------------

  const openCount = tickets.filter((t) => t.status.toLowerCase() === 'open').length;
  const inProgressCount = tickets.filter((t) => t.status.toLowerCase() === 'in_progress').length;

  useEffect(() => {
    const actionsNode = (
      <div className="flex items-center gap-4">
        {showTickets && (
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse" />
              <span className="text-[11px] font-medium text-[rgba(0,0,0,0.4)] uppercase tracking-wide">Open</span>
              <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)]">{openCount}</span>
            </div>
            <div className="flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-amber-400" />
              <span className="text-[11px] font-medium text-[rgba(0,0,0,0.4)] uppercase tracking-wide">In Progress</span>
              <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)]">{inProgressCount}</span>
            </div>
          </div>
        )}
        <button
          onClick={() => refetch()}
          disabled={isFetching}
          className="flex items-center gap-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] transition-all disabled:opacity-50"
          title="Refresh Tickets"
        >
          <Icon icon="mdi:refresh" width={14} className={isFetching ? 'animate-spin' : ''} />
          Refresh
        </button>
        <button
          onClick={() => openCreateModalRef.current()}
          className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-[rgba(0,0,0,0.75)] transition-all"
        >
          <Icon icon="mdi:plus" width={14} />
          Create Ticket
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

        {/* Skeleton — only on true first-ever load with no cached data */}
        {showSkeleton && (
          <div className="space-y-2">
            {[1, 2, 3, 4].map((i) => (
              <div
                key={i}
                className="flex items-center gap-4 px-5 py-4 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl"
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
          <div className="flex flex-col items-center justify-center py-16 text-center">
            <div className="w-14 h-14 bg-red-50 border border-red-100 rounded-xl flex items-center justify-center mb-4">
              <Icon icon="mdi:alert-circle-outline" width={28} className="text-red-500" />
            </div>
            <p className="text-[rgba(0,0,0,0.7)] font-medium mb-4">Could not load support tickets.</p>
            <button
              onClick={() => refetch()}
              className="flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white rounded-xl text-[12px] font-medium hover:bg-[rgba(0,0,0,0.75)] transition-all"
            >
              <Icon icon="mdi:refresh" width={14} />
              Try Again
            </button>
          </div>
        )}

        {/* Ticket list */}
        {showTickets && (
          <div className="space-y-2">
            {/* Subtle background-refresh indicator */}
            {isFetching && !isFirstLoad && (
              <div className="flex items-center gap-2 px-4 py-2 text-[10px] font-medium text-[rgba(0,0,0,0.35)] uppercase tracking-wider">
                <Icon icon="mdi:refresh" width={10} className="animate-spin" />
                Refreshing…
              </div>
            )}

            {tickets.map((ticket, index) => {
              const { icon, className: sevClass } = getSeverityMeta(ticket.severity);
              const accountName = ticket.accountId ? accountNameMap[ticket.accountId] : null;

              return (
                <AnimatedItem key={ticket.id} index={index} delay={index * 0.03}>
                <div
                  onClick={() => navigate(`/ticket-detail?id=${ticket.id}${ticket._source === 'billops' ? '&src=billops' : ''}`)}
                  className="flex items-center gap-4 px-5 py-4 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:border-indigo-200 hover:shadow-sm transition-all cursor-pointer group"
                >
                  {/* ID + Subject */}
                  <div className="flex-1 min-w-0">
                    <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest">
                      Case #{ticket.id}
                    </span>
                    <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] group-hover:text-indigo-600 transition-colors truncate">
                      {ticket.subject}
                    </p>
                  </div>

                  {/* Account */}
                  {accountName && (
                    <div className="hidden lg:flex flex-col items-end min-w-0 max-w-[160px]">
                      <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.7)] truncate w-full text-right">
                        {accountName}
                      </span>
                      <span className="text-[10px] text-[rgba(0,0,0,0.35)] font-mono truncate w-full text-right">
                        {ticket.accountId}
                      </span>
                    </div>
                  )}

                  {/* Service */}
                  <span className="hidden md:flex items-center gap-1.5 px-2.5 py-1 bg-indigo-50 text-indigo-600 rounded-lg text-[10px] font-bold whitespace-nowrap">
                    <Icon icon="mdi:cube-outline" width={8} className="text-indigo-400" />
                    {ticket.service}
                  </span>

                  {/* Severity */}
                  <div className="hidden lg:flex items-center gap-1.5">
                    <Icon icon={icon} width={12} className={sevClass} />
                    <span className={`text-[10px] font-bold uppercase tracking-wider ${sevClass}`}>
                      {ticket.severity}
                    </span>
                  </div>

                  {/* Date */}
                  <span className="hidden xl:flex items-center gap-1 text-[10px] font-medium text-[rgba(0,0,0,0.35)] whitespace-nowrap">
                    <Icon icon="mdi:calendar-outline" width={10} />
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
                      className={`w-1.5 h-1.5 rounded-full bg-white ${ticket.status.toLowerCase() === 'open' ? 'animate-pulse' : ''}`}
                    />
                    {ticket.status.replace('_', ' ')}
                  </span>

                  <Icon icon="mdi:chevron-right" width={16} className="text-[rgba(0,0,0,0.2)] group-hover:text-indigo-400 transition-colors" />
                </div>
                </AnimatedItem>
              );
            })}
          </div>
        )}

        {/* No Accounts */}
        {showNoAccounts && (
          <div className="max-w-md mx-auto text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
            <div className="w-14 h-14 bg-indigo-50 border border-indigo-100 rounded-xl flex items-center justify-center mx-auto mb-5">
              <Icon icon="mdi:power-plug-outline" width={28} className="text-indigo-500" />
            </div>
            <h3 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">No Accounts Connected</h3>
            <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-6 px-4">
              You don't have any cloud accounts linked yet. Add an account to start creating support tickets.
            </p>
            <a
              href="/account-manager"
              className="inline-flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white rounded-xl text-[12px] font-medium hover:bg-[rgba(0,0,0,0.75)] transition-all"
            >
              <Icon icon="mdi:plus" width={14} />
              Add Account
            </a>
          </div>
        )}

        {/* Empty State */}
        {showEmpty && (
          <div className="max-w-md mx-auto text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
            <div className="w-14 h-14 bg-indigo-50 border border-indigo-100 rounded-xl flex items-center justify-center mx-auto mb-5">
              <Icon icon="mdi:ticket-outline" width={28} className="text-indigo-500" />
            </div>
            <h3 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">No Tickets Found</h3>
            <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-6 px-4">
              {selectedAccountIds.length > 0
                ? `No technical tickets found for the selected account${selectedAccountIds.length > 1 ? 's' : ''}. Support is just a click away.`
                : 'No tickets yet. Create a new ticket to get technical support.'}
            </p>
            <button
              onClick={() => openCreateModalRef.current()}
              className="inline-flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white rounded-xl text-[12px] font-medium hover:bg-[rgba(0,0,0,0.75)] transition-all"
            >
              <Icon icon="mdi:plus" width={14} />
              Create New Ticket
            </button>
          </div>
        )}

      </div>

      {/* Create Ticket Modal */}
      <DialogRoot isOpen={showModal} onClose={closeModal} maxWidth="max-w-lg">
        <DialogPanel
          title={
            <div className="flex items-center gap-3">
              <Icon icon="mdi:plus-circle-outline" width={18} className="text-indigo-600" />
              <div>
                <div className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)]">Create Support Case</div>
                <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">Submit a new request for technical support</p>
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
                className="px-5 py-2.5 text-sm font-medium text-[rgba(0,0,0,0.6)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.04)] transition-colors shadow-sm"
              >
                Cancel
              </button>
              <button
                type="submit"
                form="create-ticket-form"
                disabled={createMutation.isPending}
                className="px-5 py-2.5 text-sm font-medium text-white bg-indigo-600 rounded-xl hover:bg-indigo-700 disabled:opacity-70 disabled:cursor-not-allowed transition-all shadow-sm flex items-center gap-2"
              >
                {createMutation.isPending ? (
                  <><Icon icon="mdi:loading" width={14} className="animate-spin" />Submitting...</>
                ) : (
                  <><Icon icon="mdi:check" width={14} />Submit Case</>
                )}
              </button>
            </>
          }
        >
          <form id="create-ticket-form" onSubmit={submitTicket}>
            <div className="space-y-5">

              {/* Category */}
              <div>
                <label className="text-[12px] font-semibold text-[rgba(0,0,0,0.7)] mb-1.5 block">Category</label>
                <div className="relative">
                  <select
                    value={form.category}
                    onChange={(e) => handleFormChange('category', e.target.value)}
                    className="w-full rounded-xl border border-[rgba(0,0,0,0.12)] py-2.5 pl-4 pr-10 text-[13px] text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-2 focus:ring-indigo-300 appearance-none bg-white hover:bg-[rgba(0,0,0,0.02)] transition-colors"
                  >
                    <option>Technical</option>
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-[rgba(0,0,0,0.4)]">
                    <Icon icon="mdi:lock-outline" width={12} />
                  </div>
                </div>
              </div>

              {/* Service */}
              <div>
                <label className="text-[12px] font-semibold text-[rgba(0,0,0,0.7)] mb-1.5 block">
                  Service <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <select
                    value={form.service}
                    onChange={(e) => handleFormChange('service', e.target.value)}
                    className="w-full rounded-xl border border-[rgba(0,0,0,0.12)] py-2.5 pl-4 pr-10 text-[13px] text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-2 focus:ring-indigo-300 appearance-none bg-white hover:bg-[rgba(0,0,0,0.02)] transition-colors"
                    required
                  >
                    <option disabled value="">Select Service</option>
                    {SERVICE_OPTIONS[form.category]?.map((option) => (
                      <option key={option} value={option}>{option}</option>
                    ))}
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-[rgba(0,0,0,0.4)]">
                    <Icon icon="mdi:chevron-down" width={14} />
                  </div>
                </div>
              </div>

              {/* Severity */}
              <div>
                <label className="text-[12px] font-semibold text-[rgba(0,0,0,0.7)] mb-1.5 block">
                  Severity <span className="text-red-500">*</span>
                </label>
                <div className="relative">
                  <select
                    value={form.severity}
                    onChange={(e) => handleFormChange('severity', e.target.value)}
                    className="w-full rounded-xl border border-[rgba(0,0,0,0.12)] py-2.5 pl-4 pr-10 text-[13px] text-[rgba(0,0,0,0.7)] focus:outline-none focus:ring-2 focus:ring-indigo-300 appearance-none bg-white hover:bg-[rgba(0,0,0,0.02)] transition-colors"
                    required
                  >
                    <option disabled value="">Select Severity</option>
                    {SEVERITY_OPTIONS.map((option) => (
                      <option key={option} value={option}>{option}</option>
                    ))}
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-[rgba(0,0,0,0.4)]">
                    <Icon icon="mdi:chevron-down" width={14} />
                  </div>
                </div>
              </div>

              {/* Subject */}
              <div>
                <label className="text-[12px] font-semibold text-[rgba(0,0,0,0.7)] mb-1.5 block">
                  Subject <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={form.subject}
                  onChange={(e) => handleFormChange('subject', e.target.value)}
                  maxLength={250}
                  className="w-full rounded-xl border border-[rgba(0,0,0,0.12)] py-2.5 px-4 text-[13px] focus:outline-none focus:ring-2 focus:ring-indigo-300 text-[rgba(0,0,0,0.7)] placeholder:text-[rgba(0,0,0,0.25)]"
                  placeholder="Briefly summarize your question or issue"
                  required
                />
              </div>

              {/* Description */}
              <div>
                <label className="text-[12px] font-semibold text-[rgba(0,0,0,0.7)] mb-1.5 block">
                  Description <span className="text-red-500">*</span>
                </label>
                <textarea
                  value={form.description}
                  onChange={(e) => handleFormChange('description', e.target.value)}
                  maxLength={8000}
                  rows={4}
                  className="w-full rounded-xl border border-[rgba(0,0,0,0.12)] py-2.5 px-4 text-[13px] focus:outline-none focus:ring-2 focus:ring-indigo-300 text-[rgba(0,0,0,0.7)] placeholder:text-[rgba(0,0,0,0.25)] resize-none"
                  placeholder="Describe your question or issue in detail"
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

export default TicketsPage;
