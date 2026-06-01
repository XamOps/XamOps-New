import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useCredits } from '../hooks/useCredits';
import { useTopbar } from '../contexts/TopbarContext';
import { useAccounts } from '../contexts/AccountContext';
import type { CreditRequest, CreditFilter } from '../types/credits';
import { DialogRoot, DialogPanel } from '../components/ui';

const FILTER_TABS: { id: CreditFilter | 'all'; label: string; activeClass: string }[] = [
  { id: 'all', label: 'All Requests', activeClass: 'bg-[rgba(0,0,0,0.88)] text-white' },
  { id: 'submitted', label: 'Submitted', activeClass: 'bg-blue-600 text-white' },
  { id: 'documentation_review', label: 'Documentation', activeClass: 'bg-cyan-600 text-white' },
  { id: 'technical_verification', label: 'Verification', activeClass: 'bg-amber-500 text-white' },
  { id: 'approved', label: 'Approved', activeClass: 'bg-emerald-600 text-white' },
  { id: 'rejected', label: 'Rejected', activeClass: 'bg-red-600 text-white' },
];

const CreditsPage: React.FC = () => {
  const { accounts, isLoading: accountsLoading } = useAccounts();
  const hasAccounts = accounts.length > 0;

  const {
    filteredRequests,
    loading,
    error,
    selectedFilter,
    showForm,
    newRequestData,
    totalRequested,
    pendingCount,
    setShowForm,
    setSelectedFilter,
    setNewRequestData,
    submitRequest,
    resetForm,
  } = useCredits();

  const { setActions, clearActions } = useTopbar();

  const formatCurrency = (value: number): string => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    }).format(value);
  };

  const getStatusDisplayName = (status: string): string => {
    const statusMap: Record<string, string> = {
      submitted: 'Submitted',
      documentation_review: 'Documentation Review',
      technical_verification: 'Technical Verification',
      approved: 'Approved',
      rejected: 'Rejected',
    };
    return statusMap[status] || status;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    await submitRequest();
  };

  const handleCloseForm = () => {
    setShowForm(false);
    resetForm();
  };

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          <div className="text-center bg-white px-3 py-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] hidden sm:block">
            <div className="text-sm font-bold text-indigo-600">{formatCurrency(totalRequested)}</div>
            <div className="text-[10px] text-[rgba(0,0,0,0.4)] font-semibold uppercase tracking-wider">Total Requested</div>
          </div>
          <div className="text-center bg-white px-3 py-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] hidden sm:block">
            <div className="text-sm font-bold text-sky-600">{pendingCount}</div>
            <div className="text-[10px] text-[rgba(0,0,0,0.4)] font-semibold uppercase tracking-wider">Pending</div>
          </div>
        </div>
        <button
          onClick={() => setShowForm(true)}
          className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
        >
          <Icon icon="mdi:plus" width={14} />
          <span>New Request</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, totalRequested, pendingCount]);

  return (
    <main className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen space-y-4">

      {/* Error State */}
      {error && (
        <div className="bg-red-50 border border-red-100 rounded-xl p-4">
          <div className="flex items-center gap-3">
            <Icon icon="mdi:alert-circle-outline" width={18} className="text-red-500 shrink-0" />
            <span className="text-red-700 font-medium text-sm">{error}</span>
          </div>
        </div>
      )}

      {/* Filters */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4 shadow-sm">
        <div className="flex flex-wrap gap-2">
          {FILTER_TABS.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setSelectedFilter(tab.id as CreditFilter)}
              className={`px-3 py-1.5 rounded-lg font-semibold text-xs transition-all duration-150 ${
                selectedFilter === tab.id
                  ? tab.activeClass
                  : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.07)] border border-[rgba(0,0,0,0.06)]'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* No Accounts Empty State */}
      {!accountsLoading && !hasAccounts && (
        <div className="text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
          <div className="w-14 h-14 bg-amber-50 border border-amber-100 rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:power-plug-outline" width={28} className="text-amber-500" />
          </div>
          <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">No Accounts Connected</h3>
          <p className="text-sm text-[rgba(0,0,0,0.5)] max-w-sm mx-auto mb-5">
            Connect a cloud account to manage credit requests.
          </p>
          <a
            href="/account-manager"
            className="inline-flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
          >
            <Icon icon="mdi:plus" width={14} />
            Add Account
          </a>
        </div>
      )}

      {/* Credit Requests List */}
      {(accountsLoading || hasAccounts) && (
        loading ? (
          <div className="space-y-4">
            {[1, 2, 3].map((i) => (
              <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl overflow-hidden shadow-sm animate-pulse">
                <div className="bg-[rgba(0,0,0,0.04)] h-20" />
                <div className="p-6 space-y-3">
                  <div className="h-5 bg-[rgba(0,0,0,0.06)] rounded w-3/4" />
                  <div className="h-4 bg-[rgba(0,0,0,0.06)] rounded w-1/2" />
                </div>
              </div>
            ))}
          </div>
        ) : filteredRequests.length === 0 ? (
          <div className="text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
            <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-3">
              <Icon icon="mdi:inbox-outline" width={28} className="text-[rgba(0,0,0,0.3)]" />
            </div>
            <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">No credit requests found</h3>
            <p className="text-sm text-[rgba(0,0,0,0.5)] max-w-sm mx-auto">
              {selectedFilter === 'all'
                ? 'Click "New Request" to submit your first credit request for cloud cost adjustment.'
                : `No requests currently in ${getStatusDisplayName(selectedFilter)} status.`}
            </p>
          </div>
        ) : (
          <div className="space-y-4">
            {filteredRequests.map((request: CreditRequest) => (
              <CreditRequestCard key={request.id} request={request} />
            ))}
          </div>
        )
      )}

      {/* New Request Form Modal */}
      <DialogRoot isOpen={showForm} onClose={handleCloseForm} maxWidth="max-w-2xl">
        <DialogPanel
          title={
            <div>
              <div className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Request Cloud Credits</div>
              <p className="text-xs text-[rgba(0,0,0,0.4)] mt-0.5">Submit a new request for cloud cost credits</p>
            </div>
          }
          onClose={handleCloseForm}
          footer={
            <>
              <button
                type="button"
                onClick={handleCloseForm}
                className="px-4 py-2 border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.88)] font-medium rounded-md text-sm hover:bg-[rgba(0,0,0,0.04)] transition-colors cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="submit"
                form="credits-request-form"
                className="px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white font-medium rounded-md text-sm hover:bg-black transition-colors cursor-pointer"
              >
                Submit Request
              </button>
            </>
          }
        >
          <div className="overflow-y-auto max-h-[60vh]">
            <form id="credits-request-form" onSubmit={handleSubmit} className="space-y-5">

              {/* Cloud Account ID */}
              <div>
                <label htmlFor="awsAccountId" className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-1.5">
                  Cloud Account ID <span className="text-red-500">*</span>
                </label>
                <input
                  id="awsAccountId"
                  type="text"
                  value={newRequestData.awsAccountId}
                  onChange={(e) => setNewRequestData({ ...newRequestData, awsAccountId: e.target.value })}
                  placeholder="e.g., AWS: 123456789012 | GCP: my-project | Azure: Sub-ID"
                  className="w-full px-3 py-2.5 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.04)] text-sm text-[rgba(0,0,0,0.88)] focus:ring-1 focus:ring-indigo-400 focus:border-indigo-400 outline-none transition-all"
                  required
                />
                <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1.5">Supports AWS Account IDs, GCP Project IDs, and Azure Subscription IDs</p>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {/* Expected Credits */}
                <div>
                  <label htmlFor="expectedCredits" className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-1.5">
                    Expected Credits ($) <span className="text-red-500">*</span>
                  </label>
                  <div className="relative">
                    <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-[rgba(0,0,0,0.4)] font-bold text-sm">
                      $
                    </div>
                    <input
                      id="expectedCredits"
                      type="number"
                      value={newRequestData.expectedCredits || ''}
                      onChange={(e) => setNewRequestData({ ...newRequestData, expectedCredits: e.target.value ? Number.parseFloat(e.target.value) : null })}
                      placeholder="0.00"
                      min="1"
                      step="1"
                      className="w-full pl-7 pr-3 py-2.5 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.04)] text-sm text-[rgba(0,0,0,0.88)] focus:ring-1 focus:ring-indigo-400 focus:border-indigo-400 outline-none transition-all"
                      required
                    />
                  </div>
                </div>

                {/* Priority */}
                <div>
                  <label htmlFor="priority" className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-1.5">
                    Priority Level
                  </label>
                  <select
                    id="priority"
                    value={newRequestData.priority}
                    onChange={(e) => setNewRequestData({ ...newRequestData, priority: e.target.value as 'low' | 'medium' | 'high' })}
                    className="w-full px-3 py-2.5 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.04)] text-sm text-[rgba(0,0,0,0.88)] focus:ring-1 focus:ring-indigo-400 focus:border-indigo-400 outline-none transition-all cursor-pointer"
                  >
                    <option value="low">Low Priority</option>
                    <option value="medium">Medium Priority</option>
                    <option value="high">High Priority</option>
                  </select>
                </div>
              </div>

              {/* Cloud Services */}
              <div>
                <label htmlFor="services" className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-1.5">
                  Cloud Services <span className="text-red-500">*</span>
                </label>
                <textarea
                  id="services"
                  value={newRequestData.services}
                  onChange={(e) => setNewRequestData({ ...newRequestData, services: e.target.value })}
                  placeholder="List the cloud services relevant to this credit request..."
                  rows={3}
                  className="w-full px-3 py-2.5 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.04)] text-sm text-[rgba(0,0,0,0.88)] focus:ring-1 focus:ring-indigo-400 focus:border-indigo-400 outline-none transition-all resize-none"
                  required
                />
              </div>

              {/* Use Case */}
              <div>
                <label htmlFor="useCase" className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-1.5">
                  Project Use Case <span className="text-red-500">*</span>
                </label>
                <textarea
                  id="useCase"
                  value={newRequestData.useCase}
                  onChange={(e) => setNewRequestData({ ...newRequestData, useCase: e.target.value })}
                  placeholder="Describe your project and how you plan to use these cloud credits..."
                  rows={4}
                  className="w-full px-3 py-2.5 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.04)] text-sm text-[rgba(0,0,0,0.88)] focus:ring-1 focus:ring-indigo-400 focus:border-indigo-400 outline-none transition-all resize-none"
                  required
                />
              </div>

            </form>
          </div>
        </DialogPanel>
      </DialogRoot>
    </main>
  );
};

const PRIORITY_BADGE: Record<string, string> = {
  high: 'bg-red-100 text-red-600',
  medium: 'bg-amber-100 text-amber-600',
  low: 'bg-indigo-100 text-indigo-600',
};

const CreditRequestCard: React.FC<{ request: CreditRequest }> = ({ request }) => {
  const [isExpanded, setIsExpanded] = React.useState(false);

  const formatCurrency = (value: number): string => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    }).format(value);
  };

  const formatDate = (dateString: string): string => {
    return new Date(dateString).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  };

  const isStageCompleted = (stage: string): boolean => {
    const stages = ['submitted', 'documentation_review', 'technical_verification', 'approved'];
    const currentIndex = stages.indexOf(request.status);
    const stageIndex = stages.indexOf(stage);
    return currentIndex >= stageIndex || request.status === 'rejected';
  };

  const isStageActive = (stage: string): boolean => request.status === stage;

  const getStatusIcon = (): string => {
    if (request.status === 'approved') return 'mdi:check-circle-outline';
    if (request.status === 'rejected') return 'mdi:close-circle-outline';
    return 'mdi:file-document-multiple-outline';
  };

  const getStatusThemeClass = (): string => {
    if (request.status === 'approved') return 'bg-emerald-100 text-emerald-600';
    if (request.status === 'rejected') return 'bg-red-100 text-red-600';
    return 'bg-indigo-100 text-indigo-600';
  };

  return (
    <div className="bg-white rounded-xl border border-[rgba(0,0,0,0.12)] shadow-sm overflow-hidden hover:shadow-md transition-shadow duration-200">

      {/* Card Header */}
      <button
        type="button"
        className="w-full text-left p-5 cursor-pointer hover:bg-[rgba(0,0,0,0.02)] transition-colors focus:outline-none"
        onClick={() => setIsExpanded(!isExpanded)}
        aria-expanded={isExpanded}
      >
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div className="flex items-start gap-3">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${getStatusThemeClass()}`}>
              <Icon icon={getStatusIcon()} width={20} />
            </div>
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <h4 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{request.awsAccountId}</h4>
                <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider ${PRIORITY_BADGE[request.priority] ?? 'bg-gray-100 text-gray-500'}`}>
                  {request.priority}
                </span>
              </div>
              <div className="flex items-center gap-3 mt-0.5 text-xs text-[rgba(0,0,0,0.4)] font-medium">
                <span>ID: <span className="text-[rgba(0,0,0,0.7)] font-bold">#{request.id}</span></span>
                <span>Submitted: <span className="text-[rgba(0,0,0,0.7)] font-bold">{formatDate(request.submittedDate)}</span></span>
              </div>
            </div>
          </div>

          <div className="flex items-center justify-between md:justify-end gap-6 border-t md:border-t-0 border-[rgba(0,0,0,0.06)] pt-3 md:pt-0">
            <div className="text-right">
              <div className="text-xl font-bold text-[rgba(0,0,0,0.88)] font-mono tracking-tight">
                {formatCurrency(request.expectedCredits)}
              </div>
              <div className="text-[10px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest mt-0.5">Requested Credit</div>
            </div>
            <div className={`w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] flex items-center justify-center text-[rgba(0,0,0,0.4)] transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}>
              <Icon icon="mdi:chevron-down" width={16} />
            </div>
          </div>
        </div>
      </button>

      {/* Card Details */}
      {isExpanded && (
        <div className="border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)] p-6">
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">

            {/* Left Column */}
            <div className="space-y-5">
              <div>
                <h6 className="text-[10px] font-bold text-[rgba(0,0,0,0.35)] uppercase tracking-widest mb-2 flex items-center gap-2">
                  <Icon icon="mdi:layers-outline" width={12} className="text-[rgba(0,0,0,0.25)]" />
                  Cloud Services
                </h6>
                <div className="bg-white rounded-xl p-4 border border-[rgba(0,0,0,0.08)]">
                  <p className="text-sm text-[rgba(0,0,0,0.7)] leading-relaxed">{request.services}</p>
                </div>
              </div>
              <div>
                <h6 className="text-[10px] font-bold text-[rgba(0,0,0,0.35)] uppercase tracking-widest mb-2 flex items-center gap-2">
                  <Icon icon="mdi:file-outline" width={12} className="text-[rgba(0,0,0,0.25)]" />
                  Use Case
                </h6>
                <div className="bg-white rounded-xl p-4 border border-[rgba(0,0,0,0.08)]">
                  <p className="text-sm text-[rgba(0,0,0,0.7)] leading-relaxed">{request.useCase}</p>
                </div>
              </div>
            </div>

            {/* Right Column — Progress */}
            <div>
              <h6 className="text-[10px] font-bold text-[rgba(0,0,0,0.35)] uppercase tracking-widest mb-6 flex items-center gap-2">
                <Icon icon="mdi:format-list-checks" width={12} className="text-[rgba(0,0,0,0.25)]" />
                Application Progress
              </h6>
              <div className="relative pl-10 space-y-8">
                <div className="absolute left-4 top-0 bottom-0 w-px bg-[rgba(0,0,0,0.08)]" />

                <ProgressStep
                  icon="mdi:file-import-outline"
                  title="Request Submitted"
                  description="Your credit request has been successfully recorded."
                  status="completed"
                  isCompleted={true}
                />
                <ProgressStep
                  icon="mdi:file-search-outline"
                  title="Documentation Review"
                  description="Our financial analysts are reviewing the submitted details."
                  status={isStageActive('documentation_review') ? 'active' : isStageCompleted('documentation_review') ? 'completed' : 'pending'}
                  isCompleted={isStageCompleted('documentation_review')}
                  isRejected={request.status === 'rejected' && isStageActive('documentation_review')}
                />
                <ProgressStep
                  icon="mdi:shield-check-outline"
                  title="Technical Verification"
                  description="Validating technical requirements and cloud account status."
                  status={isStageActive('technical_verification') ? 'active' : isStageCompleted('technical_verification') ? 'completed' : 'pending'}
                  isCompleted={isStageCompleted('technical_verification')}
                  isRejected={request.status === 'rejected' && isStageActive('technical_verification')}
                />
                <ProgressStep
                  icon={request.status === 'approved' ? 'mdi:check-circle-outline' : request.status === 'rejected' ? 'mdi:close-circle-outline' : 'mdi:flag-checkered'}
                  title={request.status === 'approved' ? 'Credits Approved' : request.status === 'rejected' ? 'Request Declined' : 'Final Decision'}
                  description={
                    request.status === 'approved'
                      ? 'Approved credits have been provisioned to your account.'
                      : request.status === 'rejected'
                        ? 'This request does not meet the necessary criteria.'
                        : 'Pending final validation for credit approval.'
                  }
                  status={request.status === 'approved' || request.status === 'rejected' ? 'completed' : 'pending'}
                  isCompleted={request.status === 'approved'}
                  isRejected={request.status === 'rejected'}
                />
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

interface ProgressStepProps {
  icon: string;
  title: string;
  description: string;
  status: 'completed' | 'active' | 'pending';
  isCompleted: boolean;
  isRejected?: boolean;
}

const ProgressStep: React.FC<ProgressStepProps> = ({ icon, title, description, status, isCompleted, isRejected }) => {
  const getIconThemeClass = (): string => {
    if (isRejected) return 'bg-red-500 text-white';
    if (isCompleted) return 'bg-emerald-500 text-white';
    if (status === 'active') return 'bg-indigo-600 text-white shadow-md';
    return 'bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.3)]';
  };

  const getTitleThemeClass = (): string => {
    if (isRejected) return 'text-red-700';
    if (isCompleted) return 'text-emerald-700';
    return 'text-[rgba(0,0,0,0.88)]';
  };

  return (
    <div className={`relative flex items-start gap-3 ${status === 'pending' ? 'opacity-40' : ''}`}>
      <div className={`absolute -left-[30px] w-8 h-8 rounded-full border-2 border-white flex items-center justify-center z-10 transition-colors ${getIconThemeClass()}`}>
        <Icon icon={icon} width={15} />
      </div>
      <div>
        <div className="flex items-center gap-2">
          <h5 className={`text-sm font-bold ${getTitleThemeClass()}`}>{title}</h5>
          {status === 'active' && (
            <span className="flex h-2 w-2 relative">
              <span className="animate-ping absolute inline-flex h-2 w-2 rounded-full bg-indigo-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-indigo-500" />
            </span>
          )}
        </div>
        <p className="text-xs text-[rgba(0,0,0,0.4)] mt-0.5 leading-relaxed">{description}</p>
      </div>
    </div>
  );
};

export default CreditsPage;
