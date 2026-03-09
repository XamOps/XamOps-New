/**
 * Multicloud Credits Management Page
 *
 * Converted from: billops/credits.html
 *
 * Standard Enterprise UI for multicloud credits management:
 * - Request submission form (AWS, GCP, Azure)
 * - Status tracking with visual progress and modern cards
 * - Real-time updates (5-second polling)
 * - Filter by status
 * - Statistics overview in the Topbar
 * - Clean Indigo/Slate enterprise theme
 *
 * What stayed the same:
 * - Same API endpoints: GET /api/billops/credits/user/:userId and POST /api/billops/credits
 * - Same data structure and fields (awsAccountId supports multicloud)
 * - Same polling interval (5 seconds)
 * - Same status workflow
 * - All business logic preserved
 *
 * What changed:
 * - Migrated from Glassmorphism to Standard Enterprise UI
 * - Used Indigo-600 as the primary brand color
 * - Standardized card and table styling for consistency
 * - Enhanced accessibility and responsiveness
 * - Better integration with Topbar actions
 */

import React, { useEffect } from 'react';
import { useCredits } from '../hooks/useCredits';
import { useTopbar } from '../contexts/TopbarContext';
import type { CreditRequest, CreditFilter } from '../types/credits';
import { DialogRoot, DialogPanel } from '../components/ui';

const CreditsPage: React.FC = () => {
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

  /**
   * Format currency value
   */
  const formatCurrency = (value: number): string => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    }).format(value);
  };

  /**
   * Get status display name
   */
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

  /**
   * Handle form submission
   */
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    await submitRequest();
  };

  /**
   * Handle filter button click
   */
  const handleFilterClick = (filter: CreditFilter) => {
    setSelectedFilter(filter);
  };

  /**
   * Handle close form
   */
  const handleCloseForm = () => {
    setShowForm(false);
    resetForm();
  };

  /**
   * Set Topbar Actions
   */
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-6">
        <div className="flex items-center gap-4">
          <div className="text-center bg-white px-4 py-2 rounded-xl border border-gray-200 shadow-sm hidden sm:block">
            <div className="text-sm font-bold text-indigo-600">
              {formatCurrency(totalRequested)}
            </div>
            <div className="text-[10px] text-gray-500 font-bold uppercase tracking-wider">Total Requested</div>
          </div>
          <div className="text-center bg-white px-4 py-2 rounded-xl border border-gray-200 shadow-sm hidden sm:block">
            <div className="text-sm font-bold text-sky-600">{pendingCount}</div>
            <div className="text-[10px] text-gray-500 font-bold uppercase tracking-wider">Pending</div>
          </div>
        </div>
        <button
          onClick={() => setShowForm(true)}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 transition-all active:scale-95"
        >
          <i className="fas fa-plus" />
          <span>New Request</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, totalRequested, pendingCount]);

  return (
    <div className="credits-page flex-1 flex flex-col h-full overflow-hidden bg-transparent">

      {/* Main Content */}
      <main className="flex-1 overflow-y-auto p-8 custom-scrollbar relative z-0">
        {/* Error State */}
        {error && (
          <div className="bg-red-50 border-l-4 border-red-500 p-4 rounded-xl mb-6 shadow-sm">
            <div className="flex items-center gap-3">
              <i className="fas fa-exclamation-circle text-red-600 text-xl" />
              <span className="text-red-700 font-medium">{error}</span>
            </div>
          </div>
        )}

        {/* Filters */}
        <div className="bg-white border border-gray-200 rounded-2xl p-6 mb-8 shadow-sm">
          <div className="flex flex-wrap gap-2">
            {[
              { id: 'all', label: 'All Requests', color: 'indigo' },
              { id: 'submitted', label: 'Submitted', color: 'blue' },
              { id: 'documentation_review', label: 'Documentation', color: 'cyan' },
              { id: 'technical_verification', label: 'Verification', color: 'orange' },
              { id: 'approved', label: 'Approved', color: 'green' },
              { id: 'rejected', label: 'Rejected', color: 'red' },
            ].map((filter) => (
              <button
                key={filter.id}
                onClick={() => handleFilterClick(filter.id as CreditFilter)}
                className={`px-4 py-2 rounded-xl font-semibold text-sm transition-all duration-200 ${selectedFilter === filter.id
                  ? filter.color === 'orange'
                    ? 'bg-amber-600 text-white shadow-md shadow-amber-500/20'
                    : `bg-${filter.color}-600 text-white shadow-md shadow-${filter.color}-500/20`
                  : 'bg-gray-50 text-gray-600 hover:bg-gray-100 border border-gray-100 shadow-sm'
                  }`}
              >
                {filter.label}
              </button>
            ))}
          </div>
        </div>

        {/* Credit Requests List */}
        {loading ? (
          <div className="space-y-6">
            {[1, 2, 3].map((i) => (
              <div key={i} className="bg-white border border-gray-200 rounded-2xl h-64 overflow-hidden shadow-sm">
                <div className="bg-gray-100 h-24 animate-pulse" />
                <div className="p-8 space-y-4">
                  <div className="h-6 bg-gray-100 rounded w-3/4 animate-pulse" />
                  <div className="h-4 bg-gray-100 rounded w-1/2 animate-pulse" />
                </div>
              </div>
            ))}
          </div>
        ) : filteredRequests.length === 0 ? (
          <div className="text-center py-24 bg-white border border-gray-200 rounded-2xl shadow-sm">
            <div className="inline-flex items-center justify-center w-20 h-20 rounded-full bg-gray-50 mb-6">
              <i className="fas fa-inbox text-gray-300 text-4xl" />
            </div>
            <h3 className="text-xl font-bold text-gray-900 mb-2">No credit requests found</h3>
            <p className="text-gray-500 max-w-sm mx-auto">
              {selectedFilter === 'all'
                ? 'Click "New Request" to submit your first credit request for cloud cost adjustment.'
                : `No requests currently in ${getStatusDisplayName(selectedFilter)} status.`}
            </p>
          </div>
        ) : (
          <div className="space-y-6">
            {filteredRequests.map((request: CreditRequest, index: number) => (
              <div key={request.id} className="animate-fade-in-up" style={{ animationDelay: `${index * 0.1}s` }}>
                <CreditRequestCard request={request} />
              </div>
            ))}
          </div>
        )}
      </main>

      {/* New Request Form Modal */}
      <DialogRoot isOpen={showForm} onClose={handleCloseForm} maxWidth="max-w-2xl">
        <DialogPanel
          title={
            <div>
              <div className="text-2xl font-bold text-gray-900">Request Cloud Credits</div>
              <p className="text-gray-500 text-sm mt-1">Submit a new request for cloud cost credits</p>
            </div>
          }
          onClose={handleCloseForm}
          footer={
            <>
              <button
                type="button"
                onClick={handleCloseForm}
                className="px-6 py-3 bg-white text-gray-700 font-bold rounded-xl hover:bg-gray-50 border border-gray-200 transition-all cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="submit"
                form="credits-request-form"
                className="px-8 py-3 bg-indigo-600 text-white font-bold rounded-xl hover:bg-indigo-700 transition-all shadow-lg shadow-indigo-600/20 cursor-pointer"
              >
                Submit Request
              </button>
            </>
          }
        >
          <div className="overflow-y-auto max-h-[60vh]">
            <form id="credits-request-form" onSubmit={handleSubmit} className="space-y-6">
                {/* Cloud Account ID */}
                <div>
                  <label htmlFor="awsAccountId" className="block text-sm font-bold text-gray-700 mb-2">
                    Cloud Account ID
                    <span className="text-red-500 ml-1">*</span>
                  </label>
                  <input
                    id="awsAccountId"
                    type="text"
                    value={newRequestData.awsAccountId}
                    onChange={(e) =>
                      setNewRequestData({ ...newRequestData, awsAccountId: e.target.value })
                    }
                    placeholder="e.g., AWS: 123456789012 | GCP: my-project | Azure: Sub-ID"
                    className="w-full px-4 py-3 rounded-xl focus:ring-2 focus:ring-indigo-500 border border-gray-200 outline-none transition-all"
                    required
                  />
                  <p className="text-[11px] text-gray-500 mt-2 font-medium">Supports AWS Account IDs, GCP Project IDs, and Azure Subscription IDs</p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                  {/* Expected Credits */}
                  <div>
                    <label htmlFor="expectedCredits" className="block text-sm font-bold text-gray-700 mb-2">
                      Expected Credits ($)
                      <span className="text-red-500 ml-1">*</span>
                    </label>
                    <div className="relative">
                      <div className="absolute inset-y-0 left-0 pl-4 flex items-center pointer-events-none text-gray-400 font-bold">
                        $
                      </div>
                      <input
                        id="expectedCredits"
                        type="number"
                        value={newRequestData.expectedCredits || ''}
                        onChange={(e) =>
                          setNewRequestData({
                            ...newRequestData,
                            expectedCredits: e.target.value ? Number.parseFloat(e.target.value) : null,
                          })
                        }
                        placeholder="0.00"
                        min="1"
                        step="1"
                        className="w-full pl-8 pr-4 py-3 rounded-xl focus:ring-2 focus:ring-indigo-500 border border-gray-200 outline-none transition-all"
                        required
                      />
                    </div>
                  </div>

                  {/* Priority */}
                  <div>
                    <label htmlFor="priority" className="block text-sm font-bold text-gray-700 mb-2">
                      Priority Level
                    </label>
                    <select
                      id="priority"
                      value={newRequestData.priority}
                      onChange={(e) =>
                        setNewRequestData({
                          ...newRequestData,
                          priority: e.target.value as 'low' | 'medium' | 'high',
                        })
                      }
                      className="w-full px-4 py-3 rounded-xl focus:ring-2 focus:ring-indigo-500 border border-gray-200 outline-none transition-all cursor-pointer bg-white"
                    >
                      <option value="low">Low Priority</option>
                      <option value="medium">Medium Priority</option>
                      <option value="high">High Priority</option>
                    </select>
                  </div>
                </div>

                {/* Cloud Services */}
                <div>
                  <label htmlFor="services" className="block text-sm font-bold text-gray-700 mb-2">
                    Cloud Services
                    <span className="text-red-500 ml-1">*</span>
                  </label>
                  <textarea
                    id="services"
                    value={newRequestData.services}
                    onChange={(e) =>
                      setNewRequestData({ ...newRequestData, services: e.target.value })
                    }
                    placeholder="List the cloud services relevant to this credit request..."
                    rows={3}
                    className="w-full px-4 py-3 rounded-xl focus:ring-2 focus:ring-indigo-500 border border-gray-200 outline-none transition-all resize-none"
                    required
                  />
                </div>

                {/* Use Case */}
                <div>
                  <label htmlFor="useCase" className="block text-sm font-bold text-gray-700 mb-2">
                    Project Use Case
                    <span className="text-red-500 ml-1">*</span>
                  </label>
                  <textarea
                    id="useCase"
                    value={newRequestData.useCase}
                    onChange={(e) =>
                      setNewRequestData({ ...newRequestData, useCase: e.target.value })
                    }
                    placeholder="Describe your project and how you plan to use these cloud credits..."
                    rows={4}
                    className="w-full px-4 py-3 rounded-xl focus:ring-2 focus:ring-indigo-500 border border-gray-200 outline-none transition-all resize-none"
                    required
                  />
                </div>

            </form>
          </div>
        </DialogPanel>
      </DialogRoot>
    </div>
  );
};

/**
 * Credit Request Card Component
 */
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

  const isStageActive = (stage: string): boolean => {
    return request.status === stage;
  };

  /**
   * Get main status icon based on current request status
   */
  const getStatusIconClass = (): string => {
    if (request.status === 'approved') return 'fa-check-circle';
    if (request.status === 'rejected') return 'fa-times-circle';
    return 'fa-file-invoice-dollar';
  };

  /**
   * Get status background and text colors based on status
   */
  const getStatusThemeClass = (): string => {
    if (request.status === 'approved') return 'bg-green-100 text-green-600';
    if (request.status === 'rejected') return 'bg-red-100 text-red-600';
    return 'bg-indigo-100 text-indigo-600';
  };

  /**
   * Get priority badge color theme
   */
  const getPriorityThemeClass = (): string => {
    if (request.priority === 'high') return 'bg-red-100 text-red-600';
    if (request.priority === 'medium') return 'bg-amber-100 text-amber-600';
    return 'bg-indigo-100 text-indigo-600';
  };

  return (
    <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden hover:shadow-md transition-all duration-300">
      {/* Card Header */}
      <button
        type="button"
        className="w-full text-left p-6 cursor-pointer hover:bg-gray-50/50 transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500/20"
        onClick={() => setIsExpanded(!isExpanded)}
        aria-expanded={isExpanded}
      >
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-6">
          <div className="flex items-start gap-4">
            <div className={`w-12 h-12 rounded-2xl flex items-center justify-center text-xl shadow-sm ${getStatusThemeClass()}`}>
              <i className={`fas ${getStatusIconClass()}`} />
            </div>
            <div>
              <div className="flex items-center gap-3">
                <h4 className="text-lg font-bold text-gray-900">{request.awsAccountId}</h4>
                <span className={`px-2.5 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider ${getPriorityThemeClass()}`}>
                  {request.priority}
                </span>
              </div>
              <div className="flex items-center gap-4 mt-1 text-xs text-gray-500 font-medium">
                <span>ID: <span className="text-gray-900 font-bold">#{request.id}</span></span>
                <span>Submitted: <span className="text-gray-900 font-bold">{formatDate(request.submittedDate)}</span></span>
              </div>
            </div>
          </div>

          <div className="flex items-center justify-between md:justify-end gap-8 border-t md:border-t-0 pt-4 md:pt-0">
            <div className="text-right">
              <div className="text-2xl font-black text-gray-900 tracking-tight">{formatCurrency(request.expectedCredits)}</div>
              <div className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mt-0.5">Requested Credit</div>
            </div>
            <div className={`w-10 h-10 rounded-xl bg-gray-50 flex items-center justify-center text-gray-400 border border-gray-100 transition-transform duration-300 ${isExpanded ? 'rotate-180' : ''}`}>
              <i className="fas fa-chevron-down" />
            </div>
          </div>
        </div>
      </button>

      {/* Card Details */}
      {isExpanded && (
        <div className="border-t border-gray-100 bg-gray-50/30 p-8 animate-fade-in">
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-12">
            {/* Left Column */}
            <div className="space-y-8">
              <div>
                <h6 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-3 flex items-center gap-2">
                  <i className="fas fa-layer-group text-gray-300" /> Cloud Services
                </h6>
                <div className="bg-white rounded-xl p-5 border border-gray-100 shadow-sm">
                  <p className="text-sm text-gray-700 leading-relaxed font-medium">{request.services}</p>
                </div>
              </div>
              <div>
                <h6 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-3 flex items-center gap-2">
                  <i className="fas fa-file-alt text-gray-300" /> Use Case
                </h6>
                <div className="bg-white rounded-xl p-5 border border-gray-100 shadow-sm">
                  <p className="text-sm text-gray-700 leading-relaxed font-medium">{request.useCase}</p>
                </div>
              </div>
            </div>

            {/* Right Column */}
            <div>
              <h6 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-8 flex items-center gap-2">
                <i className="fas fa-tasks text-gray-300" /> Application Progress
              </h6>
              <div className="relative pl-12 space-y-10">
                {/* Progress Line */}
                <div className="absolute left-5 top-0 bottom-0 w-0.5 bg-gray-100" />

                <ProgressStep
                  icon="fa-file-import"
                  title="Request Submitted"
                  description="Your credit request has been successfully recorded."
                  status="completed"
                  isCompleted={true}
                />
                <ProgressStep
                  icon="fa-file-search"
                  title="Documentation Review"
                  description="Our financial analysts are reviewing the submitted details."
                  status={isStageActive('documentation_review') ? 'active' : isStageCompleted('documentation_review') ? 'completed' : 'pending'}
                  isCompleted={isStageCompleted('documentation_review')}
                  isRejected={request.status === 'rejected' && isStageActive('documentation_review')}
                />
                <ProgressStep
                  icon="fa-shield-check"
                  title="Technical Verification"
                  description="Validating technical requirements and cloud account status."
                  status={isStageActive('technical_verification') ? 'active' : isStageCompleted('technical_verification') ? 'completed' : 'pending'}
                  isCompleted={isStageCompleted('technical_verification')}
                  isRejected={request.status === 'rejected' && isStageActive('technical_verification')}
                />
                <ProgressStep
                  icon={request.status === 'approved' ? 'fa-check-circle' : request.status === 'rejected' ? 'fa-times-circle' : 'fa-flag-checkered'}
                  title={request.status === 'approved' ? 'Credits Approved' : request.status === 'rejected' ? 'Request Declined' : 'Final Decision'}
                  description={request.status === 'approved' ? 'Approved credits have been provisioned to your account.' : request.status === 'rejected' ? 'This request does not meet the necessary criteria.' : 'Pending final validation for credit approval.'}
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

/**
 * Progress Step Component
 */
interface ProgressStepProps {
  icon: string;
  title: string;
  description: string;
  status: 'completed' | 'active' | 'pending';
  isCompleted: boolean;
  isRejected?: boolean;
}

const ProgressStep: React.FC<ProgressStepProps> = ({ icon, title, description, status, isCompleted, isRejected }) => {
  /**
   * Get icon container theme class
   */
  const getIconThemeClass = (): string => {
    if (isRejected) return 'bg-red-500 text-white';
    if (isCompleted) return 'bg-green-500 text-white';
    if (status === 'active') return 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/30';
    return 'bg-gray-100 text-gray-400';
  };

  /**
   * Get title text theme class
   */
  const getTitleThemeClass = (): string => {
    if (isRejected) return 'text-red-700';
    if (isCompleted) return 'text-green-700';
    return 'text-gray-900';
  };

  return (
    <div className={`relative flex items-start gap-4 ${status === 'pending' ? 'opacity-40' : ''}`}>
      <div className={`absolute -left-[37px] w-10 h-10 rounded-full border-4 border-white flex items-center justify-center text-sm z-10 transition-colors shadow-sm ${getIconThemeClass()}`}>
        <i className={`fas ${icon}`} />
      </div>
      <div>
        <div className="flex items-center gap-3">
          <h5 className={`text-sm font-bold ${getTitleThemeClass()}`}>{title}</h5>
          {status === 'active' && (
            <span className="flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-2 w-2 rounded-full bg-indigo-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-indigo-500" />
            </span>
          )}
        </div>
        <p className="text-xs text-gray-500 font-medium mt-0.5 leading-relaxed">{description}</p>
      </div>
    </div>
  );
};

export default CreditsPage;
