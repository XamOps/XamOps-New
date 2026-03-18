/**
 * BillOps Admin Tickets Page Component
 *
 * Advanced support ticket management for ROLE_BILLOPS_ADMIN users.
 * Features:
 * - Stats summary cards (Total, Open, In Progress, SLA Breached)
 * - Tabbed view (Account & Billing, Technical, All)
 * - IST time display on all timestamps
 * - Chat Start and Chat End (Closed At) columns
 * - Assigned To column
 * - SLA status indicators per ticket
 * - Advanced filter panel (status, severity, date range, assignee)
 * - Quick-filter chips (SLA Breached, Unassigned)
 * - Column sorting (ID, Status, Severity, SLA, Created)
 * - Relative ticket age display
 * - Export to CSV
 * - Row visual tinting for priority/SLA
 * - Pagination with per-page selector
 * - Search across all ticket fields
 */

import React, { useEffect, useState, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useBillOpsAdminTickets } from '../hooks/useBillOpsAdminTickets';
import { useTopbarActions } from '../contexts/TopbarContext';
import {
  AdminTicket,
  AdminTicketTab,
  ADMIN_TICKET_TABS
} from '../types/billopsAdminTickets';
import { formatIST, getSlaStatus } from '../utils/timeUtils';
import { SERVICE_OPTIONS, TicketCategory } from '../types/billopsTickets';

const ALL_STATUSES = ['OPEN', 'IN_PROGRESS', 'CLOSED'];
const ALL_SEVERITIES = ['Critical', 'Urgent', 'High', 'Normal', 'Medium', 'Low', 'Info'];
const SEVERITY_ORDER = ['Critical', 'Urgent', 'High', 'Normal', 'Medium', 'Low', 'Info'];

type SortField = 'id' | 'status' | 'severity' | 'sla' | 'createdAt' | '';
type QuickFilter = 'none' | 'breached' | 'unassigned';

const BillOpsAdminTicketsPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    tickets,
    filteredTickets,
    loading,
    isUpdating,
    error,
    lastUpdatedDisplay,
    hasData,
    activeTab,
    search,
    filters,
    activeFilterCount,
    setActiveTab,
    setSearch,
    setFilters,
    clearFilters,
    fetchTickets,
    getCategoryCount
  } = useBillOpsAdminTickets();

  const { setActions, clearActions } = useTopbarActions();
  const [showFilters, setShowFilters] = useState(false);

  // Ticket category access control for sub-users
  // undefined = still loading (hide tabs until resolved to avoid flash)
  // null      = no restrictions (primary admin or tickets-all)
  // array     = specific tabs allowed
  const [allowedTicketTabs, setAllowedTicketTabs] = useState<AdminTicketTab[] | null | undefined>(undefined);

  // ─── Admin Raise Ticket state ──────────────────────────────────────────────
  interface ClientOption { id: number; name: string; }
  interface AccountOption { id: number; accountName: string; provider: string; accountIdentifier: string | null; }

  const [showRaiseModal, setShowRaiseModal] = useState(false);
  const [raiseStep, setRaiseStep] = useState<1 | 2 | 3>(1);
  const [clients, setClients] = useState<ClientOption[]>([]);
  const [clientsLoading, setClientsLoading] = useState(false);
  const [selectedClient, setSelectedClient] = useState<ClientOption | null>(null);
  const [accounts, setAccounts] = useState<AccountOption[]>([]);
  const [accountsLoading, setAccountsLoading] = useState(false);
  const [selectedAccount, setSelectedAccount] = useState<AccountOption | null>(null);
  const [raiseForm, setRaiseForm] = useState({
    category: 'Account and Billing' as TicketCategory,
    service: 'Billing Inquiry',
    severity: 'Normal',
    subject: '',
    description: '',
  });
  const [raiseSubmitting, setRaiseSubmitting] = useState(false);
  const [raiseError, setRaiseError] = useState('');

  const openRaiseModal = useCallback(() => {
    setRaiseStep(1);
    setSelectedClient(null);
    setSelectedAccount(null);
    setAccounts([]);
    setRaiseForm({ category: 'Account and Billing', service: 'Billing Inquiry', severity: 'Normal', subject: '', description: '' });
    setRaiseError('');
    setShowRaiseModal(true);
    // Fetch clients
    setClientsLoading(true);
    fetch('/api/billops/clients', { credentials: 'include' })
      .then(r => r.ok ? r.json() : [])
      .then(data => { setClients(data); setClientsLoading(false); })
      .catch(() => setClientsLoading(false));
  }, []);

  const selectClient = useCallback((client: ClientOption) => {
    setSelectedClient(client);
    setSelectedAccount(null);
    setAccounts([]);
    setAccountsLoading(true);
    fetch(`/api/billops/clients/${client.id}/accounts`, { credentials: 'include' })
      .then(r => r.ok ? r.json() : [])
      .then(data => { setAccounts(data); setAccountsLoading(false); })
      .catch(() => setAccountsLoading(false));
    setRaiseStep(2);
  }, []);

  const selectAccount = useCallback((account: AccountOption) => {
    setSelectedAccount(account);
    setRaiseStep(3);
  }, []);

  const handleRaiseSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedClient || !selectedAccount) return;
    setRaiseSubmitting(true);
    setRaiseError('');
    try {
      const res = await fetch('/api/billops/tickets/admin-create', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          clientId: selectedClient.id,
          accountId: selectedAccount.accountIdentifier,
          category: raiseForm.category,
          service: raiseForm.service,
          severity: raiseForm.severity,
          subject: raiseForm.subject,
          description: raiseForm.description,
        }),
      });
      if (!res.ok) throw new Error('Failed to create ticket');
      setShowRaiseModal(false);
      fetchTickets(true);
    } catch {
      setRaiseError('Failed to create ticket. Please try again.');
    } finally {
      setRaiseSubmitting(false);
    }
  }, [selectedClient, selectedAccount, raiseForm, fetchTickets]);
  // ──────────────────────────────────────────────────────────────────────────

  useEffect(() => {
    fetch('/api/billops/team/profile', { credentials: 'include' })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (!data || data.isPrimaryUser) {
          setAllowedTicketTabs(null);
          return;
        }
        const tabs: string[] = data.allowedTabs || [];
        if (tabs.includes('tickets-all')) {
          setAllowedTicketTabs(null);
        } else {
          const allowed: AdminTicketTab[] = [];
          if (tabs.includes('tickets-account-billing')) allowed.push('Account and Billing');
          if (tabs.includes('tickets-technical')) allowed.push('Technical');
          if (allowed.length === 2) allowed.push('All');
          // Set active tab immediately before render to avoid flash
          if (allowed.length > 0) setActiveTab(allowed[0]);
          setAllowedTicketTabs(allowed.length > 0 ? allowed : null);
        }
      })
      .catch(() => setAllowedTicketTabs(null));
  }, []);

  // Tabs visible to this user (filtered by category access)
  const visibleTabs = useMemo(() => {
    if (allowedTicketTabs === undefined) return []; // still loading — render nothing
    if (!allowedTicketTabs) return ADMIN_TICKET_TABS;
    return ADMIN_TICKET_TABS.filter(t => allowedTicketTabs.includes(t.id));
  }, [allowedTicketTabs]);
  const [sortField, setSortField] = useState<SortField>('');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [quickFilter, setQuickFilter] = useState<QuickFilter>('none');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);

  // Collect unique assignees for the filter dropdown
  const uniqueAssignees = useMemo(() => {
    const assignees = tickets
      .map(t => t.assignedToUsername)
      .filter((u): u is string => Boolean(u));
    return Array.from(new Set(assignees)).sort();
  }, [tickets]);

  // Stats computed from ALL tickets (not filtered)
  const stats = useMemo(() => {
    const open = tickets.filter(t => t.status === 'OPEN').length;
    const inProgress = tickets.filter(t => t.status === 'IN_PROGRESS').length;
    const breached = tickets.filter(t => {
      if (!t.createdAt) return false;
      return getSlaStatus(t.createdAt, t.severity, t.status === 'CLOSED', t.closedAt).status === 'breached';
    }).length;
    return { total: tickets.length, open, inProgress, breached };
  }, [tickets]);

  // Apply quick filter + sort on top of hook-filtered tickets
  const displayTickets = useMemo(() => {
    let result = filteredTickets;

    // Quick filters
    if (quickFilter === 'breached') {
      result = result.filter(t => {
        if (!t.createdAt) return false;
        return getSlaStatus(t.createdAt, t.severity, t.status === 'CLOSED', t.closedAt).status === 'breached';
      });
    } else if (quickFilter === 'unassigned') {
      result = result.filter(t => !t.assignedToUsername);
    }

    // Sorting
    if (sortField) {
      result = [...result].sort((a, b) => {
        let aVal: number | string = 0;
        let bVal: number | string = 0;

        if (sortField === 'id') {
          aVal = a.id; bVal = b.id;
        } else if (sortField === 'status') {
          aVal = a.status || ''; bVal = b.status || '';
        } else if (sortField === 'severity') {
          aVal = SEVERITY_ORDER.indexOf(a.severity);
          bVal = SEVERITY_ORDER.indexOf(b.severity);
          if (aVal === -1) aVal = 99;
          if (bVal === -1) bVal = 99;
        } else if (sortField === 'sla') {
          const order: Record<string, number> = { breached: 0, warning: 1, ok: 2 };
          const aSla = a.createdAt ? getSlaStatus(a.createdAt, a.severity, a.status === 'CLOSED', a.closedAt).status : 'ok';
          const bSla = b.createdAt ? getSlaStatus(b.createdAt, b.severity, b.status === 'CLOSED', b.closedAt).status : 'ok';
          aVal = order[aSla] ?? 2; bVal = order[bSla] ?? 2;
        } else if (sortField === 'createdAt') {
          aVal = a.createdAt ? new Date(a.createdAt).getTime() : 0;
          bVal = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        }

        if (aVal < bVal) return sortDir === 'asc' ? -1 : 1;
        if (aVal > bVal) return sortDir === 'asc' ? 1 : -1;
        return 0;
      });
    }

    return result;
  }, [filteredTickets, quickFilter, sortField, sortDir]);

  const totalPages = Math.ceil(displayTickets.length / pageSize);

  const paginatedTickets = useMemo(() => {
    const start = (page - 1) * pageSize;
    return displayTickets.slice(start, start + pageSize);
  }, [displayTickets, page, pageSize]);

  // Reset to page 1 whenever the filtered result set changes
  useEffect(() => { setPage(1); }, [filteredTickets, quickFilter, pageSize]);

  const getStatusBadgeClasses = (status: string) => {
    if (status === 'OPEN') return 'bg-emerald-500 text-white ring-4 ring-emerald-500/10';
    if (status === 'IN_PROGRESS') return 'bg-amber-500 text-white ring-4 ring-amber-500/10';
    return 'bg-slate-400 text-white ring-4 ring-slate-400/10';
  };

  const getSeverityDotClasses = (severity: string): string => {
    if (['High', 'Critical', 'Urgent'].includes(severity)) return 'bg-red-500';
    if (['Normal', 'Medium'].includes(severity)) return 'bg-yellow-500';
    if (['Low', 'Info'].includes(severity)) return 'bg-blue-500';
    return 'bg-gray-500';
  };

  const getSlaBadge = (ticket: AdminTicket) => {
    if (!ticket.createdAt) return null;
    const isClosed = ticket.status === 'CLOSED';
    const sla = getSlaStatus(ticket.createdAt, ticket.severity, isClosed, ticket.closedAt);
    const colorMap = {
      ok: 'bg-emerald-100 text-emerald-700 border-emerald-200',
      warning: 'bg-amber-100 text-amber-700 border-amber-200',
      breached: 'bg-red-100 text-red-700 border-red-200'
    };
    const dotMap = {
      ok: 'bg-emerald-500',
      warning: 'bg-amber-500 animate-pulse',
      breached: 'bg-red-500 animate-pulse'
    };
    return (
      <span
        className={`inline-flex items-center gap-1 px-2 py-0.5 text-[9px] font-black rounded-full border uppercase tracking-widest ${colorMap[sla.status]}`}
        title={sla.label}
      >
        <span className={`w-1.5 h-1.5 rounded-full ${dotMap[sla.status]}`}></span>
        {sla.status === 'breached' ? 'Breached' : sla.status === 'warning' ? 'Warning' : 'On Track'}
      </span>
    );
  };

  /** Row left-border tinting based on SLA and severity */
  const getRowClasses = (ticket: AdminTicket): string => {
    if (!ticket.createdAt) return '';
    const sla = getSlaStatus(ticket.createdAt, ticket.severity, ticket.status === 'CLOSED', ticket.closedAt);
    if (sla.status === 'breached') return 'border-l-[3px] border-l-red-400 bg-red-50/20';
    if (['Critical', 'Urgent'].includes(ticket.severity) && ticket.status !== 'CLOSED') {
      return 'border-l-[3px] border-l-orange-400 bg-orange-50/10';
    }
    return '';
  };

  /** Compact age string: "3d", "5h", "12m", or "now" */
  const formatTicketAge = (isoString: string): string => {
    if (!isoString) return '—';
    const ms = Date.now() - new Date(isoString).getTime();
    const mins = Math.floor(ms / 60000);
    const hours = Math.floor(ms / 3600000);
    const days = Math.floor(ms / 86400000);
    if (days > 0) return `${days}d`;
    if (hours > 0) return `${hours}h`;
    if (mins > 0) return `${mins}m`;
    return 'now';
  };

  /** Sort icon for a given column */
  const renderSortIcon = (field: Exclude<SortField, ''>) => {
    if (sortField !== field) return <i className="fas fa-sort ml-1 opacity-20 text-[8px]"></i>;
    return <i className={`fas fa-chevron-${sortDir === 'asc' ? 'up' : 'down'} ml-1 text-indigo-500 text-[8px]`}></i>;
  };

  const viewTicket = (id: number) => {
    // Always use billops-ticket-detail — admins are authenticated through billops
    // and /ticket-detail calls /api/xamops/ which returns 401 for billops sessions
    navigate(`/billops-ticket-detail?id=${id}`);
  };

  const toggleStatusFilter = useCallback((status: string) => {
    setFilters({
      ...filters,
      statuses: filters.statuses.includes(status)
        ? filters.statuses.filter(s => s !== status)
        : [...filters.statuses, status]
    });
  }, [filters, setFilters]);

  const toggleSeverityFilter = useCallback((severity: string) => {
    setFilters({
      ...filters,
      severities: filters.severities.includes(severity)
        ? filters.severities.filter(s => s !== severity)
        : [...filters.severities, severity]
    });
  }, [filters, setFilters]);

  const handleSort = useCallback((field: Exclude<SortField, ''>) => {
    if (sortField === field) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDir('desc');
    }
  }, [sortField]);

  const exportCSV = useCallback(() => {
    const headers = [
      'ID', 'Account Name', 'Account ID', 'Subject', 'Service', 'Status', 'Severity', 'SLA',
      'Created (IST)', 'Chat Started (IST)', 'Closed At (IST)',
      'Assigned To', 'Category', 'Creator'
    ];
    const rows = displayTickets.map(t => {
      const sla = t.createdAt
        ? getSlaStatus(t.createdAt, t.severity, t.status === 'CLOSED', t.closedAt).status
        : '';
      return [
        t.id,
        t.accountName ? `"${t.accountName.replace(/"/g, '""')}"` : '',
        t.accountId || '',
        `"${(t.subject || '').replace(/"/g, '""')}"`,
        t.service || '',
        t.status,
        t.severity,
        sla,
        t.createdAt ? formatIST(t.createdAt) : '',
        t.chatStartedAt ? formatIST(t.chatStartedAt) : '',
        t.closedAt ? formatIST(t.closedAt) : '',
        t.assignedToUsername || '',
        t.category || '',
        t.creatorUsername || ''
      ].join(',');
    });
    const csv = [headers.join(','), ...rows].join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `tickets_${new Date().toISOString().split('T')[0]}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }, [displayTickets]);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-6">
        {/* Page Title */}
        <div className="hidden lg:flex items-center gap-3 pr-6 border-r border-slate-200/50">
          <div className="w-9 h-9 flex items-center justify-center rounded-xl bg-orange-50 text-orange-600 shadow-sm border border-orange-100/50">
            <i className="fas fa-user-shield"></i>
          </div>
          <div>
            <h1 className="text-sm font-bold text-slate-900 leading-none">Admin Tickets</h1>
            <p className="text-[10px] text-slate-500 font-medium mt-1">Global Ticket Management</p>
          </div>
        </div>

        {/* Search */}
        <div className="relative group hidden md:block">
          <span className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none">
            <i className="fas fa-search text-slate-400 group-focus-within:text-indigo-500 transition-colors text-[10px]"></i>
          </span>
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search all tickets..."
            className="pl-10 pr-4 py-2 bg-slate-100/50 border border-transparent rounded-xl text-xs font-medium focus:ring-4 focus:ring-indigo-500/10 focus:bg-white focus:border-indigo-500/30 w-72 transition-all outline-none"
          />
        </div>

        {/* Actions */}
        <div className="flex items-center gap-3">
          {lastUpdatedDisplay && (
            <div className="hidden xl:flex items-center gap-2 px-3 py-1.5 bg-slate-50 rounded-lg border border-slate-100/50 text-[10px] font-bold text-slate-400 uppercase tracking-widest leading-none">
              <i className="fas fa-history opacity-70"></i>
              Synced {lastUpdatedDisplay}
            </div>
          )}
          <button
            onClick={openRaiseModal}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-[10px] font-black uppercase tracking-widest rounded-xl transition-all shadow-md shadow-indigo-500/20 active:scale-95"
          >
            <i className="fas fa-plus text-[9px]"></i>
            Raise Ticket
          </button>
          <button
            onClick={() => fetchTickets(true)}
            disabled={loading || isUpdating}
            className="w-10 h-10 flex items-center justify-center text-slate-500 hover:text-indigo-600 hover:bg-slate-100 rounded-xl transition-all border border-transparent hover:border-slate-200/50"
            title="Refresh Data"
          >
            <i className={`fas fa-sync-alt ${isUpdating ? 'animate-spin' : ''}`}></i>
          </button>
        </div>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, search, setSearch, lastUpdatedDisplay, loading, isUpdating, fetchTickets, openRaiseModal]);

  const renderLoadingSkeleton = () => (
    <div className="bg-white/70 backdrop-blur-xl rounded-[2rem] shadow-xl shadow-slate-200/50 border border-white overflow-hidden">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-slate-100">
          <thead className="bg-slate-50/50">
            <tr>
              {['ID', 'Account', 'Subject / Service', 'Status', 'Severity', 'SLA', 'Created (IST)', 'Chat Started', 'Closed At', 'Assigned To', 'Action'].map((h) => (
                <th key={h} className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em]">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="bg-white/30 divide-y divide-slate-50">
            {[1, 2, 3, 4, 5].map(i => (
              <tr key={i}>
                {Array.from({ length: 11 }).map((_, j) => (
                  <td key={j} className="px-3 py-3">
                    <div className="h-4 bg-slate-100 rounded-full animate-pulse" style={{ width: `${40 + (j * 13) % 60}px` }}></div>
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );

  const renderEmptyState = () => (
    <tr>
      <td colSpan={11} className="px-6 py-12 text-center text-gray-500 bg-gray-50">
        <div className="flex flex-col items-center">
          <i className="fas fa-inbox text-4xl mb-3 text-gray-300"></i>
          <p className="text-base font-medium">No tickets found</p>
          <p className="text-sm mt-1">No tickets match your current filters or search.</p>
          {(activeFilterCount > 0 || quickFilter !== 'none') && (
            <button
              onClick={() => { clearFilters(); setQuickFilter('none'); }}
              className="mt-3 text-sm font-bold text-indigo-600 hover:text-indigo-800 underline"
            >
              Clear Filters
            </button>
          )}
        </div>
      </td>
    </tr>
  );

  const renderTicketRow = (ticket: AdminTicket) => (
    <tr
      key={ticket.id}
      className={`hover:bg-indigo-50/30 transition-all cursor-pointer group border-b border-transparent hover:border-indigo-100 ${getRowClasses(ticket)}`}
      onClick={() => viewTicket(ticket.id)}
    >
      {/* ID */}
      <td className="px-3 py-3 whitespace-nowrap">
        <span className="text-xs font-bold text-slate-400 font-mono tracking-tighter opacity-60">#{ticket.id}</span>
      </td>

      {/* Account Name */}
      <td className="px-3 py-3 whitespace-nowrap max-w-[120px]">
        {ticket.accountName ? (
          <div title={ticket.accountId}>
            <span className="text-[11px] font-bold text-slate-700 truncate block max-w-[110px]">
              {ticket.accountName}
            </span>
            <span className="text-[9px] text-slate-400 font-mono truncate block max-w-[110px]">
              {ticket.accountId}
            </span>
          </div>
        ) : (
          <span className="px-1.5 py-0.5 bg-slate-100 text-slate-600 rounded-lg text-[10px] font-bold font-mono tracking-tight border border-slate-200/50 truncate max-w-[90px] block">
            {ticket.accountId || 'GLOBAL'}
          </span>
        )}
      </td>

      {/* Subject + Service */}
      <td className="px-3 py-3 max-w-[180px]">
        <div className="text-xs font-bold text-slate-800 group-hover:text-indigo-600 transition-colors leading-tight mb-0.5 truncate">
          {ticket.subject}
        </div>
        <div className="text-[10px] text-slate-400 font-medium truncate opacity-80">
          {ticket.service}
        </div>
      </td>

      {/* Status + Read/Unread */}
      <td className="px-3 py-3 whitespace-nowrap">
        <div className="flex flex-col gap-1">
          <span className={`flex items-center gap-1 px-2 py-0.5 text-[10px] font-black rounded-full shadow-sm ring-1 ring-inset w-fit ${getStatusBadgeClasses(ticket.status)}`}>
            <span className={`w-1.5 h-1.5 rounded-full bg-white shadow-[0_0_8px_white] ${ticket.status === 'OPEN' ? 'animate-pulse' : ''}`}></span>
            {ticket.status === 'IN_PROGRESS' ? 'IN PROG' : ticket.status}
          </span>
          {ticket.hasUnreadUserReply && (
            <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full uppercase tracking-wide bg-amber-100 text-amber-700 w-fit">
              Unread
            </span>
          )}
        </div>
      </td>

      {/* Severity */}
      <td className="px-3 py-3 whitespace-nowrap">
        <div className="flex items-center gap-1.5">
          <span className={`w-2 h-2 rounded-full ${getSeverityDotClasses(ticket.severity)} flex-shrink-0`}></span>
          <span className="text-[10px] font-bold text-slate-600 uppercase tracking-widest">{ticket.severity}</span>
        </div>
      </td>

      {/* SLA */}
      <td className="px-3 py-3 whitespace-nowrap">
        {getSlaBadge(ticket)}
      </td>

      {/* Created (IST) + Relative Age */}
      <td className="px-3 py-3 whitespace-nowrap">
        <div className="text-[10px] font-bold text-slate-500 leading-tight">
          {ticket.createdAt ? formatIST(ticket.createdAt) : '—'}
        </div>
        {ticket.createdAt && ticket.status !== 'CLOSED' && (
          <div className="text-[9px] font-bold text-slate-400 mt-0.5 opacity-60">
            {formatTicketAge(ticket.createdAt)} old
          </div>
        )}
      </td>

      {/* Chat Started */}
      <td className="px-3 py-3 whitespace-nowrap hidden xl:table-cell">
        {ticket.chatStartedAt ? (
          <div className="text-[10px] font-bold text-indigo-600 leading-tight">
            {formatIST(ticket.chatStartedAt)}
          </div>
        ) : (
          <span className="text-[10px] text-slate-300 font-bold">—</span>
        )}
      </td>

      {/* Closed At */}
      <td className="px-3 py-3 whitespace-nowrap hidden xl:table-cell">
        {ticket.closedAt ? (
          <div className="text-[10px] font-bold text-slate-500 leading-tight">
            {formatIST(ticket.closedAt)}
          </div>
        ) : (
          <span className="text-[10px] text-slate-300 font-bold">—</span>
        )}
      </td>

      {/* Assigned To (with creator tooltip) */}
      <td className="px-3 py-3 whitespace-nowrap">
        {ticket.assignedToUsername ? (
          <div className="flex items-center gap-1.5" title={ticket.creatorUsername ? `Creator: ${ticket.creatorUsername}` : undefined}>
            <div className="w-5 h-5 rounded-full bg-indigo-600 flex items-center justify-center text-white text-[8px] font-black flex-shrink-0">
              {ticket.assignedToUsername.charAt(0).toUpperCase()}
            </div>
            <span className="text-[10px] font-bold text-slate-700 truncate max-w-[70px]">{ticket.assignedToUsername}</span>
          </div>
        ) : (
          <span className="text-[10px] text-slate-300 font-bold italic">Unassigned</span>
        )}
      </td>

      {/* Action */}
      <td className="px-3 py-3 whitespace-nowrap">
        <button
          onClick={(e) => { e.stopPropagation(); viewTicket(ticket.id); }}
          className="text-slate-400 hover:text-indigo-600 bg-slate-100 hover:bg-indigo-100 transition-all w-8 h-8 flex items-center justify-center rounded-xl border border-transparent hover:border-indigo-200 shadow-sm group-hover:scale-110 active:scale-95"
        >
          <i className="fas fa-chevron-right text-xs"></i>
        </button>
      </td>
    </tr>
  );

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      <div className="flex-1 overflow-x-hidden overflow-y-auto custom-scrollbar p-6 lg:p-8">
        <div className="max-w-full mx-auto space-y-6">

          {/* Stats Summary Cards */}
          {!loading && hasData && (
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              {/* Total */}
              <div className="bg-white/70 backdrop-blur-xl rounded-2xl border border-white shadow-[0_4px_20px_rgba(0,0,0,0.03)] p-5">
                <div className="flex items-center justify-between mb-3">
                  <div className="w-9 h-9 rounded-xl bg-indigo-50 flex items-center justify-center">
                    <i className="fas fa-ticket-alt text-indigo-500 text-sm"></i>
                  </div>
                  <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest">Total</span>
                </div>
                <p className="text-3xl font-black text-slate-800 leading-none">{stats.total}</p>
                <p className="text-[10px] font-bold text-slate-400 mt-1 uppercase tracking-widest">All Tickets</p>
              </div>

              {/* Open */}
              <div className="bg-white/70 backdrop-blur-xl rounded-2xl border border-white shadow-[0_4px_20px_rgba(0,0,0,0.03)] p-5">
                <div className="flex items-center justify-between mb-3">
                  <div className="w-9 h-9 rounded-xl bg-emerald-50 flex items-center justify-center">
                    <i className="fas fa-circle text-emerald-500 text-sm animate-pulse"></i>
                  </div>
                  <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest">Open</span>
                </div>
                <p className="text-3xl font-black text-emerald-600 leading-none">{stats.open}</p>
                <p className="text-[10px] font-bold text-slate-400 mt-1 uppercase tracking-widest">Awaiting Response</p>
              </div>

              {/* In Progress */}
              <div className="bg-white/70 backdrop-blur-xl rounded-2xl border border-white shadow-[0_4px_20px_rgba(0,0,0,0.03)] p-5">
                <div className="flex items-center justify-between mb-3">
                  <div className="w-9 h-9 rounded-xl bg-amber-50 flex items-center justify-center">
                    <i className="fas fa-spinner text-amber-500 text-sm"></i>
                  </div>
                  <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest">In Prog</span>
                </div>
                <p className="text-3xl font-black text-amber-600 leading-none">{stats.inProgress}</p>
                <p className="text-[10px] font-bold text-slate-400 mt-1 uppercase tracking-widest">Being Handled</p>
              </div>

              {/* SLA Breached — clickable shortcut to quick filter */}
              <div
                className={`backdrop-blur-xl rounded-2xl border shadow-[0_4px_20px_rgba(0,0,0,0.03)] p-5 cursor-pointer transition-all ${
                  stats.breached > 0
                    ? quickFilter === 'breached'
                      ? 'border-red-400 bg-red-50/60 shadow-red-100'
                      : 'border-red-200/70 bg-red-50/30 hover:bg-red-50/50'
                    : 'border-white bg-white/70 hover:bg-slate-50/50'
                }`}
                onClick={() => setQuickFilter(q => q === 'breached' ? 'none' : 'breached')}
                title="Click to filter by SLA Breached"
              >
                <div className="flex items-center justify-between mb-3">
                  <div className={`w-9 h-9 rounded-xl flex items-center justify-center ${stats.breached > 0 ? 'bg-red-100' : 'bg-slate-100'}`}>
                    <i className={`fas fa-exclamation-triangle text-sm ${stats.breached > 0 ? 'text-red-500' : 'text-slate-400'}`}></i>
                  </div>
                  <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest">SLA</span>
                </div>
                <p className={`text-3xl font-black leading-none ${stats.breached > 0 ? 'text-red-600' : 'text-slate-400'}`}>
                  {stats.breached}
                </p>
                <p className={`text-[10px] font-bold mt-1 uppercase tracking-widest ${stats.breached > 0 ? 'text-red-400' : 'text-slate-400'}`}>
                  {quickFilter === 'breached' ? 'Filtered ✓' : 'Breached'}
                </p>
              </div>
            </div>
          )}

          {/* Tabs + Filter Toggle Row */}
          <div className="flex items-center justify-between border-b border-slate-200/50 pb-2 gap-4 flex-wrap">
            <nav className="flex space-x-2 flex-wrap gap-y-2" aria-label="Tabs">
              {allowedTicketTabs === undefined ? (
                /* Skeleton placeholder while profile loads — prevents layout flash */
                <div className="flex space-x-2">
                  {[1, 2, 3].map(i => (
                    <div key={i} className="h-10 w-36 rounded-2xl bg-slate-100 animate-pulse" />
                  ))}
                </div>
              ) : visibleTabs.map((tab) => (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`relative py-3 px-5 rounded-2xl text-[11px] font-black uppercase tracking-widest flex items-center gap-2 transition-all ${
                    activeTab === tab.id
                      ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-500/20'
                      : 'text-slate-500 hover:text-slate-800 hover:bg-slate-100'
                  }`}
                >
                  <i className={`${tab.icon} ${activeTab === tab.id ? 'opacity-100' : 'opacity-40'}`}></i>
                  {tab.label}
                  <span className={`flex items-center justify-center px-2 py-0.5 rounded-full text-[9px] min-w-[20px] shadow-sm ${
                    activeTab === tab.id ? 'bg-white/20 text-white' : 'bg-slate-200 text-slate-600'
                  }`}>
                    {getCategoryCount(tab.id)}
                  </span>
                  {activeTab === tab.id && (
                    <span className="absolute -bottom-2.5 left-1/2 -translate-x-1/2 w-1.5 h-1.5 bg-indigo-600 rounded-full shadow-[0_0_8px_rgba(79,70,229,0.8)] animate-pulse"></span>
                  )}
                </button>
              ))}
            </nav>

            {/* Filter Toggle Button */}
            <button
              onClick={() => setShowFilters(v => !v)}
              className={`flex items-center gap-2 px-4 py-2 rounded-xl text-[10px] font-black uppercase tracking-widest transition-all border ${
                showFilters || activeFilterCount > 0
                  ? 'bg-indigo-600 text-white border-indigo-600 shadow-lg shadow-indigo-500/20'
                  : 'bg-white text-slate-600 border-slate-200 hover:border-indigo-300 hover:text-indigo-600'
              }`}
            >
              <i className="fas fa-filter"></i>
              Filters
              {activeFilterCount > 0 && (
                <span className="flex items-center justify-center w-4 h-4 rounded-full text-[9px] font-black bg-white text-indigo-600">
                  {activeFilterCount}
                </span>
              )}
            </button>
          </div>

          {/* Quick Filter Chips */}
          <div className="flex items-center gap-2 flex-wrap -mt-2">
            <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest">Quick:</span>
            <button
              onClick={() => setQuickFilter(q => q === 'breached' ? 'none' : 'breached')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[10px] font-black uppercase tracking-widest border transition-all ${
                quickFilter === 'breached'
                  ? 'bg-red-500 text-white border-red-500 shadow-md shadow-red-500/20'
                  : 'bg-white text-slate-600 border-slate-200 hover:border-red-300 hover:text-red-600'
              }`}
            >
              <i className="fas fa-exclamation-triangle text-[9px]"></i>
              SLA Breached
              {stats.breached > 0 && (
                <span className={`flex items-center justify-center w-4 h-4 rounded-full text-[9px] font-black ${
                  quickFilter === 'breached' ? 'bg-white/20 text-white' : 'bg-red-100 text-red-600'
                }`}>
                  {stats.breached}
                </span>
              )}
            </button>
            <button
              onClick={() => setQuickFilter(q => q === 'unassigned' ? 'none' : 'unassigned')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[10px] font-black uppercase tracking-widest border transition-all ${
                quickFilter === 'unassigned'
                  ? 'bg-amber-500 text-white border-amber-500 shadow-md shadow-amber-500/20'
                  : 'bg-white text-slate-600 border-slate-200 hover:border-amber-300 hover:text-amber-600'
              }`}
            >
              <i className="fas fa-user-slash text-[9px]"></i>
              Unassigned
            </button>
            {quickFilter !== 'none' && (
              <button
                onClick={() => setQuickFilter('none')}
                className="flex items-center gap-1 px-3 py-1.5 rounded-full text-[10px] font-black uppercase tracking-widest border border-slate-200 text-slate-400 hover:text-slate-600 transition-all"
              >
                <i className="fas fa-times text-[9px]"></i>
                Clear
              </button>
            )}
          </div>

          {/* Advanced Filter Panel */}
          {showFilters && (
            <div className="bg-white/80 backdrop-blur-xl rounded-[1.5rem] border border-slate-200/50 shadow-[0_8px_30px_rgba(0,0,0,0.04)] p-6 space-y-5">
              <div className="flex items-center justify-between mb-1">
                <h3 className="text-xs font-black text-slate-700 uppercase tracking-widest">Advanced Filters</h3>
                {activeFilterCount > 0 && (
                  <button onClick={clearFilters} className="text-[10px] font-black text-rose-500 hover:text-rose-700 uppercase tracking-widest flex items-center gap-1">
                    <i className="fas fa-times"></i> Clear All
                  </button>
                )}
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                {/* Status Filter */}
                <div>
                  <label className="block text-[10px] font-black text-slate-400 uppercase tracking-widest mb-2">Status</label>
                  <div className="flex flex-wrap gap-2">
                    {ALL_STATUSES.map(status => (
                      <button
                        key={status}
                        onClick={() => toggleStatusFilter(status)}
                        className={`px-3 py-1.5 rounded-xl text-[10px] font-black uppercase tracking-widest border transition-all ${
                          filters.statuses.includes(status)
                            ? status === 'OPEN'
                              ? 'bg-emerald-500 text-white border-emerald-500'
                              : status === 'IN_PROGRESS'
                                ? 'bg-amber-500 text-white border-amber-500'
                                : 'bg-slate-500 text-white border-slate-500'
                            : 'bg-slate-50 text-slate-500 border-slate-200 hover:border-slate-400'
                        }`}
                      >
                        {status}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Severity Filter */}
                <div>
                  <label className="block text-[10px] font-black text-slate-400 uppercase tracking-widest mb-2">Severity</label>
                  <div className="flex flex-wrap gap-2">
                    {ALL_SEVERITIES.map(severity => (
                      <button
                        key={severity}
                        onClick={() => toggleSeverityFilter(severity)}
                        className={`px-3 py-1.5 rounded-xl text-[10px] font-black uppercase tracking-widest border transition-all ${
                          filters.severities.includes(severity)
                            ? ['Critical', 'Urgent', 'High'].includes(severity)
                              ? 'bg-red-500 text-white border-red-500'
                              : ['Normal', 'Medium'].includes(severity)
                                ? 'bg-amber-500 text-white border-amber-500'
                                : 'bg-blue-500 text-white border-blue-500'
                            : 'bg-slate-50 text-slate-500 border-slate-200 hover:border-slate-400'
                        }`}
                      >
                        {severity}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Date Range */}
                <div>
                  <label className="block text-[10px] font-black text-slate-400 uppercase tracking-widest mb-2">Created Date Range</label>
                  <div className="space-y-2">
                    <div>
                      <span className="text-[9px] font-bold text-slate-400 uppercase tracking-widest">From</span>
                      <input
                        type="date"
                        value={filters.dateFrom}
                        onChange={e => setFilters({ ...filters, dateFrom: e.target.value })}
                        className="block w-full mt-1 px-3 py-1.5 bg-slate-50 border border-slate-200 rounded-lg text-[11px] font-medium focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                      />
                    </div>
                    <div>
                      <span className="text-[9px] font-bold text-slate-400 uppercase tracking-widest">To</span>
                      <input
                        type="date"
                        value={filters.dateTo}
                        onChange={e => setFilters({ ...filters, dateTo: e.target.value })}
                        className="block w-full mt-1 px-3 py-1.5 bg-slate-50 border border-slate-200 rounded-lg text-[11px] font-medium focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                      />
                    </div>
                  </div>
                </div>

                {/* Assignee Filter */}
                <div>
                  <label className="block text-[10px] font-black text-slate-400 uppercase tracking-widest mb-2">Assigned To</label>
                  <select
                    value={filters.assignee}
                    onChange={e => setFilters({ ...filters, assignee: e.target.value })}
                    className="block w-full px-3 py-2 bg-slate-50 border border-slate-200 rounded-xl text-[11px] font-medium focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                  >
                    <option value="">All Assignees</option>
                    {uniqueAssignees.map(a => (
                      <option key={a} value={a}>{a}</option>
                    ))}
                  </select>
                </div>
              </div>
            </div>
          )}

          {/* Error */}
          {error && !hasData && (
            <div className="bg-red-50 border-l-4 border-red-500 p-4 rounded shadow-sm">
              <div className="flex items-center">
                <i className="fas fa-exclamation-circle text-red-500 mr-3"></i>
                <div>
                  <p className="text-sm text-red-700 font-medium">Error loading data</p>
                  <p className="text-sm text-red-600">{error}</p>
                  <button onClick={() => fetchTickets(true)} className="mt-2 text-sm font-medium text-red-700 underline hover:text-red-600">
                    Try Again
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Loading Skeleton */}
          {loading && renderLoadingSkeleton()}

          {/* Table */}
          {!loading && (
            <div className="bg-white/70 backdrop-blur-xl rounded-[2.5rem] shadow-[0_8px_40px_rgba(0,0,0,0.03)] border border-white overflow-hidden">
              <div className="overflow-x-auto custom-scrollbar">
                <table className="min-w-full divide-y divide-slate-100/50">
                  <thead className="bg-slate-50/50">
                    <tr>
                      <th
                        onClick={() => handleSort('id')}
                        className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        ID {renderSortIcon('id')}
                      </th>
                      <th className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Account</th>
                      <th className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Subject / Service</th>
                      <th
                        onClick={() => handleSort('status')}
                        className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        Status {renderSortIcon('status')}
                      </th>
                      <th
                        onClick={() => handleSort('severity')}
                        className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        Severity {renderSortIcon('severity')}
                      </th>
                      <th
                        onClick={() => handleSort('sla')}
                        className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        SLA {renderSortIcon('sla')}
                      </th>
                      <th
                        onClick={() => handleSort('createdAt')}
                        className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        Created (IST) {renderSortIcon('createdAt')}
                      </th>
                      <th className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap hidden xl:table-cell">Chat Started</th>
                      <th className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap hidden xl:table-cell">Closed At</th>
                      <th className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Assigned To</th>
                      <th className="px-3 py-3 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Action</th>
                    </tr>
                  </thead>
                  <tbody className="bg-white/20 divide-y divide-slate-50">
                    {paginatedTickets.length > 0
                      ? paginatedTickets.map((ticket) => renderTicketRow(ticket))
                      : renderEmptyState()}
                  </tbody>
                </table>
              </div>

              {/* Table Footer — Count + Pagination + Export */}
              <div className="px-6 py-3 border-t border-slate-100/50 bg-slate-50/30 flex items-center justify-between flex-wrap gap-3">

                {/* Left: count + rows-per-page */}
                <div className="flex items-center gap-4">
                  <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">
                    {displayTickets.length === 0
                      ? 'No tickets'
                      : `${(page - 1) * pageSize + 1}–${Math.min(page * pageSize, displayTickets.length)} of ${displayTickets.length}`}
                    {tickets.length !== displayTickets.length && ` (${tickets.length} total)`}
                  </span>
                  <select
                    value={pageSize}
                    onChange={e => setPageSize(Number(e.target.value))}
                    className="px-2 py-1 bg-white border border-slate-200 rounded-lg text-[10px] font-bold text-slate-600 outline-none"
                  >
                    <option value={25}>25 / page</option>
                    <option value={50}>50 / page</option>
                    <option value={100}>100 / page</option>
                  </select>
                </div>

                {/* Center: page navigation */}
                {totalPages > 1 && (
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => setPage(1)}
                      disabled={page === 1}
                      className="w-7 h-7 flex items-center justify-center rounded-lg text-[10px] font-black text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 disabled:opacity-30 disabled:cursor-not-allowed transition-all"
                    >
                      <i className="fas fa-angle-double-left"></i>
                    </button>
                    <button
                      onClick={() => setPage(p => Math.max(1, p - 1))}
                      disabled={page === 1}
                      className="w-7 h-7 flex items-center justify-center rounded-lg text-[10px] font-black text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 disabled:opacity-30 disabled:cursor-not-allowed transition-all"
                    >
                      <i className="fas fa-angle-left"></i>
                    </button>

                    {/* Page number pills (sliding window of 5) */}
                    {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                      let p: number;
                      if (totalPages <= 5) {
                        p = i + 1;
                      } else if (page <= 3) {
                        p = i + 1;
                      } else if (page >= totalPages - 2) {
                        p = totalPages - 4 + i;
                      } else {
                        p = page - 2 + i;
                      }
                      return (
                        <button
                          key={p}
                          onClick={() => setPage(p)}
                          className={`w-7 h-7 flex items-center justify-center rounded-lg text-[10px] font-black transition-all ${
                            page === p
                              ? 'bg-indigo-600 text-white shadow-md shadow-indigo-500/20'
                              : 'text-slate-500 hover:text-indigo-600 hover:bg-indigo-50'
                          }`}
                        >
                          {p}
                        </button>
                      );
                    })}

                    <button
                      onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                      disabled={page === totalPages}
                      className="w-7 h-7 flex items-center justify-center rounded-lg text-[10px] font-black text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 disabled:opacity-30 disabled:cursor-not-allowed transition-all"
                    >
                      <i className="fas fa-angle-right"></i>
                    </button>
                    <button
                      onClick={() => setPage(totalPages)}
                      disabled={page === totalPages}
                      className="w-7 h-7 flex items-center justify-center rounded-lg text-[10px] font-black text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 disabled:opacity-30 disabled:cursor-not-allowed transition-all"
                    >
                      <i className="fas fa-angle-double-right"></i>
                    </button>
                  </div>
                )}

                {/* Right: Clear Filters + Export CSV */}
                <div className="flex items-center gap-3">
                  {(activeFilterCount > 0 || quickFilter !== 'none') && (
                    <button
                      onClick={() => { clearFilters(); setQuickFilter('none'); }}
                      className="text-[10px] font-black text-rose-500 hover:text-rose-700 uppercase tracking-widest flex items-center gap-1"
                    >
                      <i className="fas fa-times"></i> Clear Filters
                    </button>
                  )}
                  <button
                    onClick={exportCSV}
                    disabled={displayTickets.length === 0}
                    className="flex items-center gap-2 px-3 py-1.5 bg-emerald-50 text-emerald-700 hover:bg-emerald-100 rounded-xl text-[10px] font-black uppercase tracking-widest border border-emerald-200/50 transition-all disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <i className="fas fa-download text-[9px]"></i>
                    Export CSV
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* ─── Admin Raise Ticket Modal ─────────────────────────────────────── */}
      {showRaiseModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          {/* Backdrop */}
          <div className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" onClick={() => setShowRaiseModal(false)} />

          <div className="relative bg-white rounded-[2rem] shadow-2xl shadow-indigo-900/10 border border-white w-full max-w-lg overflow-hidden">
            {/* Modal Header */}
            <div className="bg-gradient-to-br from-indigo-600 to-indigo-700 px-6 py-5 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-xl bg-white/15 flex items-center justify-center">
                  <i className="fas fa-ticket-alt text-white text-sm"></i>
                </div>
                <div>
                  <h2 className="text-sm font-black text-white leading-none">Raise Ticket</h2>
                  <p className="text-[10px] text-white/70 mt-0.5 font-medium">
                    {raiseStep === 1 ? 'Select Client' : raiseStep === 2 ? 'Select Account' : 'Ticket Details'}
                  </p>
                </div>
              </div>
              <button onClick={() => setShowRaiseModal(false)} className="w-8 h-8 flex items-center justify-center rounded-xl bg-white/10 hover:bg-white/20 text-white transition-all">
                <i className="fas fa-times text-xs"></i>
              </button>
            </div>

            {/* Step Indicator */}
            <div className="px-6 pt-4 pb-0 flex items-center gap-2">
              {(['Client', 'Account', 'Details'] as const).map((label, idx) => {
                const step = idx + 1;
                const done = raiseStep > step;
                const active = raiseStep === step;
                return (
                  <React.Fragment key={label}>
                    <div className="flex items-center gap-1.5">
                      <div className={`w-6 h-6 rounded-full flex items-center justify-center text-[10px] font-black border-2 transition-all ${
                        done ? 'bg-indigo-600 border-indigo-600 text-white' :
                        active ? 'border-indigo-600 text-indigo-600 bg-indigo-50' :
                        'border-slate-200 text-slate-400 bg-white'
                      }`}>
                        {done ? <i className="fas fa-check text-[8px]"></i> : step}
                      </div>
                      <span className={`text-[10px] font-black uppercase tracking-widest ${active ? 'text-indigo-600' : done ? 'text-slate-500' : 'text-slate-300'}`}>{label}</span>
                    </div>
                    {idx < 2 && <div className={`flex-1 h-px ${done ? 'bg-indigo-300' : 'bg-slate-100'}`} />}
                  </React.Fragment>
                );
              })}
            </div>

            <div className="p-6 pt-4">
              {/* Step 1: Select Client */}
              {raiseStep === 1 && (
                <div>
                  <p className="text-[11px] font-bold text-slate-500 uppercase tracking-widest mb-3">Choose client to raise ticket for</p>
                  {clientsLoading ? (
                    <div className="space-y-2">
                      {[1, 2, 3].map(i => <div key={i} className="h-12 bg-slate-100 rounded-xl animate-pulse" />)}
                    </div>
                  ) : clients.length === 0 ? (
                    <p className="text-sm text-slate-400 text-center py-6">No clients found.</p>
                  ) : (
                    <div className="space-y-2 max-h-72 overflow-y-auto pr-1">
                      {clients.map(c => (
                        <button
                          key={c.id}
                          onClick={() => selectClient(c)}
                          className="w-full flex items-center justify-between px-4 py-3 rounded-xl border border-slate-100 hover:border-indigo-300 hover:bg-indigo-50/50 text-left transition-all group"
                        >
                          <div className="flex items-center gap-3">
                            <div className="w-8 h-8 rounded-lg bg-indigo-100 flex items-center justify-center text-indigo-600 text-xs font-black">
                              {c.name.charAt(0).toUpperCase()}
                            </div>
                            <span className="text-sm font-bold text-slate-700 group-hover:text-indigo-700">{c.name}</span>
                          </div>
                          <i className="fas fa-chevron-right text-slate-300 group-hover:text-indigo-400 text-xs"></i>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {/* Step 2: Select Account */}
              {raiseStep === 2 && (
                <div>
                  <div className="flex items-center gap-2 mb-3">
                    <button onClick={() => setRaiseStep(1)} className="w-7 h-7 flex items-center justify-center rounded-lg hover:bg-slate-100 text-slate-400 hover:text-slate-600 transition-all">
                      <i className="fas fa-arrow-left text-xs"></i>
                    </button>
                    <p className="text-[11px] font-bold text-slate-500 uppercase tracking-widest">
                      Accounts for <span className="text-indigo-600">{selectedClient?.name}</span>
                    </p>
                  </div>
                  {accountsLoading ? (
                    <div className="space-y-2">
                      {[1, 2].map(i => <div key={i} className="h-14 bg-slate-100 rounded-xl animate-pulse" />)}
                    </div>
                  ) : accounts.length === 0 ? (
                    <div className="text-center py-8">
                      <i className="fas fa-server text-slate-200 text-3xl mb-2"></i>
                      <p className="text-sm text-slate-400">No accounts found for this client.</p>
                      <p className="text-[10px] text-slate-300 mt-1">You can still continue without an account.</p>
                      <button
                        onClick={() => { setSelectedAccount({ id: 0, accountName: 'No Account', provider: '', accountIdentifier: null }); setRaiseStep(3); }}
                        className="mt-3 text-[10px] font-black text-indigo-600 hover:text-indigo-700 uppercase tracking-widest"
                      >
                        Continue without account →
                      </button>
                    </div>
                  ) : (
                    <div className="space-y-2 max-h-72 overflow-y-auto pr-1">
                      {accounts.map(a => (
                        <button
                          key={a.id}
                          onClick={() => selectAccount(a)}
                          className="w-full flex items-center justify-between px-4 py-3 rounded-xl border border-slate-100 hover:border-indigo-300 hover:bg-indigo-50/50 text-left transition-all group"
                        >
                          <div className="flex items-center gap-3">
                            <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-xs font-black text-white ${
                              a.provider === 'AWS' ? 'bg-amber-500' : a.provider === 'GCP' ? 'bg-blue-500' : a.provider === 'Azure' ? 'bg-sky-600' : 'bg-slate-400'
                            }`}>
                              {(a.provider || '?').charAt(0)}
                            </div>
                            <div>
                              <p className="text-sm font-bold text-slate-700 group-hover:text-indigo-700 leading-none">{a.accountName}</p>
                              <p className="text-[10px] text-slate-400 font-mono mt-0.5">{a.accountIdentifier || '—'}</p>
                            </div>
                          </div>
                          <i className="fas fa-chevron-right text-slate-300 group-hover:text-indigo-400 text-xs"></i>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {/* Step 3: Ticket Details */}
              {raiseStep === 3 && (
                <form onSubmit={handleRaiseSubmit} className="space-y-4">
                  <div className="flex items-center gap-2 mb-1">
                    <button type="button" onClick={() => setRaiseStep(2)} className="w-7 h-7 flex items-center justify-center rounded-lg hover:bg-slate-100 text-slate-400 hover:text-slate-600 transition-all">
                      <i className="fas fa-arrow-left text-xs"></i>
                    </button>
                    <div className="flex items-center gap-2 text-[10px] font-bold text-slate-400 uppercase tracking-widest">
                      <span className="text-indigo-600">{selectedClient?.name}</span>
                      <span>/</span>
                      <span>{selectedAccount?.accountName || 'No Account'}</span>
                    </div>
                  </div>

                  {/* Category */}
                  <div>
                    <label className="block text-[10px] font-black text-slate-400 uppercase tracking-widest mb-1.5">Category</label>
                    <div className="grid grid-cols-2 gap-2">
                      {(['Account and Billing', 'Technical'] as TicketCategory[]).map(cat => (
                        <button
                          key={cat}
                          type="button"
                          onClick={() => setRaiseForm(f => ({
                            ...f,
                            category: cat,
                            service: SERVICE_OPTIONS[cat][0]
                          }))}
                          className={`px-3 py-2.5 rounded-xl text-[10px] font-black uppercase tracking-widest border transition-all flex items-center gap-2 ${
                            raiseForm.category === cat
                              ? 'bg-indigo-600 text-white border-indigo-600 shadow-md shadow-indigo-500/20'
                              : 'bg-slate-50 text-slate-500 border-slate-200 hover:border-indigo-300'
                          }`}
                        >
                          <i className={`${cat === 'Account and Billing' ? 'fas fa-receipt' : 'fas fa-wrench'} text-[9px]`}></i>
                          {cat}
                        </button>
                      ))}
                    </div>
                  </div>

                  {/* Service */}
                  <div>
                    <label className="block text-[10px] font-black text-slate-400 uppercase tracking-widest mb-1.5">Service</label>
                    <select
                      value={raiseForm.service}
                      onChange={e => setRaiseForm(f => ({ ...f, service: e.target.value }))}
                      className="w-full px-3 py-2.5 bg-slate-50 border border-slate-200 rounded-xl text-xs font-medium focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                    >
                      {SERVICE_OPTIONS[raiseForm.category].map(s => <option key={s} value={s}>{s}</option>)}
                    </select>
                  </div>

                  {/* Severity */}
                  <div>
                    <label className="block text-[10px] font-black text-slate-400 uppercase tracking-widest mb-1.5">Severity</label>
                    <select
                      value={raiseForm.severity}
                      onChange={e => setRaiseForm(f => ({ ...f, severity: e.target.value }))}
                      className="w-full px-3 py-2.5 bg-slate-50 border border-slate-200 rounded-xl text-xs font-medium focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                    >
                      {['Info', 'Low', 'Normal', 'Medium', 'High', 'Urgent', 'Critical'].map(s => <option key={s} value={s}>{s}</option>)}
                    </select>
                  </div>

                  {/* Subject */}
                  <div>
                    <label className="block text-[10px] font-black text-slate-400 uppercase tracking-widest mb-1.5">Subject <span className="text-red-400">*</span></label>
                    <input
                      type="text"
                      required
                      value={raiseForm.subject}
                      onChange={e => setRaiseForm(f => ({ ...f, subject: e.target.value }))}
                      placeholder="Brief summary of the issue"
                      className="w-full px-3 py-2.5 bg-slate-50 border border-slate-200 rounded-xl text-xs font-medium focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                    />
                  </div>

                  {/* Description */}
                  <div>
                    <label className="block text-[10px] font-black text-slate-400 uppercase tracking-widest mb-1.5">Description <span className="text-red-400">*</span></label>
                    <textarea
                      required
                      rows={4}
                      value={raiseForm.description}
                      onChange={e => setRaiseForm(f => ({ ...f, description: e.target.value }))}
                      placeholder="Describe the issue in detail..."
                      className="w-full px-3 py-2.5 bg-slate-50 border border-slate-200 rounded-xl text-xs font-medium focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none resize-none"
                    />
                  </div>

                  {raiseError && (
                    <p className="text-[11px] font-bold text-red-600 flex items-center gap-1">
                      <i className="fas fa-exclamation-circle"></i> {raiseError}
                    </p>
                  )}

                  <button
                    type="submit"
                    disabled={raiseSubmitting}
                    className="w-full py-3 bg-indigo-600 hover:bg-indigo-700 text-white text-[11px] font-black uppercase tracking-widest rounded-xl transition-all shadow-md shadow-indigo-500/20 disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2 active:scale-[0.98]"
                  >
                    {raiseSubmitting ? (
                      <><i className="fas fa-spinner animate-spin text-xs"></i> Creating...</>
                    ) : (
                      <><i className="fas fa-paper-plane text-xs"></i> Raise Ticket</>
                    )}
                  </button>
                </form>
              )}
            </div>
          </div>
        </div>
      )}
      {/* ──────────────────────────────────────────────────────────────────── */}
    </div>
  );
};

export default BillOpsAdminTicketsPage;
