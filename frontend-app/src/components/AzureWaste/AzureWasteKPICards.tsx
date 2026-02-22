import React from 'react';
import type { AzureWasteItem } from '../../types/azureWaste';

interface AzureWasteKPICardsProps {
  allItems: AzureWasteItem[];
  isLoading: boolean;
}

const formatINR = (value: number) =>
  '₹' + value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function topCategory(items: AzureWasteItem[]): string {
  if (items.length === 0) return '—';
  const counts: Record<string, number> = {};
  for (const item of items) {
    const t = item.resourceType || 'Other';
    counts[t] = (counts[t] || 0) + 1;
  }
  return Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0] ?? '—';
}

const SkeletonCard: React.FC = () => (
  <div className="glass-card rounded-xl p-5 shadow-sm animate-pulse">
    <div className="flex items-center justify-between mb-3">
      <div className="h-3 bg-gray-200 rounded w-24"></div>
      <div className="w-9 h-9 rounded-lg bg-gray-200"></div>
    </div>
    <div className="h-8 bg-gray-200 rounded w-16 mb-1"></div>
    <div className="h-3 bg-gray-100 rounded w-28"></div>
  </div>
);

const AzureWasteKPICards: React.FC<AzureWasteKPICardsProps> = ({ allItems, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <SkeletonCard />
        <SkeletonCard />
        <SkeletonCard />
      </div>
    );
  }

  const totalWaste = allItems.length;
  const totalSavings = allItems.reduce((sum, item) => sum + (item.monthlySavings ?? 0), 0);
  const topCat = topCategory(allItems);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
      {/* Wasted Resources */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-blue-700">
            Wasted Resources
          </span>
          <div className="w-9 h-9 rounded-lg bg-blue-100 flex items-center justify-center">
            <i className="fas fa-trash-alt text-blue-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-blue-600 to-blue-800 bg-clip-text text-transparent">
          {totalWaste}
        </div>
        <p className="text-xs text-gray-500 mt-1">Idle or unused Azure resources</p>
      </div>

      {/* Potential Monthly Savings */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-green-700">
            Potential Monthly Savings
          </span>
          <div className="w-9 h-9 rounded-lg bg-green-100 flex items-center justify-center">
            <i className="fas fa-rupee-sign text-green-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-green-600 to-green-800 bg-clip-text text-transparent">
          {totalWaste > 0 ? formatINR(totalSavings) : '₹0.00'}
        </div>
        <p className="text-xs text-gray-500 mt-1">If all waste is removed</p>
      </div>

      {/* Top Waste Category */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-red-700">
            Top Waste Category
          </span>
          <div className="w-9 h-9 rounded-lg bg-red-100 flex items-center justify-center">
            <i className="fas fa-exclamation-circle text-red-600 text-sm"></i>
          </div>
        </div>
        <div className="text-xl font-black bg-gradient-to-r from-red-600 to-red-800 bg-clip-text text-transparent truncate">
          {topCat}
        </div>
        <p className="text-xs text-gray-500 mt-1">Most frequent waste type</p>
      </div>
    </div>
  );
};

export default AzureWasteKPICards;
