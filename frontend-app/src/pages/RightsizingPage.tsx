import React, { useState, useCallback, useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useRightsizingData } from '../hooks/useRightsizingData';
import { AlertDialog } from '../components/ui/AlertDialog';
import apiClient from '../utils/apiClient';

import RightsizingHeader from '../components/Rightsizing/RightsizingHeader';
import AWSRecommendationsTable from '../components/Rightsizing/AWSRecommendationsTable';
import XamOpsRecommendationsTable from '../components/Rightsizing/XamOpsRecommendationsTable';
import CohRecommendationsTable from '../components/Rightsizing/CohRecommendationsTable';
import { useTabState } from '../hooks/useTabState';
import {
  fetchCohRecommendations,
  fetchCohStatus,
  type CohRecommendation,
} from '../api/cohApi';

interface ApplyTarget {
  instanceId: string;
  currentType: string;
  targetType: string;
  region: string;
}

export const RightsizingPage: React.FC = () => {
  const { selectedAccountIds, accounts } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const selectedAccount = accounts.find(a => a.id === selectedAccountIds[0]);
  const canApply = selectedAccount?.access === 'read-write';

  const {
    awsRecommendations,
    xamopsRecommendations,
    isLoadingAWS,
    isLoadingXamOps,
    isRefreshing,
    error,
    refreshAll,
  } = useRightsizingData({ selectedAccountId });

  const [activeTab, setActiveTab] = useTabState<'aws' | 'xamops' | 'coh'>('aws');
  const [applyTarget, setApplyTarget] = useState<ApplyTarget | null>(null);
  const [isApplying, setIsApplying] = useState(false);

  // Cost Optimization Hub: cross-service recommendation feed, loaded independently of the
  // EC2/XamAi hook so the existing tabs are untouched. Enablement is probed separately because a
  // disabled Hub needs enable-guidance rather than a bare "no recommendations" empty-state.
  const [cohRecs, setCohRecs] = useState<CohRecommendation[]>([]);
  const [cohEnabled, setCohEnabled] = useState<boolean | null>(null);
  const [cohLoading, setCohLoading] = useState(false);

  const loadCoh = useCallback(async (accountId: string, forceRefresh: boolean) => {
    setCohLoading(true);
    try {
      const [status, recs] = await Promise.all([
        fetchCohStatus(accountId),
        fetchCohRecommendations(accountId, forceRefresh),
      ]);
      setCohEnabled(status.enabled);
      setCohRecs(recs);
    } catch (err) {
      console.error('Failed to load Cost Optimization Hub data:', err);
      setCohEnabled(false);
      setCohRecs([]);
    } finally {
      setCohLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!selectedAccountId) {
      setCohRecs([]);
      setCohEnabled(null);
      return;
    }
    loadCoh(selectedAccountId, false);
  }, [selectedAccountId, loadCoh]);

  const handleRefreshAll = useCallback(() => {
    refreshAll();
    if (selectedAccountId) loadCoh(selectedAccountId, true);
  }, [refreshAll, selectedAccountId, loadCoh]);

  const filteredAWSRecommendations = awsRecommendations.filter(rec => {
    // Drop "no-change" rows: AWS sometimes returns the current type as the best
    // option with $0 savings (e.g. "t4g.small -> t4g.small / $0.00").
    if (!rec.recommendedType || !rec.currentType) return false;
    if (rec.recommendedType.trim().toLowerCase() === rec.currentType.trim().toLowerCase()) return false;
    if (!(rec.estimatedMonthlySavings > 0)) return false;

    if (rec.recommendedType.toLowerCase().includes('optimized')) return false;

    if (rec.recommendationReason) {
      const reason = rec.recommendationReason.toLowerCase();
      if (reason.includes('optimized') || reason.includes('no recommendation')) return false;
    }
    return true;
  });

  const handleApplyRequest = useCallback((instanceId: string, targetType: string, region: string) => {
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

  if (error) {
    return (
      <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
        <RightsizingHeader isRefreshing={false} onRefresh={handleRefreshAll} />
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-rose-50 rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:alert-circle-outline" width={22} color="#DC2626" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">Error Loading Data</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-4">{error.message}</p>
          <button
            onClick={() => window.location.reload()}
            className="px-4 py-2 text-[12px] font-semibold rounded-lg border border-rose-200 text-rose-600 hover:bg-rose-50 transition-colors"
          >
            Try Again
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <RightsizingHeader isRefreshing={isRefreshing || cohLoading} onRefresh={handleRefreshAll} />

      {/* No account */}
      {!selectedAccountId && !isLoadingAWS && !isLoadingXamOps && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:resize" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select an AWS account from the sidebar to view rightsizing recommendations.</p>
        </div>
      )}

      {/* Tabs + content */}
      {selectedAccountId && (
        <>
          {/* Tab switcher */}
          <div className="flex gap-1 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] rounded-xl p-1 w-fit">
            {([
              { id: 'aws', label: 'AWS Recommendations', icon: 'mdi:aws' },
              { id: 'xamops', label: 'XamAi Recommendations', icon: 'mdi:lightning-bolt' },
              { id: 'coh', label: 'Cost Optimization Hub', icon: 'mdi:aws' },
            ] as const).map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`flex items-center gap-2 px-4 py-2 rounded-lg text-[12px] font-semibold transition-all ${
                  activeTab === tab.id
                    ? 'bg-white shadow-sm text-[rgba(0,0,0,0.88)] border border-[rgba(0,0,0,0.08)]'
                    : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'
                }`}
              >
                <Icon icon={tab.icon} width={14} />
                {tab.label}
              </button>
            ))}
          </div>

          <AnimatePresence mode="wait">
            {activeTab === 'aws' && (
              <motion.div
                key="aws"
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -4 }}
                transition={{ duration: 0.18, ease: 'easeOut' }}
              >
                {!isLoadingAWS && filteredAWSRecommendations.length === 0 ? (
                  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
                    <div className="w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3" style={{ backgroundColor: '#16A34A14' }}>
                      <Icon icon="mdi:check-circle-outline" width={24} color="#16A34A" />
                    </div>
                    <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Recommendations</p>
                    <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-4">No rightsizing recommendations available at this time.</p>
                    <div className="inline-flex items-start gap-2 bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 text-left max-w-md mx-auto">
                      <Icon icon="mdi:information-outline" width={15} color="#D97706" className="flex-shrink-0 mt-0.5" />
                      <p className="text-[12px] text-amber-700">
                        Ensure <strong>AWS Cost Optimization Hub</strong> is enabled in your AWS Cost Management console.
                      </p>
                    </div>
                  </div>
                ) : (
                  <AWSRecommendationsTable
                    recommendations={filteredAWSRecommendations}
                    isLoading={isLoadingAWS}
                    canApply={canApply}
                    onApply={handleApplyRequest}
                  />
                )}
              </motion.div>
            )}

            {activeTab === 'xamops' && (
              <motion.div
                key="xamops"
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -4 }}
                transition={{ duration: 0.18, ease: 'easeOut' }}
              >
                {!isLoadingXamOps && xamopsRecommendations.length === 0 ? (
                  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
                    <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
                      <Icon icon="mdi:lightning-bolt-outline" width={24} color="rgba(0,0,0,0.3)" />
                    </div>
                    <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No XamAi Recommendations</p>
                    <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Could not load custom recommendations at this time.</p>
                  </div>
                ) : (
                  <XamOpsRecommendationsTable
                    recommendations={xamopsRecommendations}
                    isLoading={isLoadingXamOps}
                    canApply={canApply}
                    onApply={handleApplyRequest}
                  />
                )}
              </motion.div>
            )}

            {activeTab === 'coh' && (
              <motion.div
                key="coh"
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -4 }}
                transition={{ duration: 0.18, ease: 'easeOut' }}
              >
                {!cohLoading && cohEnabled === false ? (
                  /* Hub not enabled — distinct from "enabled but zero recommendations". */
                  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
                    <div className="w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3" style={{ backgroundColor: '#FF990014' }}>
                      <Icon icon="mdi:toggle-switch-off-outline" width={24} color="#FF9900" />
                    </div>
                    <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">Cost Optimization Hub Not Enabled</p>
                    <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-4">
                      Enable the Hub in this account to see cross-service savings recommendations here.
                    </p>
                    <div className="inline-flex items-start gap-2 bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 text-left max-w-md mx-auto">
                      <Icon icon="mdi:information-outline" width={15} color="#D97706" className="flex-shrink-0 mt-0.5" />
                      <p className="text-[12px] text-amber-700">
                        In the AWS console go to <strong>Billing and Cost Management</strong> &rarr;{' '}
                        <strong>Cost Optimization Hub</strong> and enable it. Recommendations appear after AWS
                        completes its first analysis.
                      </p>
                    </div>
                  </div>
                ) : !cohLoading && cohRecs.length === 0 ? (
                  /* Enabled (or still unknown) but no recommendations. */
                  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
                    <div className="w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3" style={{ backgroundColor: '#16A34A14' }}>
                      <Icon icon="mdi:check-circle-outline" width={24} color="#16A34A" />
                    </div>
                    <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Recommendations</p>
                    <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
                      Cost Optimization Hub has no savings recommendations for this account right now.
                    </p>
                  </div>
                ) : (
                  <CohRecommendationsTable
                    recommendations={cohRecs}
                    isLoading={cohLoading}
                    accountId={selectedAccountId}
                  />
                )}
              </motion.div>
            )}
          </AnimatePresence>
        </>
      )}

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
