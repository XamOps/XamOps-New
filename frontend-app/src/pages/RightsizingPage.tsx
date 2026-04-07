/**
 * Rightsizing Page
 *
 * Converted from: rightsizing.html
 *
 * This is the main Rightsizing page for optimizing cloud resource sizing.
 *
 * Features:
 * - Two tabs: AWS Recommendations and XamAi Recommendations
 * - Real-time data fetching from backend
 * - AI-powered analysis for detailed recommendations
 * - Resource scanning with force refresh
 * - Session storage caching for better performance
 * - Cost comparison and savings calculations
 */

import React, { useState, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useRightsizingData } from '../hooks/useRightsizingData';
import { AriaTabGroup } from '../components/ui';
import { AlertDialog } from '../components/ui/AlertDialog';
import apiClient from '../utils/apiClient';

// Component imports
import RightsizingHeader from '../components/Rightsizing/RightsizingHeader';
import AWSRecommendationsTable from '../components/Rightsizing/AWSRecommendationsTable';
import XamOpsRecommendationsTable from '../components/Rightsizing/XamOpsRecommendationsTable';

interface ApplyTarget {
  instanceId: string;
  currentType: string;
  targetType: string;
  region: string;
}

export const RightsizingPage: React.FC = () => {
  const { selectedAccountIds, accounts } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Determine if the active account has read-write access
  const selectedAccount = accounts.find(a => a.id === selectedAccountIds[0]);
  const canApply = selectedAccount?.access === 'read-write';

  // Get rightsizing data
  const {
    awsRecommendations,
    xamopsRecommendations,
    isLoadingAWS,
    isLoadingXamOps,
    isRefreshing,
    error,
    refreshAll,
  } = useRightsizingData({
    selectedAccountId,
  });

  // Tab state
  const [activeTab, setActiveTab] = useState<'aws' | 'xamops'>('aws');

  // Apply confirmation state
  const [applyTarget, setApplyTarget] = useState<ApplyTarget | null>(null);
  const [isApplying, setIsApplying] = useState(false);

  // Filter out already optimized resources from AWS recommendations
  const filteredAWSRecommendations = awsRecommendations.filter(rec => {
    // Check recommendedType field
    if (rec.recommendedType) {
      const recType = rec.recommendedType.toLowerCase();
      if (recType.includes('optimized')) return false;
    }

    // Check recommendationReason field for optimized resources
    if (rec.recommendationReason) {
      const reason = rec.recommendationReason.toLowerCase();
      if (reason.includes('optimized') || reason.includes('no recommendation')) return false;
    }

    return true;
  });

  // Handle apply request — find the current type from the recommendation list
  const handleApplyRequest = useCallback((instanceId: string, targetType: string, region: string) => {
    // Look up current type from both recommendation lists
    const awsRec = awsRecommendations.find(r => r.resourceId === instanceId);
    const xamopsRec = xamopsRecommendations.find(r => r.instanceId === instanceId);
    const currentType = awsRec?.currentType || xamopsRec?.currentInstance || 'current type';

    setApplyTarget({ instanceId, currentType, targetType, region });
  }, [awsRecommendations, xamopsRecommendations]);

  const handleApplyConfirm = useCallback(async () => {
    if (!applyTarget || !selectedAccountId) return;
    setIsApplying(true);
    try {
      await apiClient.fetch(
        `/api/xamops/rightsizing/apply?accountId=${selectedAccountId}&instanceId=${encodeURIComponent(applyTarget.instanceId)}&targetInstanceType=${encodeURIComponent(applyTarget.targetType)}&region=${encodeURIComponent(applyTarget.region)}`,
        { method: 'POST' }
      );
      setApplyTarget(null);
    } catch (err) {
      console.error('Failed to apply rightsizing:', err);
      setApplyTarget(null);
    } finally {
      setIsApplying(false);
    }
  }, [applyTarget, selectedAccountId]);

  // Show error state
  if (error) {
    return (
      <main className="flex-1 flex flex-col min-h-screen bg-transparent">
        <div className="fixed inset-0 flex items-center justify-center bg-red-50 text-red-700 z-50">
          <div className="bg-white p-8 rounded-lg shadow-xl max-w-md w-full mx-4">
            <div className="flex items-center justify-center w-16 h-16 mx-auto mb-4 bg-red-100 rounded-full">
              <i className="fas fa-exclamation-triangle text-red-600 text-2xl"></i>
            </div>
            <h2 className="text-2xl font-bold text-center mb-4 text-gray-800">Error</h2>
            <p className="text-center text-gray-600 mb-6">{error.message}</p>
            <button
              onClick={() => window.location.reload()}
              className="w-full bg-red-600 text-white py-2 px-4 rounded-lg hover:bg-red-700 transition-colors"
            >
              Try Again
            </button>
          </div>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-transparent min-w-0 overflow-x-auto">
      <RightsizingHeader isRefreshing={isRefreshing} onRefresh={refreshAll} />

      <div className="flex-1 p-8 space-y-8 min-w-0">
        {/* No Account State */}
        {!selectedAccountId && !isLoadingAWS && !isLoadingXamOps && (
          <div className="text-center py-12 rounded-2xl border-2 border-dashed border-gray-300 bg-gray-50/50">
            <div className="w-16 h-16 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-compress-arrows-alt text-gray-400 text-2xl"></i>
            </div>
            <h4 className="text-lg font-bold text-gray-800 mb-2">No Account Selected</h4>
            <p className="text-gray-600">
              Please select an account from the sidebar to view rightsizing recommendations.
            </p>
          </div>
        )}

        {/* Tabs */}
        {selectedAccountId && (
          <>
            <AriaTabGroup
              tabs={[
                { id: 'aws',    label: 'AWS Recommendations',  icon: 'fab fa-aws' },
                { id: 'xamops', label: 'XamAi Recommendations', icon: 'fas fa-bolt' },
              ]}
              selectedKey={activeTab}
              onSelectionChange={(key) => setActiveTab(key as 'aws' | 'xamops')}
            />

            {/* AWS Recommendations Tab */}
            {activeTab === 'aws' && (
              !isLoadingAWS && filteredAWSRecommendations.length === 0 ? (
                <div className="rounded-3xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-md p-8 text-center">
                  <div className="w-14 h-14 bg-gray-100 dark:bg-gray-700 rounded-full flex items-center justify-center mx-auto mb-4">
                    <i className="fas fa-inbox text-gray-400 dark:text-gray-500 text-xl"></i>
                  </div>
                  <h3 className="text-xl font-bold text-gray-800 dark:text-gray-200 mb-2">No Recommendations</h3>
                  <p className="text-sm text-gray-500 dark:text-gray-400 mb-5">No rightsizing recommendations available at this time.</p>
                  <div className="inline-flex items-start gap-2 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700 rounded-xl px-4 py-3 text-left max-w-md mx-auto">
                    <i className="fas fa-exclamation-triangle text-amber-500 mt-0.5 flex-shrink-0 text-sm"></i>
                    <p className="text-xs text-amber-700 dark:text-amber-300">
                      If you expected recommendations, ensure <strong>AWS Cost Optimization Hub</strong> is enabled in your AWS Cost Management console.
                    </p>
                  </div>
                </div>
              ) : (
                <div className="fade-in">
                  <AWSRecommendationsTable
                    recommendations={filteredAWSRecommendations}
                    isLoading={isLoadingAWS}
                    canApply={canApply}
                    onApply={handleApplyRequest}
                  />
                </div>
              )
            )}

            {/* XamOps Recommendations Tab */}
            {activeTab === 'xamops' && (
              !isLoadingXamOps && xamopsRecommendations.length === 0 ? (
                <div className="rounded-3xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-md p-8 text-center">
                  <div className="w-14 h-14 bg-gray-100 dark:bg-gray-700 rounded-full flex items-center justify-center mx-auto mb-4">
                    <i className="fas fa-lightbulb text-gray-400 dark:text-gray-500 text-xl"></i>
                  </div>
                  <h3 className="text-xl font-bold text-gray-800 dark:text-gray-200 mb-2">No XamOps Recommendations</h3>
                  <p className="text-sm text-gray-500 dark:text-gray-400">Could not load custom recommendations at this time.</p>
                </div>
              ) : (
                <div className="fade-in">
                  <XamOpsRecommendationsTable
                    recommendations={xamopsRecommendations}
                    isLoading={isLoadingXamOps}
                    canApply={canApply}
                    onApply={handleApplyRequest}
                  />
                </div>
              )
            )}
          </>
        )}
      </div>

      {/* Apply Rightsizing Confirmation Dialog */}
      <AlertDialog
        isOpen={applyTarget !== null}
        title="Apply Rightsizing?"
        variant="warning"
        confirmLabel={isApplying ? 'Applying…' : 'Apply'}
        description={
          applyTarget && (
            <>
              Instance <strong>{applyTarget.instanceId}</strong> will be{' '}
              <strong>stopped</strong>, resized from{' '}
              <strong>{applyTarget.currentType}</strong> to{' '}
              <strong>{applyTarget.targetType}</strong>, then{' '}
              <strong>restarted</strong>. This may take a few minutes.
            </>
          )
        }
        onConfirm={handleApplyConfirm}
        onCancel={() => setApplyTarget(null)}
      />
    </main>
  );
};

export default RightsizingPage;
