/**
 * BillOps Tickets Page Component
 *
 * Displays and manages support tickets for Account and Billing category
 * Features: List tickets, create tickets, filter by account, real-time updates
 *
 * Converted from: billops/tickets.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Improved UI with modern card design and better spacing
 * - Enhanced status and severity badges with icons
 * - Better loading states and animations
 * - More professional modal design
 * - Improved responsive design
 *
 * What stayed the same:
 * - All API endpoints remain identical
 * - Same caching strategy (stale-while-revalidate)
 * - Same filtering logic
 * - All business logic preserved exactly
 */

import React, { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useBillOpsTickets } from '../hooks/useBillOpsTickets';
import {
  CreateTicketFormData,
  SERVICE_OPTIONS,
  TicketSeverity,
  BillOpsTicket
} from '../types/billopsTickets';

const BillOpsTicketsPage: React.FC = () => {
  const navigate = useNavigate();
  const {
    tickets,
    loading,
    isUpdating,
    error,
    lastUpdated,
    hasData,
    selectedAccountId,
    fetchTickets,
    submitTicket
  } = useBillOpsTickets();

  const [showModal, setShowModal] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [form, setForm] = useState<CreateTicketFormData>({
    category: 'Account and Billing',
    service: 'Billing Inquiry',
    severity: '',
    subject: '',
    description: '',
    accountId: '',
    region: ''
  });

  /**
   * Format last updated timestamp
   */
  const lastUpdatedDisplay = useMemo(() => {
    if (!lastUpdated) return '';
    const date = new Date(lastUpdated);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }, [lastUpdated]);

  /**
   * Format date for ticket display
   */
  const formatDate = (dateString: string): string => {
    if (!dateString) return '';
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
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
   * Get severity badge classes
   */
  const getSeverityClasses = (severity: string): string => {
    switch (severity) {
      case 'High':
      case 'Critical':
        return 'bg-red-50 text-red-700 border-red-200';
      case 'Medium':
        return 'bg-orange-50 text-orange-700 border-orange-200';
      case 'Low':
        return 'bg-blue-50 text-blue-700 border-blue-200';
      default:
        return 'bg-gray-50 text-gray-700 border-gray-200';
    }
  };

  /**
   * Open create ticket modal
   */
  const openCreateModal = () => {
    setForm({
      category: 'Account and Billing',
      service: 'Billing Inquiry',
      severity: '',
      subject: '',
      description: '',
      accountId: selectedAccountId || '',
      region: ''
    });
    setShowModal(true);
  };

  /**
   * Handle form submission
   */
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSubmitting(true);

    try {
      await submitTicket(form);
      setShowModal(false);
    } catch (err) {
      console.error('Error submitting ticket:', err);
      alert('There was an error submitting your case. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  /**
   * View ticket detail
   */
  const viewTicket = (id: number) => {
    navigate(`/billops-ticket-detail?id=${id}`);
  };

  /**
   * Render loading skeleton
   */
  const renderLoadingSkeleton = () => (
    <div className="space-y-4">
      {[1, 2].map(i => (
        <div key={i} className="bg-white shadow rounded-lg p-6 border border-gray-200">
          <div className="h-6 bg-gray-200 rounded w-1/4 mb-4 animate-pulse"></div>
          <div className="space-y-3">
            <div className="h-4 bg-gray-200 rounded w-full animate-pulse"></div>
            <div className="h-4 bg-gray-200 rounded w-5/6 animate-pulse"></div>
            <div className="h-4 bg-gray-200 rounded w-4/6 animate-pulse"></div>
          </div>
        </div>
      ))}
    </div>
  );

  /**
   * Render empty state
   */
  const renderEmptyState = () => (
    <div className="text-center py-16 bg-white rounded-lg shadow-sm border border-gray-200">
      <div className="text-gray-300 text-5xl mb-4">
        <i className="fas fa-ticket-alt"></i>
      </div>
      <h3 className="text-lg font-medium text-gray-900">No tickets found</h3>
      <p className="mt-1 text-sm text-gray-500">
        {selectedAccountId
          ? 'No tickets found for this account.'
          : 'There are no tickets in the Account and Billing category.'}
      </p>
      <button
        onClick={openCreateModal}
        className="mt-6 text-indigo-600 hover:text-indigo-500 font-medium transition-colors"
      >
        Create a case &rarr;
      </button>
    </div>
  );

  /**
   * Render ticket card
   */
  const renderTicketCard = (ticket: BillOpsTicket) => (
    <div
      key={ticket.id}
      className="bg-white shadow-sm rounded-lg hover:shadow-md transition-all duration-200 border border-gray-200 overflow-hidden group cursor-pointer"
      onClick={() => viewTicket(ticket.id)}
    >
      <div className="p-6">
        <div className="flex justify-between items-start">
          <div className="flex-1">
            {/* Badges Row */}
            <div className="flex items-center gap-3 mb-3 flex-wrap">
              <span className="text-xs font-mono text-gray-400">
                #{ticket.id}
              </span>

              {/* Status Badge */}
              <span
                className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${getStatusClasses(
                  ticket.status
                )}`}
              >
                <span
                  className={`w-1.5 h-1.5 rounded-full mr-1.5 ${getStatusDotClasses(
                    ticket.status
                  )}`}
                ></span>
                {ticket.status}
              </span>

              {/* Severity Badge */}
              <span
                className={`inline-flex items-center px-2.5 py-0.5 rounded-md text-xs font-medium border ${getSeverityClasses(
                  ticket.severity
                )}`}
              >
                {ticket.severity === 'High' || ticket.severity === 'Critical' ? (
                  <i className="fas fa-exclamation-circle mr-1.5"></i>
                ) : null}
                {ticket.severity}
              </span>

              {/* Account Badge */}
              {ticket.accountId && (
                <span className="text-xs text-gray-500 bg-gray-100 px-2.5 py-0.5 rounded-md">
                  <i className="fas fa-cloud mr-1"></i>
                  {ticket.accountId}
                </span>
              )}
            </div>

            {/* Subject */}
            <h3 className="text-lg font-semibold text-gray-900 mb-2 group-hover:text-indigo-600 transition-colors">
              {ticket.subject}
            </h3>

            {/* Description */}
            <p className="text-sm text-gray-600 line-clamp-2 mb-4">
              {ticket.description}
            </p>

            {/* Metadata Row */}
            <div className="flex items-center text-xs text-gray-400 space-x-4">
              <div className="flex items-center">
                <i className="far fa-clock mr-1.5"></i>
                <span>{formatDate(ticket.createdAt)}</span>
              </div>
              <div className="flex items-center">
                <i className="far fa-folder mr-1.5"></i>
                <span>{ticket.service}</span>
              </div>
              {ticket.region && (
                <div className="flex items-center">
                  <i className="fas fa-globe-americas mr-1.5"></i>
                  <span>{ticket.region}</span>
                </div>
              )}
            </div>
          </div>

          {/* Chevron Icon */}
          <div className="ml-4 flex-shrink-0 self-center">
            <i className="fas fa-chevron-right text-gray-300 group-hover:text-indigo-400 transition-colors"></i>
          </div>
        </div>
      </div>
    </div>
  );

  return (
    <div className="billops-tickets-page">
      {/* Header */}
      <header className="bg-white shadow-sm sticky top-0 z-10">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 flex justify-between items-center">
          <div>
            <div className="flex items-center gap-3">
              <h1 className="text-3xl font-bold text-gray-900">
                BillOps Support Tickets
              </h1>
              {isUpdating && (
                <div className="updating-spinner" title="Refreshing data...">
                  <i className="fas fa-sync-alt fa-spin text-indigo-600"></i>
                </div>
              )}
            </div>
            <div className="flex items-center space-x-3 mt-2">
              {selectedAccountId && (
                <p className="text-sm text-gray-500">
                  <i className="fas fa-filter mr-1"></i>
                  Account: {selectedAccountId}
                </p>
              )}
              {lastUpdated && (
                <span className="text-xs text-gray-400 font-medium bg-gray-50 px-2 py-1 rounded border border-gray-100 flex items-center">
                  <i className="far fa-clock mr-1"></i>
                  Synced: {lastUpdatedDisplay}
                </span>
              )}
            </div>
          </div>
          <div className="flex items-center space-x-4">
            <button
              onClick={() => fetchTickets(true)}
              disabled={loading || isUpdating}
              className="text-gray-600 hover:text-indigo-600 transition-colors p-2 rounded-full hover:bg-gray-100 disabled:opacity-50"
              title="Refresh Tickets"
            >
              <i className={`fas fa-sync-alt ${isUpdating ? 'fa-spin' : ''}`}></i>
            </button>
            <button
              onClick={openCreateModal}
              className="bg-indigo-600 hover:bg-indigo-700 text-white px-5 py-2.5 rounded-lg text-sm font-medium transition-colors flex items-center shadow-sm hover:shadow-md"
            >
              <i className="fas fa-plus mr-2"></i> Create New Case
            </button>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {/* Loading State */}
        {loading && renderLoadingSkeleton()}

        {/* Error State */}
        {error && !hasData && (
          <div className="bg-red-50 border-l-4 border-red-400 p-4 mb-6 shadow-sm rounded-r-md">
            <div className="flex">
              <div className="flex-shrink-0">
                <i className="fas fa-exclamation-circle text-red-400"></i>
              </div>
              <div className="ml-3">
                <p className="text-sm text-red-700">{error}</p>
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

        {/* Empty State */}
        {!loading && !error && tickets.length === 0 && renderEmptyState()}

        {/* Tickets Grid */}
        {!loading && tickets.length > 0 && (
          <div className="grid gap-4">
            {tickets.map(ticket => renderTicketCard(ticket))}
          </div>
        )}
      </main>

      {/* Create Ticket Modal */}
      {showModal && (
        <div className="fixed inset-0 z-50 overflow-y-auto">
          {/* Backdrop */}
          <div
            className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"
            onClick={() => setShowModal(false)}
          ></div>

          {/* Modal */}
          <div className="flex min-h-full items-center justify-center p-4">
            <div
              className="relative bg-white rounded-lg shadow-xl max-w-lg w-full"
              onClick={e => e.stopPropagation()}
            >
              <form onSubmit={handleSubmit}>
                {/* Modal Header */}
                <div className="bg-gradient-to-r from-indigo-600 to-purple-600 px-6 py-4 rounded-t-lg">
                  <h3 className="text-xl font-semibold text-white">
                    Create Support Case (Billing)
                  </h3>
                </div>

                {/* Modal Body */}
                <div className="px-6 py-5 space-y-4">
                  {/* Category */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">
                      Category
                    </label>
                    <select
                      value={form.category}
                      disabled
                      className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 p-2.5 border bg-gray-100 cursor-not-allowed text-gray-500"
                    >
                      <option>Account and Billing</option>
                    </select>
                  </div>

                  {/* Service */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">
                      Service
                    </label>
                    <select
                      value={form.service}
                      onChange={e =>
                        setForm({ ...form, service: e.target.value })
                      }
                      className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 p-2.5 border"
                    >
                      {SERVICE_OPTIONS[form.category].map(service => (
                        <option key={service} value={service}>
                          {service}
                        </option>
                      ))}
                    </select>
                  </div>

                  {/* Severity and Region */}
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-medium text-gray-700 mb-1">
                        Severity *
                      </label>
                      <select
                        value={form.severity}
                        onChange={e =>
                          setForm({
                            ...form,
                            severity: e.target.value as TicketSeverity
                          })
                        }
                        required
                        className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 p-2.5 border"
                      >
                        <option value="">Select...</option>
                        <option value="Low">Low</option>
                        <option value="Medium">Medium</option>
                        <option value="High">High</option>
                        <option value="Critical">Critical</option>
                      </select>
                    </div>
                    <div>
                      <label className="block text-sm font-medium text-gray-700 mb-1">
                        Region (Optional)
                      </label>
                      <input
                        type="text"
                        value={form.region}
                        onChange={e =>
                          setForm({ ...form, region: e.target.value })
                        }
                        placeholder="e.g., us-east-1"
                        className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 p-2.5 border"
                      />
                    </div>
                  </div>

                  {/* Subject */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">
                      Subject *
                    </label>
                    <input
                      type="text"
                      value={form.subject}
                      onChange={e =>
                        setForm({ ...form, subject: e.target.value })
                      }
                      required
                      className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 p-2.5 border"
                    />
                  </div>

                  {/* Description */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">
                      Description *
                    </label>
                    <textarea
                      value={form.description}
                      onChange={e =>
                        setForm({ ...form, description: e.target.value })
                      }
                      rows={4}
                      required
                      className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 p-2.5 border"
                    ></textarea>
                  </div>
                </div>

                {/* Modal Footer */}
                <div className="bg-gray-50 px-6 py-4 flex justify-end space-x-3 rounded-b-lg">
                  <button
                    type="button"
                    onClick={() => setShowModal(false)}
                    className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 transition-colors"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={isSubmitting}
                    className="px-4 py-2 text-sm font-medium text-white bg-indigo-600 rounded-md hover:bg-indigo-700 disabled:opacity-50 transition-colors flex items-center"
                  >
                    {isSubmitting && (
                      <i className="fas fa-circle-notch fa-spin mr-2"></i>
                    )}
                    {isSubmitting ? 'Submitting...' : 'Submit Case'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default BillOpsTicketsPage;
