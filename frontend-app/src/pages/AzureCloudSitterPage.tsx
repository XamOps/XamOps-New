import React from 'react';
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
      <main className="flex-1 flex items-center justify-center min-h-screen bg-gray-50">
        <div className="text-center">
          <i className="fas fa-cloud text-4xl text-gray-300 mb-4" />
          <p className="text-gray-500 text-lg">Please select an Azure account to use Azure CloudSitter.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      <CloudSitterHeader
        currentView={cs.currentView}
        editMode={cs.editMode}
        onNavigateToDashboard={() => cs.setCurrentView('dashboard')}
      />

      <div className="flex-1 p-8 overflow-y-auto">
        {cs.currentView === 'dashboard' && (
          <CloudSitterDashboard
            accountId={subscriptionId}
            isLoading={cs.isLoading}
            searchQuery={cs.searchQuery}
            resources={cs.resources}
            onSearchChange={cs.setSearchQuery}
            onRefresh={() => cs.loadData(true)}
            onCreatePolicy={cs.openCreatePolicy}
            onManagePolicies={() => cs.setShowManagePoliciesModal(true)}
            onAssignPolicy={cs.openAssignModal}
          />
        )}

        {cs.currentView === 'createPolicy' && (
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
        )}
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
