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
 * - Improved UI with modern table design and better spacing
 * - Enhanced status and severity badges with animations
 * - Better loading states with skeleton loaders
 * - More professional tab design with counts
 * - Improved responsive design
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
import { useTopbar } from '../contexts/TopbarContext';
import {
  AdminTicket,
  AdminTicketTab,
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

  const { setActions, clearActions } = useTopbar();

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
   * Get status badge classes
   */
  const getStatusClasses = (status: string): string => {
    switch (status) {
      case 'OPEN':
        return 'bg-green-100 text-green-800';
      case 'CLOSED':
        return 'bg-gray-100 text-gray-800';
      case 'IN_PROGRESS':
        return 'bg-yellow-100 text-yellow-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  /**
   * Get status dot classes
   */
  const getStatusDotClasses = (status: string): string => {
    switch (status) {
      case 'OPEN':
        return 'bg-green-400';
      case 'CLOSED':
        return 'bg-gray-400';
      case 'IN_PROGRESS':
        return 'bg-yellow-400';
      default:
        return 'bg-gray-400';
    }
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
      // Technical tickets go to xamops ticket detail
      window.location.href = `/xamops_ticket_detail.html?id=${id}`;
    } else if (category === 'Account and Billing') {
      // Billing tickets go to React billops ticket detail
      navigate(`/billops-ticket-detail?id=${id}`);
    } else {
      // Default to xamops ticket detail
      window.location.href = `/xamops_ticket_detail.html?id=${id}`;
    }
  };

  /**
   * Set Topbar Actions
   */
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {/* Search Input in Topbar */}
        <div className="relative group hidden md:block">
          <span className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
            <i className="fas fa-search text-slate-400 group-focus-within:text-indigo-500 transition-colors"></i>
          </span>
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search tickets..."
            className="pl-10 pr-4 py-2 bg-slate-50 border border-slate-200 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500 focus:bg-white focus:border-indigo-500 w-64 transition-all"
          />
        </div>

        {/* Sync Info */}
        {lastUpdatedDisplay && (
          <div className="text-[10px] text-slate-400 font-bold uppercase tracking-widest hidden lg:block">
            Synced {lastUpdatedDisplay}
          </div>
        )}

        {/* Refresh Button */}
        <button
          onClick={() => fetchTickets(true)}
          disabled={loading || isUpdating}
          className="w-10 h-10 flex items-center justify-center text-slate-500 hover:text-indigo-600 hover:bg-slate-100 rounded-xl transition-all disabled:opacity-50"
          title="Refresh Data"
        >
          <i className={`fas fa-sync-alt ${isUpdating ? 'animate-spin' : ''}`}></i>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, search, setSearch, lastUpdatedDisplay, loading, isUpdating, fetchTickets]);

  /**
   * Render loading skeleton
   */
  const renderLoadingSkeleton = () => (
    <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              {['ID', 'Account ID', 'Subject', 'Service', 'Status', 'Severity', 'Created', 'Action'].map((header) => (
                <th
                  key={header}
                  className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider"
                >
                  {header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-200">
            {[1, 2, 3, 4, 5].map(i => (
              <tr key={i}>
                <td className="px-6 py-4"><div className="h-4 bg-gray-200 rounded w-16 animate-pulse"></div></td>
                <td className="px-6 py-4"><div className="h-4 bg-gray-200 rounded w-24 animate-pulse"></div></td>
                <td className="px-6 py-4">
                  <div className="h-4 bg-gray-200 rounded w-48 mb-2 animate-pulse"></div>
                  <div className="h-3 bg-gray-200 rounded w-32 animate-pulse"></div>
                </td>
                <td className="px-6 py-4"><div className="h-4 bg-gray-200 rounded w-20 animate-pulse"></div></td>
                <td className="px-6 py-4"><div className="h-4 bg-gray-200 rounded w-16 animate-pulse"></div></td>
                <td className="px-6 py-4"><div className="h-4 bg-gray-200 rounded w-16 animate-pulse"></div></td>
                <td className="px-6 py-4"><div className="h-4 bg-gray-200 rounded w-20 animate-pulse"></div></td>
                <td className="px-6 py-4"><div className="h-8 w-8 bg-gray-200 rounded-full animate-pulse"></div></td>
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
      className="hover:bg-gray-50 transition-colors cursor-pointer group"
      onClick={() => viewTicket(ticket.id, ticket.category)}
    >
      {/* ID */}
      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500 font-mono">
        #{ticket.id}
      </td>

      {/* Account ID */}
      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600 font-mono">
        {ticket.accountId || '-'}
      </td>

      {/* Subject & Description */}
      <td className="px-6 py-4">
        <div className="text-sm font-medium text-gray-900 group-hover:text-indigo-600 transition-colors">
          {ticket.subject}
        </div>
        <div className="text-xs text-gray-500 truncate max-w-xs">
          {ticket.description}
        </div>
      </td>

      {/* Service */}
      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
        <span className="inline-flex items-center px-2.5 py-0.5 rounded bg-gray-100 text-gray-800 text-xs font-medium">
          {ticket.service}
        </span>
      </td>

      {/* Status */}
      <td className="px-6 py-4 whitespace-nowrap">
        <span
          className={`px-2 inline-flex items-center text-xs leading-5 font-semibold rounded-full ${getStatusClasses(ticket.status)}`}
        >
          <span
            className={`w-1.5 h-1.5 rounded-full mr-1.5 ${getStatusDotClasses(ticket.status)}`}
          ></span>
          {ticket.status}
        </span>
      </td>

      {/* Severity */}
      <td className="px-6 py-4 whitespace-nowrap text-sm">
        <div className="flex items-center">
          <span
            className={`w-2 h-2 rounded-full mr-2 ${getSeverityDotClasses(ticket.severity)}`}
          ></span>
          <span>{ticket.severity}</span>
        </div>
      </td>

      {/* Created Date */}
      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
        {formatDate(ticket.createdAt)}
      </td>

      {/* Action */}
      <td className="px-6 py-4 whitespace-nowrap text-sm font-medium">
        <button
          onClick={(e) => {
            e.stopPropagation();
            viewTicket(ticket.id, ticket.category);
          }}
          className="text-indigo-600 hover:text-indigo-900 bg-indigo-50 hover:bg-indigo-100 p-2 rounded-full transition w-8 h-8 flex items-center justify-center"
        >
          <i className="fas fa-arrow-right"></i>
        </button>
      </td>
    </tr>
  );

  return (
    <div className="billops-admin-tickets-page flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      {/* Category Summary (Simplified) */}
      <div className="px-6 py-4 flex gap-4 bg-white/50 border-b border-slate-200/50 xl:hidden">
        {ADMIN_TICKET_TABS.map(tab => (
          <div key={tab.id} className="flex-1 text-center">
            <div className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">{tab.label}</div>
            <div className="text-sm font-bold text-slate-700">{getCategoryCount(tab.id)}</div>
          </div>
        ))}
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-x-hidden overflow-y-auto bg-gray-50 p-6">
        {/* Tabs */}
        <div className="mb-6">
          <div className="border-b border-gray-200">
            <nav className="-mb-px flex space-x-8" aria-label="Tabs">
              {ADMIN_TICKET_TABS.map((tab) => (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`whitespace-nowrap py-4 px-1 border-b-2 font-medium text-sm flex items-center gap-2 transition-colors ${activeTab === tab.id
                      ? 'border-indigo-500 text-indigo-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                    }`}
                >
                  <i className={tab.icon}></i>
                  {tab.label}
                  <span className="bg-gray-100 text-gray-600 py-0.5 px-2.5 rounded-full text-xs ml-1">
                    {getCategoryCount(tab.id)}
                  </span>
                </button>
              ))}
            </nav>
          </div>
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
          <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                      ID
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                      Account ID
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                      Subject
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                      Service
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                      Status
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                      Severity
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                      Created
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                      Action
                    </th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
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
  );
};

export default BillOpsAdminTicketsPage;
