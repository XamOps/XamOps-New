/**
 * Credits Management Page
 *
 * Converted from: billops/credits.html
 *
 * AWS Credits Management with:
 * - Request submission form
 * - Status tracking with visual progress
 * - Real-time updates (5-second polling)
 * - Filter by status
 * - Statistics overview
 *
 * What stayed the same:
 * - Same API endpoints: GET /api/billops/credits/user/:userId and POST /api/billops/credits
 * - Same data structure and fields
 * - Same polling interval (5 seconds)
 * - Same status workflow
 * - All business logic preserved
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Using custom hook for data fetching
 * - Enhanced UI with smoother animations and professional design
 * - Better mobile responsiveness
 * - Improved accessibility
 */

import React from 'react';
import { useCredits } from '../hooks/useCredits';
import type { CreditRequest, CreditFilter } from '../types/credits';

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
   * Get status badge classes
   */
  const getStatusBadgeClass = (status: string): string => {
    const statusMap: Record<string, string> = {
      submitted: 'bg-blue-100 text-blue-700',
      documentation_review: 'bg-cyan-100 text-cyan-700',
      technical_verification: 'bg-yellow-100 text-yellow-700',
      approved: 'bg-green-100 text-green-700',
      rejected: 'bg-red-100 text-red-700',
    };
    return statusMap[status] || 'bg-gray-100 text-gray-700';
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

  return (
    <div className="credits-page flex-1 flex flex-col h-screen overflow-hidden">
      {/* Header */}
      <header className="credits-header px-8 py-6 border-b border-gray-200 bg-white sticky top-0 z-10">
        <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
          <div>
            <h3 className="text-3xl font-bold text-gray-900 flex items-center gap-3">
              <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center">
                <i className="fas fa-coins text-white text-xl" />
              </div>
              Credits Management
            </h3>
            <p className="text-gray-600 mt-2 ml-15">Request and track your credit applications</p>
          </div>

          <div className="flex items-center gap-6">
            {/* Total Requested */}
            <div className="text-center">
              <div className="text-2xl font-bold text-indigo-600">
                {formatCurrency(totalRequested)}
              </div>
              <div className="text-xs text-gray-500 font-medium">Total Requested</div>
            </div>

            {/* Pending Count */}
            <div className="text-center">
              <div className="text-2xl font-bold text-orange-600">{pendingCount}</div>
              <div className="text-xs text-gray-500 font-medium">Pending</div>
            </div>

            {/* New Request Button */}
            <button
              onClick={() => setShowForm(true)}
              className="bg-gradient-to-r from-indigo-600 to-purple-600 text-white font-semibold py-3 px-6 rounded-xl hover:from-indigo-700 hover:to-purple-700 transition-all duration-300 transform hover:scale-105 shadow-lg flex items-center gap-2"
            >
              <i className="fas fa-plus" />
              New Request
            </button>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 overflow-y-auto p-8 bg-gray-50 credits-scrollbar">
        {/* Error State */}
        {error && (
          <div className="bg-red-50 border-l-4 border-red-500 p-4 rounded-lg mb-6 animate-fade-in">
            <div className="flex items-center gap-3">
              <i className="fas fa-exclamation-circle text-red-500 text-xl" />
              <span className="text-red-700 font-medium">{error}</span>
            </div>
          </div>
        )}

        {/* Filters */}
        <div className="bg-white rounded-xl shadow-sm p-6 mb-8 animate-fade-in">
          <div className="flex flex-wrap gap-2">
            <button
              onClick={() => handleFilterClick('all')}
              className={`px-4 py-2 rounded-lg font-medium transition-all duration-200 ${
                selectedFilter === 'all'
                  ? 'bg-indigo-600 text-white shadow-md'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              All Requests
            </button>
            <button
              onClick={() => handleFilterClick('submitted')}
              className={`px-4 py-2 rounded-lg font-medium transition-all duration-200 ${
                selectedFilter === 'submitted'
                  ? 'bg-blue-600 text-white shadow-md'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Submitted
            </button>
            <button
              onClick={() => handleFilterClick('documentation_review')}
              className={`px-4 py-2 rounded-lg font-medium transition-all duration-200 ${
                selectedFilter === 'documentation_review'
                  ? 'bg-cyan-600 text-white shadow-md'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Documentation
            </button>
            <button
              onClick={() => handleFilterClick('technical_verification')}
              className={`px-4 py-2 rounded-lg font-medium transition-all duration-200 ${
                selectedFilter === 'technical_verification'
                  ? 'bg-yellow-600 text-white shadow-md'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Verification
            </button>
            <button
              onClick={() => handleFilterClick('approved')}
              className={`px-4 py-2 rounded-lg font-medium transition-all duration-200 ${
                selectedFilter === 'approved'
                  ? 'bg-green-600 text-white shadow-md'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Approved
            </button>
            <button
              onClick={() => handleFilterClick('rejected')}
              className={`px-4 py-2 rounded-lg font-medium transition-all duration-200 ${
                selectedFilter === 'rejected'
                  ? 'bg-red-600 text-white shadow-md'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              Rejected
            </button>
          </div>
        </div>

        {/* Credit Requests List */}
        {loading ? (
          <div className="space-y-6">
            {[1, 2, 3].map((i) => (
              <div key={i} className="bg-white rounded-2xl h-96 animate-pulse">
                <div className="bg-gray-200 h-32 rounded-t-2xl" />
                <div className="p-8 space-y-4">
                  <div className="h-6 bg-gray-200 rounded w-3/4" />
                  <div className="h-4 bg-gray-200 rounded w-1/2" />
                  <div className="h-4 bg-gray-200 rounded w-2/3" />
                </div>
              </div>
            ))}
          </div>
        ) : filteredRequests.length === 0 ? (
          <div className="text-center py-20 bg-white rounded-2xl shadow-sm">
            <div className="inline-flex items-center justify-center w-20 h-20 rounded-full bg-gray-100 mb-4">
              <i className="fas fa-inbox text-gray-400 text-3xl" />
            </div>
            <p className="text-gray-500 text-lg font-medium">No credit requests found</p>
            <p className="text-gray-400 text-sm mt-2">
              {selectedFilter === 'all'
                ? 'Click "New Request" to submit your first credit request'
                : `No requests with status: ${getStatusDisplayName(selectedFilter)}`}
            </p>
          </div>
        ) : (
          <div className="space-y-6">
            {filteredRequests.map((request: CreditRequest) => (
              <CreditRequestCard key={request.id} request={request} />
            ))}
          </div>
        )}
      </main>

      {/* New Request Form Modal */}
      {showForm && (
        <div
          className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
          onClick={handleCloseForm}
        >
          <div
            className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto animate-scale-in"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="sticky top-0 bg-white border-b border-gray-200 px-8 py-6 rounded-t-2xl">
              <div className="flex items-center justify-between">
                <h4 className="text-2xl font-bold text-gray-900 flex items-center gap-3">
                  <div className="w-10 h-10 rounded-lg bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center">
                    <i className="fas fa-plus text-white text-sm" />
                  </div>
                  Request AWS Credits
                </h4>
                <button
                  onClick={handleCloseForm}
                  className="text-gray-400 hover:text-gray-600 transition-colors"
                >
                  <i className="fas fa-times text-xl" />
                </button>
              </div>
            </div>

            <form onSubmit={handleSubmit} className="p-8">
              <div className="space-y-6">
                {/* AWS Account ID */}
                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-2">
                    AWS Account ID or GCP Project ID
                    <span className="text-red-500 ml-1">*</span>
                  </label>
                  <input
                    type="text"
                    value={newRequestData.awsAccountId}
                    onChange={(e) =>
                      setNewRequestData({ ...newRequestData, awsAccountId: e.target.value })
                    }
                    placeholder="Enter your AWS Account ID or GCP Project ID"
                    className="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all"
                    required
                  />
                </div>

                {/* Expected Credits */}
                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-2">
                    Expected Credits ($)
                    <span className="text-red-500 ml-1">*</span>
                  </label>
                  <input
                    type="number"
                    value={newRequestData.expectedCredits || ''}
                    onChange={(e) =>
                      setNewRequestData({
                        ...newRequestData,
                        expectedCredits: e.target.value ? parseFloat(e.target.value) : null,
                      })
                    }
                    placeholder="e.g., 1000"
                    min="1"
                    step="1"
                    className="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all"
                    required
                  />
                </div>

                {/* Priority */}
                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-2">
                    Priority Level
                  </label>
                  <select
                    value={newRequestData.priority}
                    onChange={(e) =>
                      setNewRequestData({
                        ...newRequestData,
                        priority: e.target.value as 'low' | 'medium' | 'high',
                      })
                    }
                    className="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all"
                  >
                    <option value="low">Low Priority</option>
                    <option value="medium">Medium Priority</option>
                    <option value="high">High Priority</option>
                  </select>
                </div>

                {/* AWS Services */}
                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-2">
                    AWS Services
                    <span className="text-red-500 ml-1">*</span>
                  </label>
                  <textarea
                    value={newRequestData.services}
                    onChange={(e) =>
                      setNewRequestData({ ...newRequestData, services: e.target.value })
                    }
                    placeholder="e.g., EC2, S3, RDS, Lambda"
                    rows={3}
                    className="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all resize-none"
                    required
                  />
                </div>

                {/* Use Case */}
                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-2">
                    Project Use Case
                    <span className="text-red-500 ml-1">*</span>
                  </label>
                  <textarea
                    value={newRequestData.useCase}
                    onChange={(e) =>
                      setNewRequestData({ ...newRequestData, useCase: e.target.value })
                    }
                    placeholder="Describe your project and how you plan to use AWS credits"
                    rows={5}
                    className="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all resize-none"
                    required
                  />
                </div>
              </div>

              {/* Form Actions */}
              <div className="mt-8 flex justify-end gap-4">
                <button
                  type="button"
                  onClick={handleCloseForm}
                  className="px-6 py-3 bg-gray-100 text-gray-700 font-semibold rounded-lg hover:bg-gray-200 transition-colors"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-6 py-3 bg-gradient-to-r from-indigo-600 to-purple-600 text-white font-semibold rounded-lg hover:from-indigo-700 hover:to-purple-700 transition-all shadow-lg"
                >
                  Submit Request
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

/**
 * Credit Request Card Component
 */
const CreditRequestCard: React.FC<{ request: CreditRequest }> = ({ request }) => {
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

  return (
    <div className="bg-white rounded-2xl shadow-lg hover:shadow-xl transition-all duration-300 overflow-hidden animate-fade-in">
      {/* Card Header */}
      <div className="bg-gradient-to-r from-indigo-600 to-purple-600 text-white p-6">
        <div className="flex justify-between items-start flex-wrap gap-4">
          <div className="flex-1">
            <div className="flex items-center gap-3 flex-wrap">
              <h4 className="text-xl font-bold">AWS Account</h4>
              <span className="font-mono text-lg bg-white bg-opacity-20 px-3 py-1 rounded-lg">
                {request.awsAccountId}
              </span>
            </div>
            <div className="flex items-center gap-4 mt-2 text-sm flex-wrap">
              <span className="text-indigo-100">
                Request ID: <span className="font-mono">{request.id}</span>
              </span>
              <span className="text-indigo-100">
                Submitted: <span className="font-semibold">{formatDate(request.submittedDate)}</span>
              </span>
            </div>
          </div>
          <div className="text-right">
            <div className="text-3xl font-bold">{formatCurrency(request.expectedCredits)}</div>
            <div className="text-indigo-100 text-sm font-medium mt-1">Requested Amount</div>
          </div>
        </div>
      </div>

      {/* Card Body */}
      <div className="p-8">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
          {/* Left Column - Details */}
          <div className="space-y-6">
            <div>
              <h6 className="text-sm font-bold text-gray-900 uppercase tracking-wide mb-2">
                AWS Services
              </h6>
              <div className="bg-gray-50 rounded-lg p-4">
                <p className="text-gray-700 whitespace-pre-wrap">{request.services}</p>
              </div>
            </div>
            <div>
              <h6 className="text-sm font-bold text-gray-900 uppercase tracking-wide mb-2">
                Use Case
              </h6>
              <div className="bg-gray-50 rounded-lg p-4">
                <p className="text-gray-700 whitespace-pre-wrap">{request.useCase}</p>
              </div>
            </div>
          </div>

          {/* Right Column - Progress */}
          <div>
            <h6 className="text-sm font-bold text-gray-900 uppercase tracking-wide mb-6">
              Application Progress
            </h6>
            <div className="relative">
              {/* Progress Line */}
              <div className="border-l-4 border-indigo-200 absolute h-full left-6 top-0" />

              {/* Progress Steps */}
              <div className="space-y-8">
                {/* Step 1: Submitted */}
                <ProgressStep
                  icon="fa-check"
                  title="Request Submitted"
                  description="Credit request has been successfully submitted"
                  status="completed"
                  isCompleted={true}
                />

                {/* Step 2: Documentation Review */}
                <ProgressStep
                  icon={
                    isStageCompleted('documentation_review') ? 'fa-check' : 'fa-file-alt'
                  }
                  title="Documentation Review"
                  description="We are reviewing your documentation"
                  status={
                    isStageActive('documentation_review')
                      ? 'active'
                      : isStageCompleted('documentation_review')
                      ? 'completed'
                      : 'pending'
                  }
                  isCompleted={isStageCompleted('documentation_review')}
                  isRejected={request.status === 'rejected' && isStageActive('documentation_review')}
                />

                {/* Step 3: Technical Verification */}
                <ProgressStep
                  icon={
                    isStageActive('technical_verification')
                      ? 'fa-search'
                      : isStageCompleted('technical_verification')
                      ? 'fa-check'
                      : 'fa-hourglass-start'
                  }
                  title="Technical Verification"
                  description="AWS is verifying your use case"
                  status={
                    isStageActive('technical_verification')
                      ? 'active'
                      : isStageCompleted('technical_verification')
                      ? 'completed'
                      : 'pending'
                  }
                  isCompleted={isStageCompleted('technical_verification')}
                  isRejected={request.status === 'rejected' && isStageActive('technical_verification')}
                />

                {/* Step 4: Final Decision */}
                <ProgressStep
                  icon={
                    request.status === 'approved'
                      ? 'fa-check-circle'
                      : request.status === 'rejected'
                      ? 'fa-times-circle'
                      : 'fa-trophy'
                  }
                  title={
                    request.status === 'approved'
                      ? 'Credits Approved'
                      : request.status === 'rejected'
                      ? 'Request Rejected'
                      : 'Final Decision'
                  }
                  description="Credits will be applied to your account"
                  status={
                    request.status === 'approved' || request.status === 'rejected'
                      ? 'completed'
                      : 'pending'
                  }
                  isCompleted={request.status === 'approved'}
                  isRejected={request.status === 'rejected'}
                />
              </div>
            </div>
          </div>
        </div>
      </div>
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

const ProgressStep: React.FC<ProgressStepProps> = ({
  icon,
  title,
  description,
  status,
  isCompleted,
  isRejected,
}) => {
  const getStepClass = (): string => {
    if (isRejected) return 'bg-red-500';
    if (status === 'completed') return 'bg-gradient-to-r from-blue-500 to-indigo-600';
    if (status === 'active') return 'bg-gradient-to-r from-yellow-500 to-orange-500 animate-pulse';
    return 'bg-gray-300';
  };

  const getStatusText = (): string => {
    if (isRejected) return '✗ Rejected';
    if (status === 'completed') return '✓ Completed';
    if (status === 'active') return '⏳ In Progress';
    return 'Pending';
  };

  const getStatusTextClass = (): string => {
    if (isRejected) return 'text-red-600';
    if (status === 'completed') return 'text-green-600';
    if (status === 'active') return 'text-yellow-600 animate-pulse';
    return 'text-gray-500';
  };

  const getTitleClass = (): string => {
    if (isRejected) return 'text-red-600';
    if (isCompleted) return 'text-green-600';
    return 'text-gray-900';
  };

  return (
    <div className={`flex items-center ${status === 'pending' ? 'opacity-40' : ''}`}>
      <div
        className={`rounded-full h-12 w-12 flex items-center justify-center text-white z-10 shadow-lg ${getStepClass()}`}
      >
        <i className={`fas ${icon} text-sm`} />
      </div>
      <div className="ml-6">
        <h5 className={`font-bold ${getTitleClass()}`}>{title}</h5>
        <p className="text-sm text-gray-600">{description}</p>
        <span className={`text-xs font-medium ${getStatusTextClass()}`}>{getStatusText()}</span>
      </div>
    </div>
  );
};

export default CreditsPage;
