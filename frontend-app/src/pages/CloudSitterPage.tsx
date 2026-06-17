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
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useCloudSitter } from '../hooks/useCloudSitter';
import CloudSitterHeader from '../components/CloudSitter/CloudSitterHeader';
import CloudSitterDashboard from '../components/CloudSitter/CloudSitterDashboard';
import CloudSitterPolicyForm from '../components/CloudSitter/CloudSitterPolicyForm';
import CloudSitterAuditLogTab from '../components/CloudSitter/CloudSitterAuditLogTab';
import AssignPolicyModal from '../components/CloudSitter/AssignPolicyModal';
import ManagePoliciesModal from '../components/CloudSitter/ManagePoliciesModal';

const TABS = [
  { id: 'dashboard', label: 'Instances', icon: 'mdi:server' },
  { id: 'auditLog',  label: 'Audit Log', icon: 'mdi:history' },
] as const;

const CloudSitterPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const cloudSitter = useCloudSitter(selectedAccountId);

  const activeTab = cloudSitter.currentView === 'createPolicy' ? 'dashboard' : cloudSitter.currentView;

  return (
    <main className="flex-1 px-6 pt-4 pb-6">
      {/* Header */}
      <CloudSitterHeader
        currentView={cloudSitter.currentView}
        editMode={cloudSitter.editMode}
        onNavigateToDashboard={() => cloudSitter.setCurrentView('dashboard')}
      />

      {/* Tabs — hidden while editing a policy form */}
      {cloudSitter.currentView !== 'createPolicy' && (
        <div className="flex gap-1 mb-5 border-b border-[rgba(0,0,0,0.08)]">
          {TABS.map((tab) => {
            const isActive = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => cloudSitter.setCurrentView(tab.id)}
                className={`flex items-center gap-1.5 px-4 py-2.5 text-[12px] font-semibold transition-all border-b-2 -mb-px ${
                  isActive
                    ? 'border-indigo-600 text-indigo-600'
                    : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.75)]'
                }`}
              >
                <Icon icon={tab.icon} width={13} />
                {tab.label}
              </button>
            );
          })}
        </div>
      )}

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
                page={cloudSitter.dashboardPage}
                pageSize={cloudSitter.dashboardPageSize}
                totalPages={cloudSitter.dashboardTotalPages}
                filteredCount={cloudSitter.filteredResourcesCount}
                onPageChange={cloudSitter.setDashboardPage}
                onPageSizeChange={cloudSitter.setDashboardPageSize}
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
          {cloudSitter.currentView === 'auditLog' && (
            <motion.div key="auditLog" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <CloudSitterAuditLogTab
                accountId={selectedAccountId}
                logs={cloudSitter.auditLogs}
                isLoading={cloudSitter.auditLoading}
                totalPages={cloudSitter.auditTotalPages}
                page={cloudSitter.auditPage}
                onLoad={cloudSitter.loadAuditLog}
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
