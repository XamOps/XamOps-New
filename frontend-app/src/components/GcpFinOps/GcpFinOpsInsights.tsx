/**
 * GCP FinOps Insights Component
 *
 * Displays cost anomalies, rightsizing recommendations, and wasted resources
 */

import React from 'react';
import type { GcpCostAnomaly, GcpRightsizingRecommendation, GcpWastedResource } from '../../types/gcpFinops';

interface GcpFinOpsInsightsProps {
  costAnomalies: GcpCostAnomaly[];
  rightsizingRecommendations: GcpRightsizingRecommendation[];
  wastedResources: GcpWastedResource[];
}

const formatCurrency = (value: number): string => {
  return value.toLocaleString('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

const GcpFinOpsInsights: React.FC<GcpFinOpsInsightsProps> = ({
  costAnomalies,
  rightsizingRecommendations,
  wastedResources,
}) => {
  const hasAnyInsights =
    (costAnomalies && costAnomalies.length > 0) ||
    (rightsizingRecommendations && rightsizingRecommendations.length > 0) ||
    (wastedResources && wastedResources.length > 0);

  if (!hasAnyInsights) {
    return null;
  }

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md fade-in">
      <h3 className="text-xl font-bold text-gray-800 mb-6">Insights & Opportunities</h3>

      {/* Cost Anomalies */}
      {costAnomalies && costAnomalies.length > 0 && (
        <div className="mb-8">
          <h4 className="text-lg font-semibold text-gray-800 mb-4">
            <i className="fas fa-exclamation-triangle text-red-500 mr-2"></i> Cost Anomaly Detection
          </h4>
          <div className="overflow-x-auto rounded-lg border border-gray-200">
            <table className="w-full text-sm text-left text-gray-600">
              <thead className="text-xs text-gray-700 uppercase bg-gray-50">
                <tr>
                  <th className="px-6 py-4">Service</th>
                  <th className="px-6 py-4">Date Range</th>
                  <th className="px-6 py-4">Unexpected Spend</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {costAnomalies.map((anomaly) => (
                  <tr key={anomaly.anomalyId} className="hover:bg-gray-50">
                    <td className="px-6 py-4 font-semibold">{anomaly.service}</td>
                    <td className="px-6 py-4">
                      {anomaly.startDate} to {anomaly.endDate}
                    </td>
                    <td className="px-6 py-4 text-red-600 font-bold">{formatCurrency(anomaly.unexpectedSpend)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Rightsizing Recommendations */}
      {rightsizingRecommendations && rightsizingRecommendations.length > 0 && (
        <div className="mb-8">
          <h4 className="text-lg font-semibold text-gray-800 mb-4">
            <i className="fas fa-cogs text-green-500 mr-2"></i> Rightsizing Recommendations
          </h4>
          <div className="overflow-x-auto rounded-lg border border-gray-200">
            <table className="w-full text-sm text-left text-gray-600">
              <thead className="text-xs text-gray-700 uppercase bg-gray-50">
                <tr>
                  <th className="px-6 py-4">Resource Name</th>
                  <th className="px-6 py-4">Service</th>
                  <th className="px-6 py-4">Location</th>
                  <th className="px-6 py-4">Current</th>
                  <th className="px-6 py-4">Recommended</th>
                  <th className="px-6 py-4">Type</th>
                  <th className="px-6 py-4">Impact</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {rightsizingRecommendations.map((rec) => (
                  <tr key={rec.recommendationId || rec.resourceName} className="hover:bg-gray-50">
                    <td className="px-6 py-4 font-medium text-gray-800">{rec.resourceName}</td>
                    <td className="px-6 py-4">{rec.service}</td>
                    <td className="px-6 py-4 text-xs text-gray-500">{rec.location || 'global'}</td>
                    <td className="px-6 py-4 text-xs">{rec.currentMachineType}</td>
                    <td className="px-6 py-4 text-xs text-blue-700 font-semibold">{rec.recommendedMachineType}</td>
                    <td className="px-6 py-4">
                      <span
                        className={`px-2 py-1 text-xs rounded-full font-semibold ${
                          rec.recommendationType === 'PERFORMANCE_IMPROVEMENT'
                            ? 'bg-orange-100 text-orange-700'
                            : 'bg-green-100 text-green-700'
                        }`}
                      >
                        {rec.recommendationType === 'PERFORMANCE_IMPROVEMENT' ? 'Performance' : 'Cost Savings'}
                      </span>
                    </td>
                    <td
                      className={`px-6 py-4 font-bold ${
                        rec.recommendationType === 'PERFORMANCE_IMPROVEMENT' ? 'text-orange-600' : 'text-green-600'
                      }`}
                    >
                      {formatCurrency(rec.monthlySavings)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Wasted Resources */}
      {wastedResources && wastedResources.length > 0 && (
        <div>
          <h4 className="text-lg font-semibold text-gray-800 mb-4">
            <i className="fas fa-trash-alt text-red-500 mr-2"></i> Wasted Resources
          </h4>
          <div className="overflow-x-auto rounded-lg border border-gray-200">
            <table className="w-full text-sm text-left text-gray-600">
              <thead className="text-xs text-gray-700 uppercase bg-gray-50">
                <tr>
                  <th className="px-6 py-4">Resource ID</th>
                  <th className="px-6 py-4">Type</th>
                  <th className="px-6 py-4">Region</th>
                  <th className="px-6 py-4">Reason</th>
                  <th className="px-6 py-4">Potential Savings</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {wastedResources.map((res) => (
                  <tr key={res.resourceId} className="hover:bg-gray-50">
                    <td className="px-6 py-4 font-mono text-xs">{res.resourceId}</td>
                    <td className="px-6 py-4">{res.resourceType}</td>
                    <td className="px-6 py-4">{res.region}</td>
                    <td className="px-6 py-4">{res.reason}</td>
                    <td className="px-6 py-4 text-red-600 font-bold">{formatCurrency(res.monthlySavings)}</td>
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

export default GcpFinOpsInsights;
