/**
 * BillOps Admin Tickets Page Component
 *
 * Displays and manages ALL support tickets for ROLE_BILLOPS_ADMIN users
 * Features: Tabbed view (Account & Billing, Technical, All), search, real-time updates
 *
 * Converted from: billops/admin_tickets.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Topbar integration for search, actions and status
 * - Soft Glass Minimal design system
 * - Premium animations and transitions
 *
 * What stayed the same:
 * - All API endpoints remain identical (/api/billops/tickets)
 * - Same caching strategy (stale-while-revalidate)
 * - Same tab filtering logic
 * - Same navigation logic to ticket details
 * - All business logic preserved exactly
 */

import React, { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useBillOpsAdminTickets } from '../hooks/useBillOpsAdminTickets';
import { useTopbarActions } from '../contexts/TopbarContext';
import {
  AdminTicket,
  ADMIN_TICKET_TABS
} from '../types/billopsAdminTickets';

const BillOpsAdminTicketsPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    filteredTickets,
    loading,
    isUpdating,
    error,
    lastUpdatedDisplay,
    hasData,
    activeTab,
    search,
    setActiveTab,
    setSearch,
    fetchTickets,
    getCategoryCount
  } = useBillOpsAdminTickets();

  const { setActions, clearActions } = useTopbarActions();

  const getStatusBadgeClasses = (status: string) => {
    if (status === 'OPEN') {
      return 'bg-emerald-500 text-white shadow-emerald-200 ring-4 ring-emerald-500/10';
    }
    if (status === 'IN_PROGRESS') {
      return 'bg-amber-500 text-white shadow-amber-200 ring-4 ring-amber-500/10';
    }
    return 'bg-slate-400 text-white shadow-slate-200 ring-4 ring-slate-400/10';
  };

  const getSeverityColor = (severity: string): string => {
    if (['High', 'Critical', 'Urgent'].includes(severity)) {
      return '#ef4444';
    } else if (['Normal', 'Medium'].includes(severity)) {
      return '#f59e0b';
    }
    return '#3b82f6';
  };

  /**
   * Format date for ticket display
   */
  const formatDate = (dateString: string): string => {
    if (!dateString) return '';
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric'
    });
  };


  /**
   * Get severity indicator classes
   */
  const getSeverityDotClasses = (severity: string): string => {
    if (['High', 'Critical', 'Urgent'].includes(severity)) {
      return 'bg-red-500';
    } else if (['Normal', 'Medium'].includes(severity)) {
      return 'bg-yellow-500';
    } else if (['Low', 'Info'].includes(severity)) {
      return 'bg-blue-500';
    }
    return 'bg-gray-500';
  };

  /**
   * Navigate to ticket detail based on category
   */
  const viewTicket = (id: number, category: string) => {
    if (category === 'Technical') {
      navigate(`/ticket-detail?id=${id}`);
    } else {
      navigate(`/billops-ticket-detail?id=${id}`);
    }
  };

  /**
   * Set Topbar Actions
   */
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-6">
        {/* Page Title & Icon */}
        <div className="hidden lg:flex items-center gap-3 pr-6 border-r border-slate-200/50">
          <div className="w-9 h-9 flex items-center justify-center rounded-xl bg-orange-50 text-orange-600 shadow-sm border border-orange-100/50">
            <i className="fas fa-user-shield"></i>
          </div>
          <div>
            <h1 className="text-sm font-bold text-slate-900 leading-none">Admin Tickets</h1>
            <p className="text-[10px] text-slate-500 font-medium mt-1">Global Ticket Management</p>
          </div>
        </div>

        {/* Global Search Interface */}
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

        {/* Action Group */}
        <div className="flex items-center gap-3">
          {lastUpdatedDisplay && (
            <div className="hidden xl:flex items-center gap-2 px-3 py-1.5 bg-slate-50 rounded-lg border border-slate-100/50 text-[10px] font-bold text-slate-400 uppercase tracking-widest leading-none">
              <i className="fas fa-history opacity-70"></i>
              Synced {lastUpdatedDisplay}
            </div>
          )}

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
  }, [setActions, clearActions, search, setSearch, lastUpdatedDisplay, loading, isUpdating, fetchTickets]);

  /**
   * Render loading skeleton
   */
  const renderLoadingSkeleton = () => (
    <div className="bg-white/70 backdrop-blur-xl rounded-[2rem] shadow-xl shadow-slate-200/50 border border-white overflow-hidden">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-slate-100">
          <thead className="bg-slate-50/50">
            <tr>
              {['ID', 'Account ID', 'Subject', 'Service', 'Status', 'Severity', 'Created', 'Action'].map((header) => (
                <th
                  key={header}
                  className="px-6 py-4 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em]"
                >
                  {header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="bg-white/30 divide-y divide-slate-50">
            {[1, 2, 3, 4, 5, 6].map(i => (
              <tr key={i}>
                <td className="px-6 py-5"><div className="h-4 bg-slate-100 rounded-full w-12 animate-pulse"></div></td>
                <td className="px-6 py-5"><div className="h-4 bg-slate-100 rounded-full w-20 animate-pulse"></div></td>
                <td className="px-6 py-5">
                  <div className="h-4 bg-slate-100 rounded-full w-40 mb-2 animate-pulse"></div>
                  <div className="h-3 bg-slate-50 rounded-full w-24 animate-pulse"></div>
                </td>
                <td className="px-6 py-5"><div className="h-5 bg-slate-100 rounded-lg w-20 animate-pulse"></div></td>
                <td className="px-6 py-5"><div className="h-6 bg-slate-100 rounded-full w-16 animate-pulse"></div></td>
                <td className="px-6 py-5"><div className="h-4 bg-slate-100 rounded-full w-16 animate-pulse"></div></td>
                <td className="px-6 py-5"><div className="h-4 bg-slate-100 rounded-full w-20 animate-pulse"></div></td>
                <td className="px-6 py-5"><div className="h-8 w-8 bg-slate-50 rounded-xl animate-pulse"></div></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );

  /**
   * Render empty state
   */
  const renderEmptyState = () => (
    <tr>
      <td colSpan={8} className="px-6 py-12 text-center text-gray-500 bg-gray-50">
        <div className="flex flex-col items-center">
          <i className="fas fa-inbox text-4xl mb-3 text-gray-300"></i>
          <p className="text-base font-medium">No tickets found</p>
          <p className="text-sm mt-1">
            There are no tickets in this category matching your search.
          </p>
        </div>
      </td>
    </tr>
  );

  /**
   * Render ticket row
   */
  const renderTicketRow = (ticket: AdminTicket) => (
    <tr
      key={ticket.id}
      className="hover:bg-indigo-50/30 transition-all cursor-pointer group border-b border-transparent hover:border-indigo-100"
      onClick={() => viewTicket(ticket.id, ticket.category)}
    >
      {/* ID */}
      <td className="px-6 py-5 whitespace-nowrap">
        <span className="text-xs font-bold text-slate-400 font-mono tracking-tighter opacity-60">#{ticket.id}</span>
      </td>

      {/* Account ID */}
      <td className="px-6 py-5 whitespace-nowrap">
        <span className="px-2 py-1 bg-slate-100 text-slate-600 rounded-lg text-[10px] font-bold font-mono tracking-tight border border-slate-200/50">
          {ticket.accountId || 'GLOBAL'}
        </span>
      </td>

      {/* Subject & Description */}
      <td className="px-6 py-5">
        <div className="text-sm font-bold text-slate-800 group-hover:text-indigo-600 transition-colors leading-tight mb-1">
          {ticket.subject}
        </div>
        <div className="text-[10px] text-slate-400 font-medium truncate max-w-xs opacity-80">
          {ticket.description}
        </div>
      </td>

      {/* Service */}
      <td className="px-6 py-5 whitespace-nowrap">
        <span className="inline-flex items-center px-2.5 py-1 rounded-lg bg-indigo-50 text-indigo-700 text-[10px] font-black border border-indigo-100/50 tracking-tight">
          {ticket.service}
        </span>
      </td>

      {/* Status */}
      <td className="px-6 py-5 whitespace-nowrap">
        <span
          className={`flex items-center gap-2 px-3 py-1 text-[10px] font-black rounded-full shadow-sm ring-1 ring-inset ${getStatusBadgeClasses(ticket.status)}`}
        >
          <span className={`w-1.5 h-1.5 rounded-full bg-white shadow-[0_0_8px_white] ${ticket.status === 'OPEN' ? 'animate-pulse' : ''}`}></span>
          {ticket.status}
        </span>
      </td>

      {/* Severity */}
      <td className="px-6 py-5 whitespace-nowrap">
        <div className="flex items-center gap-2">
          <span
            className={`w-2 h-2 rounded-full ${getSeverityDotClasses(ticket.severity)} shadow-[0_0_8px_currentcolor]`}
            style={{ color: getSeverityColor(ticket.severity) }}
          ></span>
          <span className="text-[10px] font-bold text-slate-600 uppercase tracking-widest">{ticket.severity}</span>
        </div>
      </td>

      {/* Created Date */}
      <td className="px-6 py-5 whitespace-nowrap">
        <span className="text-[10px] font-bold text-slate-400 uppercase tracking-tighter">
          {formatDate(ticket.createdAt)}
        </span>
      </td>

      {/* Action */}
      <td className="px-6 py-5 whitespace-nowrap text-sm font-medium">
        <button
          onClick={(e) => {
            e.stopPropagation();
            viewTicket(ticket.id, ticket.category);
          }}
          className="text-slate-400 hover:text-indigo-600 bg-slate-100 hover:bg-indigo-100 transition-all w-9 h-9 flex items-center justify-center rounded-xl border border-transparent hover:border-indigo-200 shadow-sm group-hover:scale-110 active:scale-95"
        >
          <i className="fas fa-chevron-right text-xs"></i>
        </button>
      </td>
    </tr>
  );

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      {/* Category Summary Header (Mobile Only) */}
      <div className="px-6 py-3 flex gap-3 bg-white/70 backdrop-blur-xl border-b border-slate-200/50 xl:hidden">
        {ADMIN_TICKET_TABS.map(tab => (
          <div key={tab.id} className="flex-1 p-2 bg-slate-50 rounded-xl border border-slate-100">
            <div className="text-[8px] font-black text-slate-400 uppercase tracking-widest mb-1">{tab.label}</div>
            <div className="text-xs font-black text-slate-700">{getCategoryCount(tab.id)}</div>
          </div>
        ))}
      </div>

      {/* Main Content Area */}
      <div className="flex-1 overflow-x-hidden overflow-y-auto custom-scrollbar p-6 lg:p-8">
        <div className="max-w-7xl mx-auto space-y-8">
          {/* Tabs Navigation */}
          <div className="flex items-center justify-between border-b border-slate-200/50 pb-2">
            <nav className="flex space-x-2" aria-label="Tabs">
              {ADMIN_TICKET_TABS.map((tab) => (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`relative py-3 px-6 rounded-2xl text-[11px] font-black uppercase tracking-widest flex items-center gap-2.5 transition-all ${activeTab === tab.id
                    ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-500/20'
                    : 'text-slate-500 hover:text-slate-800 hover:bg-slate-100'
                    }`}
                >
                  <i className={`${tab.icon} ${activeTab === tab.id ? 'opacity-100' : 'opacity-40'}`}></i>
                  {tab.label}
                  <span className={`flex items-center justify-center px-2 py-0.5 rounded-full text-[9px] min-w-[20px] shadow-sm ${activeTab === tab.id
                    ? 'bg-white/20 text-white'
                    : 'bg-slate-200 text-slate-600'
                    }`}>
                    {getCategoryCount(tab.id)}
                  </span>
                  {activeTab === tab.id && (
                    <span className="absolute -bottom-2.5 left-1/2 -translate-x-1/2 w-1.5 h-1.5 bg-indigo-600 rounded-full shadow-[0_0_8px_rgba(79,70,229,0.8)] animate-pulse"></span>
                  )}
                </button>
              ))}
            </nav>
          </div>

          {/* Loading State */}
          {loading && renderLoadingSkeleton()}

          {/* Error State */}
          {error && !hasData && (
            <div className="bg-red-50 border-l-4 border-red-500 p-4 mb-6 rounded shadow-sm">
              <div className="flex items-center">
                <div className="flex-shrink-0">
                  <i className="fas fa-exclamation-circle text-red-500"></i>
                </div>
                <div className="ml-3">
                  <p className="text-sm text-red-700 font-medium">Error loading data</p>
                  <p className="text-sm text-red-600">{error}</p>
                  <button
                    onClick={() => fetchTickets(true)}
                    className="mt-2 text-sm font-medium text-red-700 underline hover:text-red-600"
                  >
                    Try Again
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Table Content */}
          {!loading && (
            <div className="bg-white/70 backdrop-blur-xl rounded-[2.5rem] shadow-[0_8px_40px_rgba(0,0,0,0.03)] border border-white overflow-hidden">
              <div className="overflow-x-auto custom-scrollbar">
                <table className="min-w-full divide-y divide-slate-100/50">
                  <thead className="bg-slate-50/50">
                    <tr>
                      <th className="px-6 py-5 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">ID</th>
                      <th className="px-6 py-5 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Account ID</th>
                      <th className="px-6 py-5 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Subject</th>
                      <th className="px-6 py-5 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Service</th>
                      <th className="px-6 py-5 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Status</th>
                      <th className="px-6 py-5 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Severity</th>
                      <th className="px-6 py-5 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Created</th>
                      <th className="px-6 py-5 text-left text-[10px] font-black text-slate-400 uppercase tracking-[0.2em] whitespace-nowrap">Action</th>
                    </tr>
                  </thead>
                  <tbody className="bg-white/20 divide-y divide-slate-50">
                    {filteredTickets.length > 0
                      ? filteredTickets.map((ticket) => renderTicketRow(ticket))
                      : renderEmptyState()}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default BillOpsAdminTicketsPage;
