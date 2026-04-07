/**
 * Support Tickets Page
 *
 * Technical support tickets management with improved UI
 *
 * Converted from: xamops_tickets.html
 *
 * Modernized:
 * - Topbar integration for actions and status
 * - Soft Glass Minimal design system
 * - Premium animations and transitions
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import Skeleton from '../components/Shared/Skeleton';
import { DialogRoot, DialogPanel } from '../components/ui';

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
  'Technical': ['Compute / VMs', 'Storage', 'Networking', 'Database', 'Security & IAM', 'Monitoring & Logs', 'Serverless', 'Other']
};

const SEVERITY_OPTIONS = ['Info', 'Low', 'Normal', 'High', 'Urgent'];

const TicketsPage: React.FC = () => {
  const { userProfile } = useAuth();
  const { selectedAccountIds, accounts } = useAccounts();

  const getAccountName = (accountId?: string): string | null => {
    if (!accountId) return null;
    const acc = accounts.find(a => a.id === accountId);
    return acc ? acc.name : null;
  };
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
      // Fetch from both xamops and billops Technical endpoints in parallel
      const [xamopsRes, billopsRes] = await Promise.allSettled([
        fetch('/api/xamops/tickets/category/Technical', { credentials: 'include' }),
        fetch('/api/billops/tickets/category/Technical', { credentials: 'include' }),
      ]);

      const xamopsData: Ticket[] = xamopsRes.status === 'fulfilled' && xamopsRes.value.ok
        ? await xamopsRes.value.json()
        : [];

      const billopsData: Ticket[] = billopsRes.status === 'fulfilled' && billopsRes.value.ok
        ? (await billopsRes.value.json()).map((t: Ticket) => ({ ...t, _source: 'billops' }))
        : [];

      // Merge: billops tickets that are not already in xamops (by id collision is unlikely across systems)
      // Use a compound key to avoid confusion; keep both sets
      let data: Ticket[] = [...xamopsData, ...billopsData];

      // Save unfiltered data to cache
      localStorage.setItem(CACHE_KEY, JSON.stringify(data));

      // Filter for display based on selected accounts
      if (selectedAccountIds.length > 0) {
        data = data.filter((t: Ticket) =>
          t.accountId && selectedAccountIds.includes(t.accountId)
        );
      }

      // Sort by createdAt descending (newest first)
      setTickets(data.sort((a: Ticket, b: Ticket) => {
        const aTime = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const bTime = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return bTime - aTime;
      }));
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

    globalThis.addEventListener('account-changed', handleAccountChanged);

    return () => {
      globalThis.removeEventListener('account-changed', handleAccountChanged);
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
    if (statusLower === 'open') return 'bg-emerald-500 text-white';
    if (statusLower === 'in_progress') return 'bg-amber-500 text-white';
    if (statusLower === 'closed') return 'bg-gray-400 text-white';
    return 'bg-indigo-500 text-white';
  };

  /**
   * Get severity badge classes
   */
  const getSeverityClass = (severity: string): string => {
    const severityLower = severity.toLowerCase();
    if (severityLower === 'urgent' || severityLower === 'high' || severityLower === 'critical') {
      return 'text-red-600 font-bold';
    }
    if (severityLower === 'normal' || severityLower === 'medium') {
      return 'text-orange-500 font-semibold';
    }
    return 'text-blue-500 font-medium';
  };

  /**
   * Get severity icon
   */
  const getSeverityIcon = (severity: string): string => {
    const severityLower = severity.toLowerCase();
    if (severityLower === 'urgent' || severityLower === 'high' || severityLower === 'critical') {
      return 'fas fa-exclamation-triangle';
    }
    if (severityLower === 'normal' || severityLower === 'medium') {
      return 'fas fa-exclamation-circle';
    }
    return 'fas fa-info-circle';
  };

  /**
   * Navigate to ticket detail
   */
  const navigateToDetail = (ticketId: number) => {
    navigate(`/ticket-detail?id=${ticketId}`);
  };

  // Topbar Integration
  useEffect(() => {
    const openTicketsCount = tickets.filter(t => t.status.toLowerCase() === 'open').length;
    const inProgressCount = tickets.filter(t => t.status.toLowerCase() === 'in_progress').length;

    const actionsNode = (
      <div className="flex items-center gap-6">
        {/* Page Title & Icon (Mobile Hidden) */}
        <div className="hidden lg:flex items-center gap-3 pr-6 border-r border-slate-200/50">
          <div className="w-9 h-9 flex items-center justify-center rounded-xl bg-indigo-50 text-indigo-600 shadow-sm border border-indigo-100/50">
            <i className="fas fa-ticket-alt"></i>
          </div>
          <div>
            <h1 className="text-sm font-bold text-slate-900 leading-none">Support Tickets</h1>
            <p className="text-[10px] text-slate-500 font-medium mt-1">Technical Support Cases</p>
          </div>
        </div>

        {/* Live Stats */}
        {!loading && tickets.length > 0 && (
          <div className="hidden xl:flex items-center gap-4">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
              <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Open</span>
              <span className="text-xs font-bold text-slate-700">{openTicketsCount}</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-amber-500"></span>
              <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">In Progress</span>
              <span className="text-xs font-bold text-slate-700">{inProgressCount}</span>
            </div>
          </div>
        )}

        <div className="flex items-center gap-3">
          <button
            onClick={() => fetchTickets(true)}
            disabled={loading || isUpdating}
            className="w-10 h-10 flex items-center justify-center text-slate-500 hover:text-indigo-600 hover:bg-slate-100 rounded-xl transition-all border border-transparent hover:border-slate-200/50"
            title="Refresh Tickets"
          >
            <i className={`fas fa-sync-alt ${isUpdating ? 'animate-spin' : ''}`}></i>
          </button>

          <button
            onClick={openCreateModal}
            disabled={loading}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 transition-all hover:-translate-y-0.5 active:scale-95 disabled:opacity-50"
          >
            <i className="fas fa-plus"></i>
            <span>Create Ticket</span>
          </button>
        </div>
      </div>
    );

    setActions(actionsNode);

    return () => clearActions();
  }, [setActions, clearActions, loading, isUpdating, fetchTickets, tickets]);

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      {/* Scrollable Content Area */}
      <main className="flex-1 overflow-y-auto custom-scrollbar p-8">
        <div className="space-y-2">

          {/* Loading Skeleton */}
          {loading && (
            <div className="space-y-2">
              {[1, 2, 3, 4].map((i) => (
                <div key={i} className="flex items-center gap-4 px-5 py-4 bg-white border border-slate-200 rounded-2xl">
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

          {/* Tickets List */}
          {!loading && tickets.length > 0 && (
            <div className="space-y-2">
              {tickets.map((ticket) => (
                <div
                  key={ticket.id}
                  onClick={() => navigateToDetail(ticket.id)}
                  className="flex items-center gap-4 px-5 py-4 bg-white border border-slate-200 rounded-2xl hover:border-indigo-300 hover:shadow-sm transition-all duration-200 cursor-pointer group"
                >
                  {/* ID + Subject */}
                  <div className="flex-1 min-w-0">
                    <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Case #{ticket.id}</span>
                    <p className="text-sm font-semibold text-slate-800 group-hover:text-indigo-600 transition-colors truncate leading-snug">
                      {ticket.subject}
                    </p>
                  </div>

                  {/* Account Info */}
                  {ticket.accountId && (
                    <div className="hidden lg:flex flex-col items-end min-w-0 max-w-[160px]">
                      {getAccountName(ticket.accountId) && (
                        <span className="text-xs font-semibold text-slate-700 truncate w-full text-right">
                          {getAccountName(ticket.accountId)}
                        </span>
                      )}
                      <span className="text-[10px] text-slate-400 font-mono truncate w-full text-right">{ticket.accountId}</span>
                    </div>
                  )}

                  {/* Service */}
                  <span className="hidden md:flex items-center gap-1.5 px-2.5 py-1 bg-indigo-50 text-indigo-600 rounded-lg text-[10px] font-bold whitespace-nowrap">
                    <i className="fas fa-cube text-[8px] text-indigo-400"></i>
                    {ticket.service}
                  </span>

                  {/* Severity */}
                  <div className="hidden lg:flex items-center gap-1.5">
                    <i className={`${getSeverityIcon(ticket.severity)} ${getSeverityClass(ticket.severity)} text-xs`}></i>
                    <span className={`text-[10px] font-bold uppercase tracking-wider ${getSeverityClass(ticket.severity)}`}>
                      {ticket.severity}
                    </span>
                  </div>

                  {/* Date */}
                  <span className="hidden xl:block text-[10px] font-medium text-slate-400 whitespace-nowrap">
                    <i className="far fa-calendar-alt mr-1"></i>
                    {new Date(ticket.createdAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
                  </span>

                  {/* Status */}
                  <span className={`flex items-center gap-1.5 px-3 py-1 rounded-full text-[10px] font-bold uppercase tracking-wider shadow-sm whitespace-nowrap ${getStatusBadgeClass(ticket.status)}`}>
                    <span className={`w-1.5 h-1.5 rounded-full bg-white ${ticket.status.toLowerCase() === 'open' ? 'animate-pulse' : ''}`}></span>
                    {ticket.status.replace('_', ' ')}
                  </span>

                  <i className="fas fa-chevron-right text-slate-300 text-xs group-hover:text-indigo-400 transition-colors"></i>
                </div>
              ))}
            </div>
          )}

          {/* Empty State — full-width, outside the space-y-2 list */}
          {!loading && tickets.length === 0 && !error && (
            <div className="max-w-md mx-auto text-center py-20 bg-white/50 backdrop-blur-xl border border-white rounded-[3rem] shadow-xl shadow-indigo-500/5">
              <div className="w-24 h-24 bg-indigo-50 rounded-full flex items-center justify-center mx-auto mb-8 shadow-inner border border-indigo-100/50">
                <i className="fas fa-ticket-alt text-indigo-300 text-4xl"></i>
              </div>
              <h3 className="text-2xl font-black text-slate-800 mb-3 tracking-tight">No Tickets Found</h3>
              <p className="text-slate-500 text-sm px-10 leading-relaxed mb-10">
                {selectedAccountIds.length > 0
                  ? `No technical tickets found for the selected account${selectedAccountIds.length > 1 ? 's' : ''}. Support is just a click away.`
                  : 'Select an account from the sidebar or create a new ticket to get started.'}
              </p>
              <button
                onClick={openCreateModal}
                className="inline-flex items-center gap-3 px-8 py-4 bg-indigo-600 text-white rounded-2xl text-sm font-bold shadow-xl shadow-indigo-500/20 hover:bg-indigo-700 transition-all hover:-translate-y-1 active:scale-95"
              >
                <i className="fas fa-plus"></i>
                Create New Ticket
              </button>
            </div>
          )}
        </div>
      </main>

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
      <DialogRoot isOpen={showModal} onClose={closeModal} maxWidth="max-w-lg">
        <DialogPanel
          title={
            <div className="flex items-center gap-3">
              <i className="fas fa-plus-circle text-indigo-600"></i>
              <div>
                <div className="text-xl font-bold text-gray-900">Create Support Case</div>
                <p className="text-gray-500 text-sm mt-0.5">Submit a new request for technical support</p>
              </div>
            </div>
          }
          onClose={closeModal}
          footer={
            <>
              <button
                type="button"
                onClick={closeModal}
                disabled={isSubmitting}
                className="px-5 py-2.5 text-sm font-medium text-gray-700 bg-white border border-gray-200 rounded-xl hover:bg-gray-50 hover:text-gray-900 transition-colors shadow-sm"
              >
                Cancel
              </button>
              <button
                type="submit"
                form="create-ticket-form"
                disabled={isSubmitting}
                className="px-5 py-2.5 text-sm font-medium text-white bg-indigo-600 rounded-xl hover:bg-indigo-700 disabled:opacity-70 disabled:cursor-not-allowed transition-all shadow-sm hover:shadow hover:translate-y-px flex items-center"
              >
                {isSubmitting ? (
                  <><i className="fas fa-circle-notch fa-spin mr-2"></i>Submitting...</>
                ) : (
                  <><i className="fas fa-check mr-2"></i>Submit Case</>
                )}
              </button>
            </>
          }
        >
          <form id="create-ticket-form" onSubmit={submitTicket}>
            <div className="space-y-5">
                  {/* Category */}
                  <div>
                    <label className="block text-sm font-semibold text-gray-700 mb-2">
                      Category
                    </label>
                    <div className="relative">
                      <select
                        value={form.category}
                        onChange={(e) => handleFormChange('category', e.target.value)}
                        className="w-full rounded-xl border-gray-200 py-2.5 pl-4 pr-10 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 appearance-none bg-white hover:bg-gray-50 transition-colors"
                      >
                        <option>Technical</option>
                      </select>
                      <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-gray-500">
                        <i className="fas fa-lock text-xs"></i>
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
                        onChange={(e) => handleFormChange('service', e.target.value)}
                        className="w-full rounded-xl border-gray-200 py-2.5 pl-4 pr-10 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 appearance-none bg-white hover:bg-gray-50 transition-colors"
                        required
                      >
                        <option disabled value="">Select Service</option>
                        {SERVICE_OPTIONS[form.category].map((option) => (
                          <option key={option} value={option}>{option}</option>
                        ))}
                      </select>
                      <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-gray-500">
                        <i className="fas fa-chevron-down text-xs"></i>
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
                        onChange={(e) => handleFormChange('severity', e.target.value)}
                        className="w-full rounded-xl border-gray-200 py-2.5 pl-4 pr-10 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 appearance-none bg-white hover:bg-gray-50 transition-colors"
                        required
                      >
                        <option disabled value="">Select Severity</option>
                        {SEVERITY_OPTIONS.map((option) => (
                          <option key={option} value={option}>{option}</option>
                        ))}
                      </select>
                      <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-gray-500">
                        <i className="fas fa-chevron-down text-xs"></i>
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
                      onChange={(e) => handleFormChange('subject', e.target.value)}
                      maxLength={250}
                      className="w-full rounded-xl border-gray-200 py-2.5 px-4 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 placeholder-gray-400"
                      placeholder="Briefly summarize your question or issue"
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
                      onChange={(e) => handleFormChange('description', e.target.value)}
                      maxLength={8000}
                      rows={4}
                      className="w-full rounded-xl border-gray-200 py-2.5 px-4 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 placeholder-gray-400 resize-none"
                      placeholder="Describe your question or issue in detail"
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

export default TicketsPage;
