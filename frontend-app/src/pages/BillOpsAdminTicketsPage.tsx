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
import { createPortal } from 'react-dom';
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

// ─── Helpers hoisted outside component (stable references, no closure over state) ─

const getStatusBadgeClasses = (status: string) => {
  if (status === 'OPEN') return 'bg-emerald-50 text-emerald-700 border border-emerald-200';
  if (status === 'IN_PROGRESS') return 'bg-blue-50 text-blue-700 border border-blue-200';
  return 'bg-gray-100 text-gray-500 border border-gray-200';
};

const getSeverityDotClasses = (severity: string): string => {
  if (['High', 'Critical', 'Urgent'].includes(severity)) return 'bg-red-500';
  if (['Normal', 'Medium'].includes(severity)) return 'bg-yellow-500';
  if (['Low', 'Info'].includes(severity)) return 'bg-blue-500';
  return 'bg-gray-500';
};

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

// ─── TicketRow ─────────────────────────────────────────────────────────────────
// React.memo with value-based comparator: bails out when ticket data is unchanged,
// preventing re-renders on 30 s background polls that return the same data.
interface TicketRowProps {
  ticket: AdminTicket;
  onView: (id: number) => void;
  isStarredClient: boolean;
}

const TicketRow = React.memo<TicketRowProps>(({ ticket, onView, isStarredClient }) => {
  const isClosed = ticket.status === 'CLOSED';
  const sla = ticket.createdAt
    ? getSlaStatus(ticket.createdAt, ticket.severity, isClosed, ticket.closedAt)
    : null;

  const rowBorderClass = (() => {
    if (!sla) return '';
    if (sla.status === 'breached') return 'border-l-[3px] border-l-red-400 bg-red-50/20';
    if (['Critical', 'Urgent'].includes(ticket.severity) && !isClosed)
      return 'border-l-[3px] border-l-orange-400 bg-orange-50/10';
    return '';
  })();

  const slaBadge = sla ? (() => {
    const colorMap = {
      ok: 'bg-emerald-100 text-emerald-700 border-emerald-200',
      warning: 'bg-amber-100 text-amber-700 border-amber-200',
      breached: 'bg-red-100 text-red-700 border-red-200',
    };
    const dotMap = {
      ok: 'bg-emerald-500',
      warning: 'bg-amber-500 animate-pulse',
      breached: 'bg-red-500 animate-pulse',
    };
    return (
      <span
        className={`inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-semibold rounded border uppercase tracking-wide ${colorMap[sla.status]}`}
        title={sla.label}
      >
        <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${dotMap[sla.status]}`}></span>
        {sla.status === 'breached' ? 'Breached' : sla.status === 'warning' ? 'Warning' : 'On Track'}
      </span>
    );
  })() : null;

  return (
    <tr
      className={`ticket-row-enter hover:bg-gray-50 transition-colors cursor-pointer group ${rowBorderClass}`}
      onClick={() => onView(ticket.id)}
    >
      {/* ID */}
      <td className="px-4 py-3 whitespace-nowrap">
        <div className="flex items-center gap-1.5">
          {isStarredClient && (
            <i className="fas fa-star text-amber-400 text-[9px]" title="Primary client"></i>
          )}
          <span className="text-[11px] text-gray-400 font-mono">#{ticket.id}</span>
        </div>
      </td>

      {/* Account Name */}
      <td className="px-4 py-3 whitespace-nowrap max-w-[130px]">
        {ticket.accountName ? (
          <div title={ticket.accountId}>
            <span className="text-[12px] font-semibold text-gray-800 truncate block max-w-[120px]">
              {ticket.accountName}
            </span>
            <span className="text-[10px] text-gray-400 font-mono truncate block max-w-[120px]">
              {ticket.accountId}
            </span>
          </div>
        ) : (
          <span className="px-1.5 py-0.5 bg-gray-100 text-gray-600 rounded text-[10px] font-mono border border-gray-200 truncate max-w-[100px] block">
            {ticket.accountId || 'GLOBAL'}
          </span>
        )}
      </td>

      {/* Subject + Service */}
      <td className="px-4 py-3 max-w-[200px]">
        <div className="text-[12px] font-semibold text-gray-800 group-hover:text-indigo-600 transition-colors leading-tight mb-0.5 truncate">
          {ticket.subject}
        </div>
        <div className="text-[10px] text-gray-400 truncate">
          {ticket.service}
        </div>
      </td>

      {/* Status + Read/Unread */}
      <td className="px-4 py-3 whitespace-nowrap">
        <div className="flex flex-col gap-1">
          <span className={`inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-semibold rounded w-fit ${getStatusBadgeClasses(ticket.status)}`}>
            {ticket.status === 'OPEN' && <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse flex-shrink-0"></span>}
            {ticket.status === 'IN_PROGRESS' && <span className="w-1.5 h-1.5 rounded-full bg-blue-500 flex-shrink-0"></span>}
            {ticket.status === 'IN_PROGRESS' ? 'In Prog' : ticket.status === 'OPEN' ? 'Open' : 'Closed'}
          </span>
          {ticket.hasUnreadUserReply && (
            <span className="text-[9px] font-semibold px-1.5 py-0.5 rounded uppercase tracking-wide bg-amber-50 text-amber-600 border border-amber-200 w-fit">
              Unread
            </span>
          )}
        </div>
      </td>

      {/* Severity */}
      <td className="px-4 py-3 whitespace-nowrap">
        <div className="flex items-center gap-1.5">
          <span className={`w-2 h-2 rounded-full ${getSeverityDotClasses(ticket.severity)} flex-shrink-0`}></span>
          <span className="text-[11px] text-gray-600">{ticket.severity}</span>
        </div>
      </td>

      {/* SLA */}
      <td className="px-4 py-3 whitespace-nowrap">
        {slaBadge}
      </td>

      {/* Created (IST) + Relative Age */}
      <td className="px-4 py-3 whitespace-nowrap">
        <div className="text-[11px] text-gray-500 leading-tight">
          {ticket.createdAt ? formatIST(ticket.createdAt) : '—'}
        </div>
        {ticket.createdAt && !isClosed && (
          <div className="text-[10px] text-gray-400 mt-0.5">
            {formatTicketAge(ticket.createdAt)} old
          </div>
        )}
      </td>

      {/* Chat Started */}
      <td className="px-4 py-3 whitespace-nowrap hidden xl:table-cell">
        {ticket.chatStartedAt ? (
          <div className="text-[11px] text-indigo-600 leading-tight">
            {formatIST(ticket.chatStartedAt)}
          </div>
        ) : (
          <span className="text-[11px] text-gray-300">—</span>
        )}
      </td>

      {/* Closed At */}
      <td className="px-4 py-3 whitespace-nowrap hidden xl:table-cell">
        {ticket.closedAt ? (
          <div className="text-[11px] text-gray-500 leading-tight">
            {formatIST(ticket.closedAt)}
          </div>
        ) : (
          <span className="text-[11px] text-gray-300">—</span>
        )}
      </td>

      {/* Assigned To */}
      <td className="px-4 py-3 whitespace-nowrap">
        {ticket.assignedToUsername ? (
          <div className="flex items-center gap-1.5" title={ticket.creatorUsername ? `Creator: ${ticket.creatorUsername}` : undefined}>
            <div className="w-6 h-6 rounded-md bg-gray-100 flex items-center justify-center text-gray-600 text-[9px] font-semibold flex-shrink-0">
              {ticket.assignedToUsername.charAt(0).toUpperCase()}
            </div>
            <span className="text-[11px] text-gray-700 truncate max-w-[80px]">{ticket.assignedToUsername}</span>
          </div>
        ) : (
          <span className="text-[11px] text-gray-400 italic">Unassigned</span>
        )}
      </td>

      {/* Action */}
      <td className="px-4 py-3 whitespace-nowrap">
        <button
          onClick={(e) => { e.stopPropagation(); onView(ticket.id); }}
          className="text-gray-400 hover:text-indigo-600 bg-gray-100 hover:bg-indigo-50 transition-colors w-7 h-7 flex items-center justify-center rounded-md border border-gray-200 hover:border-indigo-200"
        >
          <i className="fas fa-chevron-right text-[10px]"></i>
        </button>
      </td>
    </tr>
  );
}, (prev, next) =>
  prev.ticket.id === next.ticket.id &&
  prev.ticket.updatedAt === next.ticket.updatedAt &&
  prev.ticket.hasUnreadUserReply === next.ticket.hasUnreadUserReply &&
  prev.isStarredClient === next.isStarredClient
);

// ──────────────────────────────────────────────────────────────────────────────

const BillOpsAdminTicketsPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    tickets,
    filteredTickets,
    starredClientIds,
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

  // Lock body scroll while the raise ticket modal is open
  useEffect(() => {
    if (showRaiseModal) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => { document.body.style.overflow = ''; };
  }, [showRaiseModal]);
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

  /** Sort icon for a given column */
  const renderSortIcon = (field: Exclude<SortField, ''>) => {
    if (sortField !== field) return <i className="fas fa-sort ml-1 opacity-20 text-[8px]"></i>;
    return <i className={`fas fa-chevron-${sortDir === 'asc' ? 'up' : 'down'} ml-1 text-indigo-500 text-[8px]`}></i>;
  };

  // Always use billops-ticket-detail — admins are authenticated through billops;
  // /ticket-detail calls /api/xamops/ which returns 401 for billops sessions
  const viewTicket = useCallback((id: number) => {
    navigate(`/billops-ticket-detail?id=${id}`);
  }, [navigate]);

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
      <div className="flex items-center gap-3">
        {/* Search */}
        <div className="relative hidden md:block">
          <span className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
            <i className="fas fa-search text-gray-400 text-[11px]"></i>
          </span>
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search tickets..."
            className="pl-9 pr-4 py-2 bg-gray-50 border border-gray-200 rounded-md text-[13px] focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 w-64 transition-all outline-none"
          />
        </div>

        {lastUpdatedDisplay && (
          <span className="hidden xl:block text-[11px] text-gray-400">
            Synced {lastUpdatedDisplay}
          </span>
        )}

        <button
          onClick={openRaiseModal}
          className="flex items-center gap-2 px-3 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-[12px] font-medium rounded-md transition-colors"
        >
          <i className="fas fa-plus text-[10px]"></i>
          Raise Ticket
        </button>

        <button
          onClick={() => fetchTickets(true)}
          disabled={loading || isUpdating}
          className="w-8 h-8 flex items-center justify-center text-gray-500 hover:text-indigo-600 hover:bg-gray-100 rounded-md transition-colors border border-gray-200 disabled:opacity-50"
          title="Refresh"
        >
          <i className={`fas fa-sync-alt text-[11px] ${isUpdating ? 'animate-spin' : ''}`}></i>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, search, setSearch, lastUpdatedDisplay, loading, isUpdating, fetchTickets, openRaiseModal]);

  const renderLoadingSkeleton = () => (
    <div className="bg-white border border-gray-200 rounded-md overflow-hidden shadow-sm">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-100">
          <thead className="bg-gray-50">
            <tr>
              {['ID', 'Account', 'Subject / Service', 'Status', 'Severity', 'SLA', 'Created (IST)', 'Chat Started', 'Closed At', 'Assigned To', 'Action'].map((h) => (
                <th key={h} className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {[1, 2, 3, 4, 5].map(i => (
              <tr key={i}>
                {Array.from({ length: 11 }).map((_, j) => (
                  <td key={j} className="px-4 py-3">
                    <div className="h-4 bg-gray-100 rounded animate-pulse" style={{ width: `${40 + (j * 13) % 60}px` }}></div>
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

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden" style={{ background: '#f5f6f8' }}>
      <div className="flex-1 overflow-x-hidden overflow-y-auto custom-scrollbar p-5">
        <div className="max-w-full mx-auto space-y-4">

          {/* Stats Summary — unified bar */}
          {!loading && hasData && (
            <div className="bg-white border border-gray-200 rounded-md shadow-sm grid grid-cols-2 lg:grid-cols-4 divide-x divide-gray-100">
              {/* Total */}
              <div className="p-4 flex items-center gap-3">
                <div className="w-8 h-8 rounded-md bg-gray-100 flex items-center justify-center flex-shrink-0">
                  <i className="fas fa-ticket-alt text-gray-500 text-xs"></i>
                </div>
                <div>
                  <p className="text-xl font-bold text-gray-900 leading-none">{stats.total}</p>
                  <p className="text-[11px] text-gray-400 mt-0.5">Total</p>
                </div>
              </div>

              {/* Open */}
              <div className="p-4 flex items-center gap-3">
                <div className="w-8 h-8 rounded-md bg-emerald-50 flex items-center justify-center flex-shrink-0">
                  <i className="fas fa-circle text-emerald-500 text-xs"></i>
                </div>
                <div>
                  <p className="text-xl font-bold text-emerald-600 leading-none">{stats.open}</p>
                  <p className="text-[11px] text-gray-400 mt-0.5">Open</p>
                </div>
              </div>

              {/* In Progress */}
              <div className="p-4 flex items-center gap-3">
                <div className="w-8 h-8 rounded-md bg-blue-50 flex items-center justify-center flex-shrink-0">
                  <i className="fas fa-spinner text-blue-500 text-xs"></i>
                </div>
                <div>
                  <p className="text-xl font-bold text-blue-600 leading-none">{stats.inProgress}</p>
                  <p className="text-[11px] text-gray-400 mt-0.5">In Progress</p>
                </div>
              </div>

              {/* SLA Breached — clickable */}
              <div
                className={`p-4 flex items-center gap-3 cursor-pointer transition-colors ${
                  stats.breached > 0
                    ? quickFilter === 'breached'
                      ? 'bg-red-50'
                      : 'hover:bg-red-50/50'
                    : 'hover:bg-gray-50'
                }`}
                onClick={() => setQuickFilter(q => q === 'breached' ? 'none' : 'breached')}
                title="Click to filter by SLA Breached"
              >
                <div className={`w-8 h-8 rounded-md flex items-center justify-center flex-shrink-0 ${stats.breached > 0 ? 'bg-red-50' : 'bg-gray-100'}`}>
                  <i className={`fas fa-exclamation-triangle text-xs ${stats.breached > 0 ? 'text-red-500' : 'text-gray-400'}`}></i>
                </div>
                <div>
                  <p className={`text-xl font-bold leading-none ${stats.breached > 0 ? 'text-red-600' : 'text-gray-400'}`}>
                    {stats.breached}
                  </p>
                  <p className={`text-[11px] mt-0.5 ${stats.breached > 0 ? 'text-red-400' : 'text-gray-400'}`}>
                    {quickFilter === 'breached' ? 'Click to clear' : 'SLA Breached'}
                  </p>
                </div>
              </div>
            </div>
          )}

          {/* Tabs + Filter Toggle Row */}
          <div className="flex items-center justify-between border-b border-gray-200 gap-4 flex-wrap">
            <nav className="flex items-center gap-0.5 flex-wrap" aria-label="Tabs">
              {allowedTicketTabs === undefined ? (
                <div className="flex gap-2 pb-2">
                  {[1, 2, 3].map(i => (
                    <div key={i} className="h-8 w-32 rounded bg-gray-100 animate-pulse" />
                  ))}
                </div>
              ) : visibleTabs.map((tab) => (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`flex items-center gap-2 px-4 py-3 text-[12px] font-medium border-b-2 -mb-px transition-colors ${
                    activeTab === tab.id
                      ? 'border-indigo-600 text-indigo-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                  }`}
                >
                  <i className={`${tab.icon} text-[11px]`}></i>
                  {tab.label}
                  <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${
                    activeTab === tab.id ? 'bg-indigo-50 text-indigo-600' : 'bg-gray-100 text-gray-500'
                  }`}>
                    {getCategoryCount(tab.id)}
                  </span>
                </button>
              ))}
            </nav>

            {/* Filter Toggle Button */}
            <button
              onClick={() => setShowFilters(v => !v)}
              className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-[12px] font-medium transition-colors border mb-1 ${
                showFilters || activeFilterCount > 0
                  ? 'bg-indigo-600 text-white border-indigo-600'
                  : 'bg-white text-gray-600 border-gray-200 hover:border-indigo-300 hover:text-indigo-600'
              }`}
            >
              <i className="fas fa-filter text-[11px]"></i>
              Filters
              {activeFilterCount > 0 && (
                <span className={`flex items-center justify-center w-4 h-4 rounded text-[9px] font-semibold ${showFilters || activeFilterCount > 0 ? 'bg-white/20 text-white' : 'bg-indigo-50 text-indigo-600'}`}>
                  {activeFilterCount}
                </span>
              )}
            </button>
          </div>

          {/* Quick Filter Chips */}
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-[11px] text-gray-400">Quick:</span>
            <button
              onClick={() => setQuickFilter(q => q === 'breached' ? 'none' : 'breached')}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11px] font-medium border transition-colors ${
                quickFilter === 'breached'
                  ? 'bg-red-600 text-white border-red-600'
                  : 'bg-white text-gray-600 border-gray-200 hover:border-red-300 hover:text-red-600'
              }`}
            >
              <i className="fas fa-exclamation-triangle text-[9px]"></i>
              SLA Breached
              {stats.breached > 0 && (
                <span className={`inline-flex items-center justify-center w-4 h-4 rounded text-[9px] font-semibold ${
                  quickFilter === 'breached' ? 'bg-white/20 text-white' : 'bg-red-50 text-red-600'
                }`}>
                  {stats.breached}
                </span>
              )}
            </button>
            <button
              onClick={() => setQuickFilter(q => q === 'unassigned' ? 'none' : 'unassigned')}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11px] font-medium border transition-colors ${
                quickFilter === 'unassigned'
                  ? 'bg-amber-500 text-white border-amber-500'
                  : 'bg-white text-gray-600 border-gray-200 hover:border-amber-300 hover:text-amber-600'
              }`}
            >
              <i className="fas fa-user-slash text-[9px]"></i>
              Unassigned
            </button>
            {quickFilter !== 'none' && (
              <button
                onClick={() => setQuickFilter('none')}
                className="flex items-center gap-1 px-2.5 py-1 rounded-md text-[11px] font-medium border border-gray-200 text-gray-400 hover:text-gray-600 transition-colors"
              >
                <i className="fas fa-times text-[9px]"></i>
                Clear
              </button>
            )}
          </div>

          {/* Advanced Filter Panel */}
          {showFilters && (
            <div className="bg-white border border-gray-200 rounded-md shadow-sm p-5 space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="text-[11px] font-semibold text-gray-700 uppercase tracking-widest">Advanced Filters</h3>
                {activeFilterCount > 0 && (
                  <button onClick={clearFilters} className="text-[11px] text-red-500 hover:text-red-700 flex items-center gap-1">
                    <i className="fas fa-times text-[9px]"></i> Clear All
                  </button>
                )}
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-5">
                {/* Status Filter */}
                <div>
                  <label className="block text-[11px] font-medium text-gray-500 uppercase tracking-wider mb-2">Status</label>
                  <div className="flex flex-wrap gap-1.5">
                    {ALL_STATUSES.map(status => (
                      <button
                        key={status}
                        onClick={() => toggleStatusFilter(status)}
                        className={`px-2.5 py-1 rounded-md text-[11px] font-medium border transition-colors ${
                          filters.statuses.includes(status)
                            ? status === 'OPEN'
                              ? 'bg-emerald-600 text-white border-emerald-600'
                              : status === 'IN_PROGRESS'
                                ? 'bg-blue-600 text-white border-blue-600'
                                : 'bg-gray-500 text-white border-gray-500'
                            : 'bg-gray-50 text-gray-500 border-gray-200 hover:border-gray-400'
                        }`}
                      >
                        {status}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Severity Filter */}
                <div>
                  <label className="block text-[11px] font-medium text-gray-500 uppercase tracking-wider mb-2">Severity</label>
                  <div className="flex flex-wrap gap-1.5">
                    {ALL_SEVERITIES.map(severity => (
                      <button
                        key={severity}
                        onClick={() => toggleSeverityFilter(severity)}
                        className={`px-2.5 py-1 rounded-md text-[11px] font-medium border transition-colors ${
                          filters.severities.includes(severity)
                            ? ['Critical', 'Urgent', 'High'].includes(severity)
                              ? 'bg-red-600 text-white border-red-600'
                              : ['Normal', 'Medium'].includes(severity)
                                ? 'bg-amber-500 text-white border-amber-500'
                                : 'bg-blue-500 text-white border-blue-500'
                            : 'bg-gray-50 text-gray-500 border-gray-200 hover:border-gray-400'
                        }`}
                      >
                        {severity}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Date Range */}
                <div>
                  <label className="block text-[11px] font-medium text-gray-500 uppercase tracking-wider mb-2">Created Date Range</label>
                  <div className="space-y-2">
                    <div>
                      <span className="text-[10px] text-gray-400">From</span>
                      <input
                        type="date"
                        value={filters.dateFrom}
                        onChange={e => setFilters({ ...filters, dateFrom: e.target.value })}
                        className="block w-full mt-1 px-3 py-1.5 bg-gray-50 border border-gray-200 rounded-md text-[12px] focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                      />
                    </div>
                    <div>
                      <span className="text-[10px] text-gray-400">To</span>
                      <input
                        type="date"
                        value={filters.dateTo}
                        onChange={e => setFilters({ ...filters, dateTo: e.target.value })}
                        className="block w-full mt-1 px-3 py-1.5 bg-gray-50 border border-gray-200 rounded-md text-[12px] focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                      />
                    </div>
                  </div>
                </div>

                {/* Assignee Filter */}
                <div>
                  <label className="block text-[11px] font-medium text-gray-500 uppercase tracking-wider mb-2">Assigned To</label>
                  <select
                    value={filters.assignee}
                    onChange={e => setFilters({ ...filters, assignee: e.target.value })}
                    className="block w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-md text-[12px] focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
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
            <div className="bg-white border border-gray-200 rounded-md shadow-sm overflow-hidden">
              <div className="overflow-x-auto custom-scrollbar">
                <table className="min-w-full divide-y divide-gray-100">
                  <thead className="bg-gray-50">
                    <tr>
                      <th
                        onClick={() => handleSort('id')}
                        className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        ID {renderSortIcon('id')}
                      </th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap">Account</th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap">Subject / Service</th>
                      <th
                        onClick={() => handleSort('status')}
                        className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        Status {renderSortIcon('status')}
                      </th>
                      <th
                        onClick={() => handleSort('severity')}
                        className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        Severity {renderSortIcon('severity')}
                      </th>
                      <th
                        onClick={() => handleSort('sla')}
                        className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        SLA {renderSortIcon('sla')}
                      </th>
                      <th
                        onClick={() => handleSort('createdAt')}
                        className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap cursor-pointer hover:text-indigo-600 select-none"
                      >
                        Created (IST) {renderSortIcon('createdAt')}
                      </th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap hidden xl:table-cell">Chat Started</th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap hidden xl:table-cell">Closed At</th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap">Assigned To</th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest whitespace-nowrap">Action</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {paginatedTickets.length > 0
                      ? paginatedTickets.map((ticket) => (
                          <TicketRow
                            key={ticket.id}
                            ticket={ticket}
                            onView={viewTicket}
                            isStarredClient={ticket.clientId != null && starredClientIds.has(ticket.clientId)}
                          />
                        ))
                      : renderEmptyState()}
                  </tbody>
                </table>
              </div>

              {/* Table Footer — Count + Pagination + Export */}
              <div className="px-5 py-3 border-t border-gray-100 bg-gray-50 flex items-center justify-between flex-wrap gap-3">

                {/* Left: count + rows-per-page */}
                <div className="flex items-center gap-3">
                  <span className="text-[11px] text-gray-400">
                    {displayTickets.length === 0
                      ? 'No tickets'
                      : `${(page - 1) * pageSize + 1}–${Math.min(page * pageSize, displayTickets.length)} of ${displayTickets.length}`}
                    {tickets.length !== displayTickets.length && ` (${tickets.length} total)`}
                  </span>
                  <select
                    value={pageSize}
                    onChange={e => setPageSize(Number(e.target.value))}
                    className="px-2 py-1 bg-white border border-gray-200 rounded-md text-[11px] text-gray-600 outline-none"
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
                      className="w-7 h-7 flex items-center justify-center rounded-md text-[11px] text-gray-400 hover:text-indigo-600 hover:bg-indigo-50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                    >
                      <i className="fas fa-angle-double-left"></i>
                    </button>
                    <button
                      onClick={() => setPage(p => Math.max(1, p - 1))}
                      disabled={page === 1}
                      className="w-7 h-7 flex items-center justify-center rounded-md text-[11px] text-gray-400 hover:text-indigo-600 hover:bg-indigo-50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                    >
                      <i className="fas fa-angle-left"></i>
                    </button>

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
                          className={`w-7 h-7 flex items-center justify-center rounded-md text-[11px] font-medium transition-colors ${
                            page === p
                              ? 'bg-indigo-600 text-white'
                              : 'text-gray-500 hover:text-indigo-600 hover:bg-indigo-50'
                          }`}
                        >
                          {p}
                        </button>
                      );
                    })}

                    <button
                      onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                      disabled={page === totalPages}
                      className="w-7 h-7 flex items-center justify-center rounded-md text-[11px] text-gray-400 hover:text-indigo-600 hover:bg-indigo-50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                    >
                      <i className="fas fa-angle-right"></i>
                    </button>
                    <button
                      onClick={() => setPage(totalPages)}
                      disabled={page === totalPages}
                      className="w-7 h-7 flex items-center justify-center rounded-md text-[11px] text-gray-400 hover:text-indigo-600 hover:bg-indigo-50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
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
                      className="text-[11px] text-red-500 hover:text-red-700 flex items-center gap-1"
                    >
                      <i className="fas fa-times text-[9px]"></i> Clear Filters
                    </button>
                  )}
                  <button
                    onClick={exportCSV}
                    disabled={displayTickets.length === 0}
                    className="flex items-center gap-1.5 px-3 py-1.5 bg-white text-gray-600 hover:bg-gray-50 rounded-md text-[11px] font-medium border border-gray-200 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <i className="fas fa-download text-[10px]"></i>
                    Export CSV
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* ─── Admin Raise Ticket Modal ─────────────────────────────────────── */}
      {showRaiseModal && createPortal(
        <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4">
          {/* Backdrop */}
          <div className="absolute inset-0 bg-gray-900/40" onClick={() => setShowRaiseModal(false)} />

          <div className="relative bg-white rounded-md shadow-xl border border-gray-200 w-full max-w-lg overflow-hidden">
            {/* Modal Header */}
            <div className="bg-indigo-600 px-5 py-4 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-md bg-white/10 flex items-center justify-center">
                  <i className="fas fa-ticket-alt text-white text-sm"></i>
                </div>
                <div>
                  <h2 className="text-[13px] font-semibold text-white leading-none">Raise Ticket</h2>
                  <p className="text-[11px] text-white/70 mt-0.5">
                    {raiseStep === 1 ? 'Select Client' : raiseStep === 2 ? 'Select Account' : 'Ticket Details'}
                  </p>
                </div>
              </div>
              <button onClick={() => setShowRaiseModal(false)} className="w-7 h-7 flex items-center justify-center rounded-md bg-white/10 hover:bg-white/20 text-white transition-colors">
                <i className="fas fa-times text-xs"></i>
              </button>
            </div>

            {/* Step Indicator */}
            <div className="px-5 pt-4 pb-0 flex items-center gap-2">
              {(['Client', 'Account', 'Details'] as const).map((label, idx) => {
                const step = idx + 1;
                const done = raiseStep > step;
                const active = raiseStep === step;
                return (
                  <React.Fragment key={label}>
                    <div className="flex items-center gap-1.5">
                      <div className={`w-5 h-5 rounded flex items-center justify-center text-[10px] font-semibold border transition-colors ${
                        done ? 'bg-indigo-600 border-indigo-600 text-white' :
                        active ? 'border-indigo-600 text-indigo-600 bg-indigo-50' :
                        'border-gray-200 text-gray-400 bg-white'
                      }`}>
                        {done ? <i className="fas fa-check text-[8px]"></i> : step}
                      </div>
                      <span className={`text-[11px] font-medium ${active ? 'text-indigo-600' : done ? 'text-gray-500' : 'text-gray-300'}`}>{label}</span>
                    </div>
                    {idx < 2 && <div className={`flex-1 h-px ${done ? 'bg-indigo-300' : 'bg-gray-100'}`} />}
                  </React.Fragment>
                );
              })}
            </div>

            <div className="p-5 pt-4">
              {/* Step 1: Select Client */}
              {raiseStep === 1 && (
                <div>
                  <p className="text-[11px] text-gray-500 mb-3">Choose a client to raise a ticket for</p>
                  {clientsLoading ? (
                    <div className="space-y-2">
                      {[1, 2, 3].map(i => <div key={i} className="h-12 bg-gray-100 rounded-md animate-pulse" />)}
                    </div>
                  ) : clients.length === 0 ? (
                    <p className="text-sm text-gray-400 text-center py-6">No clients found.</p>
                  ) : (
                    <div className="space-y-1.5 max-h-72 overflow-y-auto pr-1">
                      {clients.map(c => (
                        <button
                          key={c.id}
                          onClick={() => selectClient(c)}
                          className="w-full flex items-center justify-between px-4 py-3 rounded-md border border-gray-100 hover:border-indigo-200 hover:bg-indigo-50 text-left transition-colors group"
                        >
                          <div className="flex items-center gap-3">
                            <div className="w-7 h-7 rounded-md bg-gray-100 flex items-center justify-center text-gray-600 text-xs font-semibold">
                              {c.name.charAt(0).toUpperCase()}
                            </div>
                            <span className="text-[13px] font-medium text-gray-700 group-hover:text-indigo-700">{c.name}</span>
                          </div>
                          <i className="fas fa-chevron-right text-gray-300 group-hover:text-indigo-400 text-xs"></i>
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
                    <button onClick={() => setRaiseStep(1)} className="w-7 h-7 flex items-center justify-center rounded-md hover:bg-gray-100 text-gray-400 hover:text-gray-600 transition-colors">
                      <i className="fas fa-arrow-left text-xs"></i>
                    </button>
                    <p className="text-[11px] text-gray-500">
                      Accounts for <span className="text-indigo-600 font-medium">{selectedClient?.name}</span>
                    </p>
                  </div>
                  {accountsLoading ? (
                    <div className="space-y-2">
                      {[1, 2].map(i => <div key={i} className="h-14 bg-gray-100 rounded-md animate-pulse" />)}
                    </div>
                  ) : accounts.length === 0 ? (
                    <div className="text-center py-8">
                      <i className="fas fa-server text-gray-200 text-3xl mb-2"></i>
                      <p className="text-sm text-gray-400">No accounts found for this client.</p>
                      <button
                        onClick={() => { setSelectedAccount({ id: 0, accountName: 'No Account', provider: '', accountIdentifier: null }); setRaiseStep(3); }}
                        className="mt-3 text-[11px] font-medium text-indigo-600 hover:text-indigo-700"
                      >
                        Continue without account →
                      </button>
                    </div>
                  ) : (
                    <div className="space-y-1.5 max-h-72 overflow-y-auto pr-1">
                      {accounts.map(a => (
                        <button
                          key={a.id}
                          onClick={() => selectAccount(a)}
                          className="w-full flex items-center justify-between px-4 py-3 rounded-md border border-gray-100 hover:border-indigo-200 hover:bg-indigo-50 text-left transition-colors group"
                        >
                          <div className="flex items-center gap-3">
                            <div className={`w-7 h-7 rounded-md flex items-center justify-center text-xs font-semibold text-white ${
                              a.provider === 'AWS' ? 'bg-amber-500' : a.provider === 'GCP' ? 'bg-blue-500' : a.provider === 'Azure' ? 'bg-sky-600' : 'bg-gray-400'
                            }`}>
                              {(a.provider || '?').charAt(0)}
                            </div>
                            <div>
                              <p className="text-[13px] font-medium text-gray-700 group-hover:text-indigo-700 leading-none">{a.accountName}</p>
                              <p className="text-[10px] text-gray-400 font-mono mt-0.5">{a.accountIdentifier || '—'}</p>
                            </div>
                          </div>
                          <i className="fas fa-chevron-right text-gray-300 group-hover:text-indigo-400 text-xs"></i>
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
                    <button type="button" onClick={() => setRaiseStep(2)} className="w-7 h-7 flex items-center justify-center rounded-md hover:bg-gray-100 text-gray-400 hover:text-gray-600 transition-colors">
                      <i className="fas fa-arrow-left text-xs"></i>
                    </button>
                    <div className="flex items-center gap-1.5 text-[11px] text-gray-400">
                      <span className="text-indigo-600 font-medium">{selectedClient?.name}</span>
                      <span>/</span>
                      <span>{selectedAccount?.accountName || 'No Account'}</span>
                    </div>
                  </div>

                  {/* Category */}
                  <div>
                    <label className="block text-[11px] font-medium text-gray-500 uppercase tracking-wider mb-1.5">Category</label>
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
                          className={`px-3 py-2.5 rounded-md text-[11px] font-medium border transition-colors flex items-center gap-2 ${
                            raiseForm.category === cat
                              ? 'bg-indigo-600 text-white border-indigo-600'
                              : 'bg-gray-50 text-gray-500 border-gray-200 hover:border-indigo-300'
                          }`}
                        >
                          <i className={`${cat === 'Account and Billing' ? 'fas fa-receipt' : 'fas fa-wrench'} text-[10px]`}></i>
                          {cat}
                        </button>
                      ))}
                    </div>
                  </div>

                  {/* Service */}
                  <div>
                    <label className="block text-[11px] font-medium text-gray-500 uppercase tracking-wider mb-1.5">Service</label>
                    <select
                      value={raiseForm.service}
                      onChange={e => setRaiseForm(f => ({ ...f, service: e.target.value }))}
                      className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-md text-[13px] focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                    >
                      {SERVICE_OPTIONS[raiseForm.category].map(s => <option key={s} value={s}>{s}</option>)}
                    </select>
                  </div>

                  {/* Severity */}
                  <div>
                    <label className="block text-[11px] font-medium text-gray-500 uppercase tracking-wider mb-1.5">Severity</label>
                    <select
                      value={raiseForm.severity}
                      onChange={e => setRaiseForm(f => ({ ...f, severity: e.target.value }))}
                      className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-md text-[13px] focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                    >
                      {['Info', 'Low', 'Normal', 'Medium', 'High', 'Urgent', 'Critical'].map(s => <option key={s} value={s}>{s}</option>)}
                    </select>
                  </div>

                  {/* Subject */}
                  <div>
                    <label className="block text-[11px] font-medium text-gray-500 uppercase tracking-wider mb-1.5">Subject <span className="text-red-400">*</span></label>
                    <input
                      type="text"
                      required
                      value={raiseForm.subject}
                      onChange={e => setRaiseForm(f => ({ ...f, subject: e.target.value }))}
                      placeholder="Brief summary of the issue"
                      className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-md text-[13px] focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none"
                    />
                  </div>

                  {/* Description */}
                  <div>
                    <label className="block text-[11px] font-medium text-gray-500 uppercase tracking-wider mb-1.5">Description <span className="text-red-400">*</span></label>
                    <textarea
                      required
                      rows={4}
                      value={raiseForm.description}
                      onChange={e => setRaiseForm(f => ({ ...f, description: e.target.value }))}
                      placeholder="Describe the issue in detail..."
                      className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-md text-[13px] focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 outline-none resize-none"
                    />
                  </div>

                  {raiseError && (
                    <p className="text-[11px] text-red-600 flex items-center gap-1">
                      <i className="fas fa-exclamation-circle"></i> {raiseError}
                    </p>
                  )}

                  <button
                    type="submit"
                    disabled={raiseSubmitting}
                    className="w-full py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-medium rounded-md transition-colors disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2"
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
        </div>,
        document.body
      )}
      {/* ──────────────────────────────────────────────────────────────────── */}
    </div>
  );
};

export default BillOpsAdminTicketsPage;
