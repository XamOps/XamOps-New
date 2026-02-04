/**
 * Support Tickets Page
 *
 * Technical support tickets management with improved UI
 *
 * Converted from: xamops_tickets.html
 *
 * Features:
 * - Create new technical support cases
 * - View all technical tickets
 * - Filter by account ID
 * - Real-time data fetching with cache
 * - Status tracking (Open, In Progress, Closed)
 * - Professional and modern UI design
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import Skeleton from '../components/Shared/Skeleton';

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

const SERVICE_OPTIONS: Record<string, string[]> = {
  'Account and Billing': ['Billing', 'Pricing', 'Reserved Instances', 'Detailed Billing Report (DBR)'],
  'Technical': ['EC2', 'S3', 'RDS', 'Lambda', 'Other Technical']
};

const SEVERITY_OPTIONS = ['Info', 'Low', 'Normal', 'High', 'Urgent'];

const TicketsPage: React.FC = () => {
  const { userProfile } = useAuth();
  const { selectedAccountIds } = useAccounts();
  const navigate = useNavigate();
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [loading, setLoading] = useState(true);
  const [isUpdating, setIsUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { setActions, clearActions } = useTopbarActions();

  const [form, setForm] = useState<TicketForm>({
    category: 'Technical',
    service: 'EC2',
    severity: '',
    subject: '',
    description: '',
    accountId: selectedAccountIds.length > 0 ? selectedAccountIds[0] : '',
    region: ''
  });

  const CACHE_KEY = 'xamops_technical_tickets_v1';

  /**
   * Load tickets from localStorage cache
   */
  const loadFromCache = useCallback((): boolean => {
    try {
      const cached = localStorage.getItem(CACHE_KEY);
      if (cached) {
        let allTickets = JSON.parse(cached);
        // Filter by selected accounts
        if (selectedAccountIds.length > 0) {
          setTickets(allTickets.filter((t: Ticket) =>
            t.accountId && selectedAccountIds.includes(t.accountId)
          ));
        } else {
          setTickets(allTickets);
        }
        setLoading(false);
        return true;
      }
    } catch (e) {
      console.error('Error loading cache:', e);
    }
    return false;
  }, [selectedAccountIds]);

  /**
   * Fetch tickets from API
   */
  const fetchTickets = useCallback(async (manual = false) => {
    if (manual) {
      console.log('🖱️ Manual refresh triggered');
    }
    setIsUpdating(true);
    setError(null);

    if (tickets.length === 0) {
      setLoading(true);
    }

    try {
      const response = await fetch('/api/xamops/tickets/category/Technical', {
        credentials: 'include'
      });

      if (!response.ok) {
        throw new Error('Failed to fetch tickets');
      }

      let data = await response.json();

      // Save unfiltered data to cache
      localStorage.setItem(CACHE_KEY, JSON.stringify(data));

      // Filter for display based on selected accounts
      if (selectedAccountIds.length > 0) {
        data = data.filter((t: Ticket) =>
          t.accountId && selectedAccountIds.includes(t.accountId)
        );
      }

      // Sort by ID descending (newest first)
      setTickets(data.sort((a: Ticket, b: Ticket) => b.id - a.id));
    } catch (err) {
      console.error('Fetch error:', err);
      if (tickets.length === 0) {
        setError('Could not load support tickets.');
      }
    } finally {
      setLoading(false);
      setIsUpdating(false);
    }
  }, [selectedAccountIds, tickets.length]);

  /**
   * Initialize component and listen for account changes
   */
  useEffect(() => {
    console.log('🔍 Filtering tickets for selected accounts:', selectedAccountIds);

    // Load from cache first
    if (loadFromCache()) {
      console.log('✅ Loaded tickets from cache');
    }

    // Fetch fresh data
    fetchTickets();

    // Listen for account changes from sidebar
    const handleAccountChanged = () => {
      console.log('🔄 Account changed, refreshing tickets...');
      fetchTickets();
    };

    window.addEventListener('account-changed', handleAccountChanged);

    return () => {
      window.removeEventListener('account-changed', handleAccountChanged);
    };
  }, [selectedAccountIds, loadFromCache, fetchTickets]);

  /**
   * Open create modal
   */
  const openCreateModal = () => {
    resetForm();
    setShowModal(true);
  };

  /**
   * Close modal
   */
  const closeModal = () => {
    if (!isSubmitting) {
      setShowModal(false);
    }
  };

  /**
   * Reset form to defaults
   */
  const resetForm = () => {
    setForm({
      category: 'Technical',
      service: SERVICE_OPTIONS['Technical'][0],
      severity: '',
      subject: '',
      description: '',
      accountId: selectedAccountIds.length > 0 ? selectedAccountIds[0] : '',
      region: ''
    });
  };

  /**
   * Handle form field changes
   */
  const handleFormChange = (field: keyof TicketForm, value: string) => {
    setForm(prev => {
      const updated = { ...prev, [field]: value };

      // When category changes, update service to first option
      if (field === 'category') {
        updated.service = SERVICE_OPTIONS[value][0];
      }

      return updated;
    });
  };

  /**
   * Submit new ticket
   */
  const submitTicket = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!form.service || !form.subject.trim() || !form.description.trim() || !form.severity) {
      alert('Please fill in all required fields.');
      return;
    }

    if (!userProfile?.id) {
      alert('Could not identify current user. Please refresh and log in.');
      return;
    }

    setIsSubmitting(true);

    const payload = {
      ...form,
      creatorId: userProfile.id
    };

    try {
      const response = await fetch('/api/xamops/tickets', {
        credentials: 'include',
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        throw new Error('Failed to submit ticket');
      }

      await fetchTickets();
      resetForm();
      setShowModal(false);
    } catch (err) {
      console.error('Error submitting ticket:', err);
      alert('There was an error submitting your case. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  /**
   * Get status badge classes
   */
  const getStatusBadgeClass = (status: string): string => {
    const statusLower = status.toLowerCase();
    if (statusLower === 'open') return 'bg-blue-100 text-blue-800';
    if (statusLower === 'in_progress') return 'bg-yellow-100 text-yellow-800';
    if (statusLower === 'closed') return 'bg-gray-200 text-gray-800';
    return 'bg-gray-100 text-gray-700';
  };

  /**
   * Navigate to ticket detail
   */
  const navigateToDetail = (ticketId: number) => {
    navigate(`/ticket-detail?id=${ticketId}`);
  };

  // Topbar Integration
  useEffect(() => {
    // Only update actions when loading/updating state changes to avoid flickering
    // internal navigation state is handled by the buttons
    const actionsNode = (
      <div className="flex items-center gap-3">
        <button
          onClick={() => fetchTickets(true)}
          disabled={loading || isUpdating}
          className="w-9 h-9 flex items-center justify-center text-slate-500 hover:text-indigo-600 hover:bg-indigo-50 rounded-lg transition-all"
          title="Refresh Tickets"
        >
          <i className={`fas fa-sync-alt ${isUpdating ? 'animate-spin' : ''}`}></i>
        </button>

        <button
          onClick={openCreateModal}
          disabled={loading}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className="fas fa-plus"></i>
          <span>Create Ticket</span>
        </button>
      </div>
    );

    setActions(actionsNode);

    // Cleanup function
    return () => {
      // Ensure actions are cleared when component unmounts
      // This is critical for preventing "stuck" topbar state
      clearActions();
    };
  }, [setActions, clearActions, loading, isUpdating, fetchTickets]); // fetchTickets is cached via useCallback

  return (
    <div className="tickets-page p-8">

      {/* Loading Skeleton */}
      {loading && (
        <div className="tickets-grid">
          {[1, 2, 3].map((i) => (
            <div key={i} className="ticket-card p-6">
              <div className="flex justify-between items-start mb-4">
                <div className="space-y-2 w-2/3">
                  <Skeleton variant="text" height={24} width="80%" />
                  <Skeleton variant="text" height={16} width="40%" />
                </div>
                <Skeleton variant="rounded" height={24} width={80} />
              </div>
              <div className="space-y-2 mb-4">
                <Skeleton variant="text" height={16} width="100%" />
                <Skeleton variant="text" height={16} width="90%" />
              </div>
              <Skeleton variant="text" height={16} width={120} />
            </div>
          ))}
        </div>
      )}

      {/* Tickets List */}
      {!loading && tickets.length > 0 && (
        <div className="tickets-grid">
          {tickets.map((ticket) => (
            <div
              key={ticket.id}
              onClick={() => navigateToDetail(ticket.id)}
              className="ticket-card"
            >
              <div className="ticket-header">
                <div>
                  <div className="flex items-center gap-2 mb-1">
                    <h3 className="ticket-title">{ticket.subject}</h3>
                    {ticket.accountId && (
                      <span className="account-badge">
                        Account: {ticket.accountId}
                      </span>
                    )}
                  </div>
                  <p className="ticket-meta">
                    {ticket.category} | {ticket.service} | Severity: {ticket.severity}
                  </p>
                </div>
                <span className={`status-badge ${getStatusBadgeClass(ticket.status)}`}>
                  {ticket.status.replace('_', ' ')}
                </span>
              </div>

              <p className="ticket-description">
                {ticket.description}
              </p>

              <div className="ticket-footer">
                <i className="fas fa-calendar-alt mr-2 text-gray-400"></i>
                Created on: {new Date(ticket.createdAt).toLocaleString()}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Empty State */}
      {!loading && tickets.length === 0 && !error && (
        <div className="empty-state">
          <i className="fas fa-ticket-alt text-6xl mb-4 text-gray-300"></i>
          <h3 className="text-xl font-semibold text-gray-700 mb-2">No tickets found</h3>
          <p className="text-gray-500">
            {selectedAccountIds.length > 0
              ? `No technical tickets found for the selected account${selectedAccountIds.length > 1 ? 's' : ''}.`
              : 'Please select an account from the sidebar to view tickets.'}
          </p>
        </div>
      )}

      {/* Error State */}
      {error && tickets.length === 0 && (
        <div className="error-state">
          <i className="fas fa-exclamation-circle text-4xl mb-4 text-red-500"></i>
          <p className="text-red-700 font-medium mb-4">{error}</p>
          <button onClick={() => fetchTickets(true)} className="btn-primary">
            <i className="fas fa-sync-alt mr-2"></i>
            Try Refreshing
          </button>
        </div>
      )}

      {/* Create Ticket Modal */}
      {showModal && (
        <div className="modal-overlay" onClick={closeModal}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h2 className="text-2xl font-bold text-gray-900">Create Support Case</h2>
              <button onClick={closeModal} className="modal-close" disabled={isSubmitting}>
                <i className="fas fa-times"></i>
              </button>
            </div>

            <form onSubmit={submitTicket}>
              <div className="modal-body">
                {/* Category */}
                <div className="form-group">
                  <label className="form-label">Category</label>
                  <select
                    value={form.category}
                    onChange={(e) => handleFormChange('category', e.target.value)}
                    className="form-input"
                  >
                    <option>Technical</option>
                  </select>
                </div>

                {/* Service */}
                <div className="form-group">
                  <label className="form-label">Service *</label>
                  <select
                    value={form.service}
                    onChange={(e) => handleFormChange('service', e.target.value)}
                    className="form-input"
                    required
                  >
                    <option disabled value="">Select Service</option>
                    {SERVICE_OPTIONS[form.category].map((option) => (
                      <option key={option} value={option}>{option}</option>
                    ))}
                  </select>
                </div>

                {/* Severity */}
                <div className="form-group">
                  <label className="form-label">Severity *</label>
                  <select
                    value={form.severity}
                    onChange={(e) => handleFormChange('severity', e.target.value)}
                    className="form-input"
                    required
                  >
                    <option disabled value="">Select Severity</option>
                    {SEVERITY_OPTIONS.map((option) => (
                      <option key={option} value={option}>{option}</option>
                    ))}
                  </select>
                </div>

                {/* Subject */}
                <div className="form-group">
                  <label className="form-label">Subject *</label>
                  <input
                    type="text"
                    value={form.subject}
                    onChange={(e) => handleFormChange('subject', e.target.value)}
                    maxLength={250}
                    className="form-input"
                    placeholder="Briefly summarize your question or issue"
                    required
                  />
                </div>

                {/* Description */}
                <div className="form-group">
                  <label className="form-label">Description *</label>
                  <textarea
                    value={form.description}
                    onChange={(e) => handleFormChange('description', e.target.value)}
                    maxLength={8000}
                    rows={4}
                    className="form-input"
                    placeholder="Describe your question or issue in detail"
                    required
                  />
                </div>

                {/* Account ID and Region */}
                <div className="form-row">
                  <div className="form-group">
                    <label className="form-label">AWS Account ID</label>
                    <input
                      type="text"
                      value={form.accountId}
                      onChange={(e) => handleFormChange('accountId', e.target.value)}
                      className="form-input"
                      placeholder="e.g. 1234-5678-9012"
                    />
                  </div>

                  <div className="form-group">
                    <label className="form-label">Region</label>
                    <input
                      type="text"
                      value={form.region}
                      onChange={(e) => handleFormChange('region', e.target.value)}
                      className="form-input"
                      placeholder="e.g. us-east-1"
                    />
                  </div>
                </div>
              </div>

              <div className="modal-footer">
                <button
                  type="button"
                  onClick={closeModal}
                  disabled={isSubmitting}
                  className="btn-secondary"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting}
                  className="btn-primary"
                >
                  {isSubmitting ? (
                    <>
                      <i className="fas fa-circle-notch fa-spin mr-2"></i>
                      Submitting...
                    </>
                  ) : (
                    <>
                      <i className="fas fa-check mr-2"></i>
                      Submit Case
                    </>
                  )}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

export default TicketsPage;
