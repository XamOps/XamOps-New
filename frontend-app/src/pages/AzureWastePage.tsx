import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureWasteData } from '../hooks/useAzureWasteData';
import {
  AzureWasteHeader,
  AzureWasteKPICards,
  AzureWasteResourceGroup,
} from '../components/AzureWaste';

const AzureWastePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { wasteGroups, allItems, isLoading, isRefreshing, loadWasteData } =
    useAzureWasteData({ selectedAccountId });

  const handleRefresh = () => {
    loadWasteData(true);
  };

  return (
    <main className="p-6 space-y-6">
      <AzureWasteHeader
        selectedAccountId={selectedAccountId}
        isRefreshing={isRefreshing}
        onRefresh={handleRefresh}
      />

      {!selectedAccountId && !isLoading && (
        <div className="glass-card rounded-xl p-16 text-center">
          <div className="flex flex-col items-center">
            <div className="w-16 h-16 bg-blue-100 rounded-full flex items-center justify-center mb-4">
              <i className="fas fa-recycle text-blue-500 text-2xl"></i>
            </div>
            <h3 className="text-lg font-bold text-gray-700 mb-1">No Account Selected</h3>
            <p className="text-sm text-gray-500 max-w-md">
              Please select an Azure account from the sidebar to scan for waste and idle resources.
            </p>
          </div>
        </div>
      )}

      {selectedAccountId && (
        <>
          <AzureWasteKPICards allItems={allItems} isLoading={isLoading} />

          {isLoading && (
            <div className="space-y-4">
              {[1, 2, 3].map(i => (
                <div key={i} className="glass-card rounded-xl p-6 animate-pulse">
                  <div className="flex items-center gap-3 mb-4">
                    <div className="w-8 h-8 rounded-lg bg-gray-200"></div>
                    <div className="h-4 bg-gray-200 rounded w-32"></div>
                    <div className="h-5 bg-gray-200 rounded-full w-16"></div>
                  </div>
                  <div className="space-y-3">
                    {[1, 2].map(j => (
                      <div key={j} className="h-4 bg-gray-100 rounded w-full"></div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}

          {!isLoading && allItems.length === 0 && (
            <div className="glass-card rounded-xl p-16 text-center">
              <div className="flex flex-col items-center">
                <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mb-4">
                  <i className="fas fa-check-circle text-green-500 text-2xl"></i>
                </div>
                <h3 className="text-lg font-bold text-gray-700 mb-1">No Waste Detected</h3>
                <p className="text-sm text-gray-500 max-w-md">
                  Your Azure resources appear clean — no idle disks, stopped VMs, orphaned IPs, or unattached NICs found.
                </p>
              </div>
            </div>
          )}

          {!isLoading && wasteGroups.length > 0 && (
            <div className="space-y-4">
              {wasteGroups.map(group => (
                <AzureWasteResourceGroup key={group.type} group={group} />
              ))}
            </div>
          )}
        </>
      )}
    </main>
  );
};

export default AzureWastePage;
