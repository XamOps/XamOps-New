import React from 'react';
import { Link } from 'react-router-dom';
import { AzureOptimizationData } from '../../types/azureDashboard';

interface AzureOptimizationSectionProps {
  optimization: AzureOptimizationData;
  selectedAccountIds: string[];
  isLoading: boolean;
}

const AzureOptimizationSection: React.FC<AzureOptimizationSectionProps> = ({ optimization, selectedAccountIds, isLoading }) => {
  const formatCurrency = (num: number): string => {
    return '$' + (num != null ? num.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }) : '0.00');
  };

  if (isLoading) {
    return (
      <div className="chart-container p-6 rounded-xl skeleton" style={{ height: '400px' }}></div>
    );
  }

  return (
    <div className="chart-container p-6 rounded-xl fade-in">
      <div className="flex items-center justify-between mb-6">
        <h3 className="text-lg font-bold text-gray-800 flex items-center">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-gray-400"></i>
          <i className="fas fa-lightbulb mr-2 text-yellow-500"></i>
          Optimization Hub
        </h3>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">
        <div className="bg-gradient-to-br from-green-50 to-green-100 border border-green-200 p-6 rounded-xl text-center">
          <div className="flex items-center justify-center mb-3">
            <div className="w-12 h-12 bg-gradient-to-br from-green-500 to-green-600 rounded-xl flex items-center justify-center">
              <i className="fas fa-piggy-bank text-white text-xl"></i>
            </div>
          </div>
          <p className="text-sm text-green-700 font-semibold uppercase tracking-wide">Potential Savings</p>
          <p className="text-3xl font-bold text-green-800 my-2">{formatCurrency(optimization.summary.totalPotentialSavings)}</p>
          <p className="text-sm text-green-600">per month</p>
        </div>
        <div className="bg-gradient-to-br from-red-50 to-red-100 border border-red-200 p-6 rounded-xl text-center">
          <div className="flex items-center justify-center mb-3">
            <div className="w-12 h-12 bg-gradient-to-br from-red-500 to-red-600 rounded-xl flex items-center justify-center">
              <i className="fas fa-exclamation-triangle text-white text-xl"></i>
            </div>
          </div>
          <p className="text-sm text-red-700 font-semibold uppercase tracking-wide">Critical Alerts</p>
          <p className="text-3xl font-bold text-red-800 my-2">{optimization.summary.criticalAlertsCount}</p>
          <p className="text-sm text-red-600">items to review</p>
        </div>
      </div>

      {/* VM Recommendations */}
      {optimization.vmRecommendations && optimization.vmRecommendations.length > 0 && (
        <div className="optimization-card rounded-xl p-6 border-t-4 border-orange-500 mt-6">
          <div className="flex items-center justify-between mb-4">
            <h4 className="font-bold text-lg text-gray-800 flex items-center">
              <div className="w-8 h-8 bg-gradient-to-br from-orange-500 to-orange-600 rounded-lg flex items-center justify-center mr-3">
                <i className="fas fa-server text-white text-sm"></i>
              </div>
              Virtual Machine Right-Sizing
            </h4>
            <span className="bg-orange-100 text-orange-800 text-xs font-semibold px-2 py-1 rounded-full">{optimization.vmRecommendations.length} recommendations</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 rounded-lg">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold text-gray-700 rounded-l-lg">Resource</th>
                  <th className="px-4 py-3 text-left font-semibold text-gray-700">Recommendation</th>
                  <th className="px-4 py-3 text-right font-semibold text-gray-700 rounded-r-lg">Monthly Savings</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {optimization.vmRecommendations.slice(0, 3).map((rec, index) => (
                  <tr key={index} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 font-mono text-xs text-gray-600">{rec.resourceId}</td>
                    <td className="px-4 py-3">
                      <div className="flex items-center space-x-2">
                        <span className="text-gray-600">{rec.currentType}</span>
                        <i className="fas fa-arrow-right text-gray-400"></i>
                        <span className="font-semibold text-orange-600">{rec.recommendedType}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right font-bold text-orange-600">{formatCurrency(rec.estimatedMonthlySavings)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {optimization.vmRecommendations.length > 3 && (
            <div className="mt-4 text-center">
              <Link to={`/rightsizing.html?accountIds=${selectedAccountIds.join(',')}`} className="inline-flex items-center text-sm text-orange-600 hover:text-orange-800 font-medium">
                View all {optimization.vmRecommendations.length} recommendations
                <i className="fas fa-arrow-right ml-1"></i>
              </Link>
            </div>
          )}
        </div>
      )}

      {/* Disk Recommendations */}
      {optimization.diskRecommendations && optimization.diskRecommendations.length > 0 && (
        <div className="optimization-card rounded-xl p-6 border-t-4 border-purple-500 mt-6">
          <div className="flex items-center justify-between mb-4">
            <h4 className="font-bold text-lg text-gray-800 flex items-center">
              <div className="w-8 h-8 bg-gradient-to-br from-purple-500 to-purple-600 rounded-lg flex items-center justify-center mr-3">
                <i className="fas fa-hdd text-white text-sm"></i>
              </div>
              Disk Storage Optimization
            </h4>
            <span className="bg-purple-100 text-purple-800 text-xs font-semibold px-2 py-1 rounded-full">{optimization.diskRecommendations.length} recommendations</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 rounded-lg">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold text-gray-700 rounded-l-lg">Resource</th>
                  <th className="px-4 py-3 text-left font-semibold text-gray-700">Recommendation</th>
                  <th className="px-4 py-3 text-right font-semibold text-gray-700 rounded-r-lg">Monthly Savings</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {optimization.diskRecommendations.slice(0, 3).map((rec, index) => (
                  <tr key={index} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 font-mono text-xs text-gray-600">{rec.resourceId}</td>
                    <td className="px-4 py-3">
                      <div className="flex items-center space-x-2">
                        <span className="text-gray-600">{rec.currentType}</span>
                        <i className="fas fa-arrow-right text-gray-400"></i>
                        <span className="font-semibold text-purple-600">{rec.recommendedType}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right font-bold text-purple-600">{formatCurrency(rec.estimatedMonthlySavings)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {optimization.diskRecommendations.length > 3 && (
            <div className="mt-4 text-center">
              <Link to={`/rightsizing.html?accountIds=${selectedAccountIds.join(',')}`} className="inline-flex items-center text-sm text-purple-600 hover:text-purple-800 font-medium">
                View all {optimization.diskRecommendations.length} recommendations
                <i className="fas fa-arrow-right ml-1"></i>
              </Link>
            </div>
          )}
        </div>
      )}

      {/* Function Recommendations */}
      {optimization.functionRecommendations && optimization.functionRecommendations.length > 0 && (
        <div className="optimization-card rounded-xl p-6 border-t-4 border-yellow-500 mt-6">
          <div className="flex items-center justify-between mb-4">
            <h4 className="font-bold text-lg text-gray-800 flex items-center">
              <div className="w-8 h-8 bg-gradient-to-br from-yellow-500 to-yellow-600 rounded-lg flex items-center justify-center mr-3">
                <i className="fas fa-bolt text-white text-sm"></i>
              </div>
              Function App Optimization
            </h4>
            <span className="bg-yellow-100 text-yellow-800 text-xs font-semibold px-2 py-1 rounded-full">{optimization.functionRecommendations.length} recommendations</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 rounded-lg">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold text-gray-700 rounded-l-lg">Resource</th>
                  <th className="px-4 py-3 text-left font-semibold text-gray-700">Recommendation</th>
                  <th className="px-4 py-3 text-right font-semibold text-gray-700 rounded-r-lg">Monthly Savings</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {optimization.functionRecommendations.slice(0, 3).map((rec, index) => (
                  <tr key={index} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 font-mono text-xs text-gray-600">{rec.resourceId}</td>
                    <td className="px-4 py-3">
                      <div className="flex items-center space-x-2">
                        <span className="text-gray-600">{rec.currentType}</span>
                        <i className="fas fa-arrow-right text-gray-400"></i>
                        <span className="font-semibold text-yellow-600">{rec.recommendedType}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right font-bold text-yellow-600">{formatCurrency(rec.estimatedMonthlySavings)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Cost Anomalies */}
      {optimization.costAnomalies && optimization.costAnomalies.length > 0 && (
        <div className="anomaly-card rounded-xl p-6 border-t-4 border-red-500 mt-6">
          <div className="flex items-center justify-between mb-4">
            <h4 className="font-bold text-lg text-gray-800 flex items-center">
              <div className="w-8 h-8 bg-gradient-to-br from-red-500 to-red-600 rounded-lg flex items-center justify-center mr-3">
                <i className="fas fa-exclamation-triangle text-white text-sm"></i>
              </div>
              Cost Anomalies
            </h4>
            <span className="bg-red-100 text-red-800 text-xs font-semibold px-2 py-1 rounded-full">{optimization.costAnomalies.length} detected</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 rounded-lg">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold text-gray-700 rounded-l-lg">Service</th>
                  <th className="px-4 py-3 text-left font-semibold text-gray-700">Date</th>
                  <th className="px-4 py-3 text-right font-semibold text-gray-700 rounded-r-lg">Unexpected Spend</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {optimization.costAnomalies.slice(0, 3).map((a, index) => (
                  <tr key={index} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 font-semibold text-gray-800">{a.service}</td>
                    <td className="px-4 py-3 text-gray-600">{a.startDate}</td>
                    <td className="px-4 py-3 text-right font-bold text-red-600">{formatCurrency(a.unexpectedSpend)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
};

export default AzureOptimizationSection;
