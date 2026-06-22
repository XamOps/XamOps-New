import React from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureCloudSitter } from '../hooks/useAzureCloudSitter';
import CloudSitterHeader from '../components/CloudSitter/CloudSitterHeader';
import CloudSitterDashboard from '../components/CloudSitter/CloudSitterDashboard';
import CloudSitterPolicyForm from '../components/CloudSitter/CloudSitterPolicyForm';
import AssignPolicyModal from '../components/CloudSitter/AssignPolicyModal';
import ManagePoliciesModal from '../components/CloudSitter/ManagePoliciesModal';

const AzureCloudSitterPage: React.FC = () => {
  const { selectedAccountDisplay } = useAccounts();
  const isAzure = selectedAccountDisplay.provider === 'Azure';
  const subscriptionId = isAzure && selectedAccountDisplay.id !== 'No account selected'
    ? selectedAccountDisplay.id : null;

  const cs = useAzureCloudSitter(subscriptionId);

  if (!isAzure) {
    return (
      <main className="flex-1 px-6 pt-4 pb-6">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:microsoft-azure" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Azure Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Please select an Azure account to use Azure CloudSitter.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 px-6 pt-4 pb-6">
      <CloudSitterHeader
        currentView={cs.currentView}
        editMode={cs.editMode}
        onNavigateToDashboard={() => cs.setCurrentView('dashboard')}
      />

      <div className="flex-1">
        <AnimatePresence mode="wait">
          {cs.currentView === 'dashboard' && (
            <motion.div key="dashboard" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <CloudSitterDashboard
                accountId={subscriptionId}
                isLoading={cs.isLoading}
                searchQuery={cs.searchQuery}
                resources={cs.resources}
                allResources={cs.allResources}
                onSearchChange={cs.setSearchQuery}
                onRefresh={() => cs.loadData(true)}
                onCreatePolicy={cs.openCreatePolicy}
                onManagePolicies={() => cs.setShowManagePoliciesModal(true)}
                onAssignPolicy={cs.openAssignModal}
                provider="AZURE"
                page={cs.dashboardPage}
                pageSize={cs.dashboardPageSize}
                totalPages={cs.dashboardTotalPages}
                filteredCount={cs.filteredResourcesCount}
                onPageChange={cs.setDashboardPage}
                onPageSizeChange={cs.setDashboardPageSize}
              />
            </motion.div>
          )}
          {cs.currentView === 'createPolicy' && (
            <motion.div key="createPolicy" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <CloudSitterPolicyForm
                policyForm={cs.policyForm}
                policyGrid={cs.policyGrid}
                editMode={cs.editMode}
                isSaving={cs.isSaving}
                isDragging={cs.isDragging}
                onFormChange={cs.setPolicyForm}
                onGridToggle={cs.toggleCell}
                onGridDrag={cs.dragToggle}
                onStartDrag={() => cs.setIsDragging(true)}
                onStopDrag={() => cs.setIsDragging(false)}
                onResetGrid={cs.resetGrid}
                onSave={cs.savePolicy}
                onCancel={cs.closeCreatePolicy}
                instanceId={cs.formInstanceTarget?.id}
                instanceName={cs.formInstanceTarget?.name}
                instanceRegion={cs.formInstanceTarget?.region}
                accountId={subscriptionId}
                onApplyRecommendation={cs.applyRecommendationToGrid}
              />
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      <AssignPolicyModal
        isOpen={cs.showAssignModal}
        assignTarget={cs.assignTarget}
        policies={cs.policies}
        selectedPolicyId={cs.selectedPolicyId}
        onClose={() => cs.setShowAssignModal(false)}
        onPolicySelect={cs.setSelectedPolicyId}
        onCreateNew={cs.openCreatePolicyFromAssign}
        onSubmit={cs.submitAssignment}
      />

      <ManagePoliciesModal
        isOpen={cs.showManagePoliciesModal}
        policies={cs.policies}
        onClose={() => cs.setShowManagePoliciesModal(false)}
        onEdit={cs.editPolicy}
        onDelete={cs.deletePolicy}
      />
    </main>
  );
};

export default AzureCloudSitterPage;
