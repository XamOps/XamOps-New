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

import React, { useState } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useRightsizingData } from '../hooks/useRightsizingData';

// Component imports
import RightsizingHeader from '../components/Rightsizing/RightsizingHeader';
import AWSRecommendationsTable from '../components/Rightsizing/AWSRecommendationsTable';
import XamOpsRecommendationsTable from '../components/Rightsizing/XamOpsRecommendationsTable';

export const RightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

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
    <main className="flex-1 flex flex-col min-h-screen bg-transparent">
      <RightsizingHeader isRefreshing={isRefreshing} onRefresh={refreshAll} />

      <div className="flex-1 p-8 space-y-8">
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
            <div className="flex border-b border-gray-200">
              <button
                onClick={() => setActiveTab('aws')}
                className={`tab-button px-6 py-3 text-sm font-semibold ${activeTab === 'aws' ? 'active' : ''
                  }`}
              >
                <i className="fab fa-aws mr-2"></i>AWS Recommendations
              </button>
              <button
                onClick={() => setActiveTab('xamops')}
                className={`tab-button px-6 py-3 text-sm font-semibold ${activeTab === 'xamops' ? 'active' : ''
                  }`}
              >
                <i className="fas fa-bolt mr-2"></i>XamAi Recommendations
              </button>
            </div>

            {/* AWS Recommendations Tab */}
            {activeTab === 'aws' && (
              <div className="fade-in">
                <AWSRecommendationsTable
                  recommendations={filteredAWSRecommendations}
                  isLoading={isLoadingAWS}
                />
              </div>
            )}

            {/* XamOps Recommendations Tab */}
            {activeTab === 'xamops' && (
              <div className="fade-in">
                <XamOpsRecommendationsTable
                  recommendations={xamopsRecommendations}
                  isLoading={isLoadingXamOps}
                />
              </div>
            )}
          </>
        )}
      </div>

    </main>
  );
};

export default RightsizingPage;
