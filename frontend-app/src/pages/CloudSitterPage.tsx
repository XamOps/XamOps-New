/**
 * CloudSitter Page
 *
 * Converted from: cloudsitter.html
 *
 * EC2 Instance Scheduling and Cost Optimization
 *
 * Features:
 * - EC2 instance listing with policy assignment
 * - Create/Edit scheduling policies
 * - Weekly schedule grid (drag to toggle)
 * - Timezone support
 * - Cost savings estimation
 * - Policy management
 */

import React from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useAccounts } from '../contexts/AccountContext';
import { useCloudSitter } from '../hooks/useCloudSitter';
import CloudSitterHeader from '../components/CloudSitter/CloudSitterHeader';
import CloudSitterDashboard from '../components/CloudSitter/CloudSitterDashboard';
import CloudSitterPolicyForm from '../components/CloudSitter/CloudSitterPolicyForm';
import AssignPolicyModal from '../components/CloudSitter/AssignPolicyModal';
import ManagePoliciesModal from '../components/CloudSitter/ManagePoliciesModal';

const CloudSitterPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const cloudSitter = useCloudSitter(selectedAccountId);

  return (
    <main className="flex-1 px-6 pt-4 pb-6">
      {/* Header */}
      <CloudSitterHeader
        currentView={cloudSitter.currentView}
        editMode={cloudSitter.editMode}
        onNavigateToDashboard={() => cloudSitter.setCurrentView('dashboard')}
      />

      {/* Content */}
      <div className="flex-1">
        <AnimatePresence mode="wait">
          {cloudSitter.currentView === 'dashboard' && (
            <motion.div key="dashboard" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <CloudSitterDashboard
                accountId={selectedAccountId}
                isLoading={cloudSitter.isLoading}
                searchQuery={cloudSitter.searchQuery}
                resources={cloudSitter.resources}
                allResources={cloudSitter.allResources}
                onSearchChange={cloudSitter.setSearchQuery}
                onRefresh={() => cloudSitter.loadData(true)}
                onCreatePolicy={cloudSitter.openCreatePolicy}
                onManagePolicies={() => cloudSitter.setShowManagePoliciesModal(true)}
                onAssignPolicy={cloudSitter.openAssignModal}
              />
            </motion.div>
          )}
          {cloudSitter.currentView === 'createPolicy' && (
            <motion.div key="createPolicy" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <CloudSitterPolicyForm
                policyForm={cloudSitter.policyForm}
                policyGrid={cloudSitter.policyGrid}
                editMode={cloudSitter.editMode}
                isSaving={cloudSitter.isSaving}
                isDragging={cloudSitter.isDragging}
                onFormChange={cloudSitter.setPolicyForm}
                onGridToggle={cloudSitter.toggleCell}
                onGridDrag={cloudSitter.dragToggle}
                onStartDrag={() => cloudSitter.setIsDragging(true)}
                onStopDrag={() => cloudSitter.setIsDragging(false)}
                onResetGrid={cloudSitter.resetGrid}
                onSave={cloudSitter.savePolicy}
                onCancel={cloudSitter.closeCreatePolicy}
                instanceId={cloudSitter.formInstanceTarget?.id}
                instanceName={cloudSitter.formInstanceTarget?.name}
                instanceRegion={cloudSitter.formInstanceTarget?.region}
                accountId={selectedAccountId}
                onApplyRecommendation={cloudSitter.applyRecommendationToGrid}
              />
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* Assign Policy Modal */}
      <AssignPolicyModal
        isOpen={cloudSitter.showAssignModal}
        assignTarget={cloudSitter.assignTarget}
        policies={cloudSitter.policies}
        selectedPolicyId={cloudSitter.selectedPolicyId}
        onClose={() => cloudSitter.setShowAssignModal(false)}
        onPolicySelect={cloudSitter.setSelectedPolicyId}
        onCreateNew={cloudSitter.openCreatePolicyFromAssign}
        onSubmit={cloudSitter.submitAssignment}
      />

      {/* Manage Policies Modal */}
      <ManagePoliciesModal
        isOpen={cloudSitter.showManagePoliciesModal}
        policies={cloudSitter.policies}
        onClose={() => cloudSitter.setShowManagePoliciesModal(false)}
        onEdit={cloudSitter.editPolicy}
        onDelete={cloudSitter.deletePolicy}
      />
    </main>
  );
};

export default CloudSitterPage;
