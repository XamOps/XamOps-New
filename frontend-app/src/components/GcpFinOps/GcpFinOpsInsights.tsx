/**
 * GCP FinOps Insights Component
 *
 * Displays cost anomalies, rightsizing recommendations, and wasted resources
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { GcpCostAnomaly, GcpRightsizingRecommendation, GcpWastedResource } from '../../types/gcpFinops';

interface GcpFinOpsInsightsProps {
  costAnomalies: GcpCostAnomaly[];
  rightsizingRecommendations: GcpRightsizingRecommendation[];
  wastedResources: GcpWastedResource[];
}

const formatCurrency = (value: number): string => {
  return '₹' + value.toLocaleString('en-IN', {
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
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 widget-panel-hover">
      {/* Section header */}
      <div className="px-0 pt-0 pb-4 flex items-center gap-3">
        <div
          className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]"
          style={{ backgroundColor: '#EA433514' }}
        >
          <Icon icon="mdi:lightbulb-on" width={16} color="#EA4335" />
        </div>
        <div>
          <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Insights & Opportunities</h3>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-red-400 animate-pulse inline-block"></span>
            Cost anomalies, rightsizing, and wasted resources
          </p>
        </div>
      </div>

      {/* Cost Anomalies */}
      {costAnomalies && costAnomalies.length > 0 && (
        <div className="mb-6">
          <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mb-3 flex items-center gap-2">
            <Icon icon="mdi:alert-circle" width={15} color="#EA4335" />
            Cost Anomaly Detection
          </h4>
          <div className="overflow-x-auto rounded-lg border border-[rgba(0,0,0,0.08)]">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)]">
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Service</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Date Range</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Unexpected Spend</th>
                </tr>
              </thead>
              <tbody>
                {costAnomalies.map((anomaly) => (
                  <tr key={anomaly.anomalyId} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                    <td className="px-4 py-3 text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{anomaly.service}</td>
                    <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)]">
                      {anomaly.startDate} to {anomaly.endDate}
                    </td>
                    <td className="px-4 py-3 text-[14px] font-semibold text-red-600">{formatCurrency(anomaly.unexpectedSpend)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Rightsizing Recommendations */}
      {rightsizingRecommendations && rightsizingRecommendations.length > 0 && (
        <div className="mb-6">
          <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mb-3 flex items-center gap-2">
            <Icon icon="mdi:cog" width={15} color="#34A853" />
            Rightsizing Recommendations
          </h4>
          <div className="overflow-x-auto rounded-lg border border-[rgba(0,0,0,0.08)]">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)]">
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Resource Name</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Service</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Location</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Current</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Recommended</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Type</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Impact</th>
                </tr>
              </thead>
              <tbody>
                {rightsizingRecommendations.map((rec) => (
                  <tr key={rec.recommendationId || rec.resourceName} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                    <td className="px-4 py-3 text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{rec.resourceName}</td>
                    <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)]">{rec.service}</td>
                    <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.5)]">{rec.location || 'global'}</td>
                    <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.88)]">{rec.currentMachineType}</td>
                    <td className="px-4 py-3 text-[12px] font-semibold text-blue-700">{rec.recommendedMachineType}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`px-2 py-0.5 text-[11px] rounded-full font-semibold ${
                          rec.recommendationType === 'PERFORMANCE_IMPROVEMENT'
                            ? 'bg-orange-100 text-orange-700'
                            : 'bg-green-100 text-green-700'
                        }`}
                      >
                        {rec.recommendationType === 'PERFORMANCE_IMPROVEMENT' ? 'Performance' : 'Cost Savings'}
                      </span>
                    </td>
                    <td
                      className={`px-4 py-3 text-[14px] font-semibold ${
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
          <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mb-3 flex items-center gap-2">
            <Icon icon="mdi:delete-circle" width={15} color="#EA4335" />
            Wasted Resources
          </h4>
          <div className="overflow-x-auto rounded-lg border border-[rgba(0,0,0,0.08)]">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)]">
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Resource ID</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Type</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Region</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Reason</th>
                  <th className="px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Potential Savings</th>
                </tr>
              </thead>
              <tbody>
                {wastedResources.map((res) => (
                  <tr key={res.resourceId} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                    <td className="px-4 py-3 font-mono text-[12px] text-[rgba(0,0,0,0.88)]">{res.resourceId}</td>
                    <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)]">{res.resourceType}</td>
                    <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)]">{res.region}</td>
                    <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)]">{res.reason}</td>
                    <td className="px-4 py-3 text-[14px] font-semibold text-red-600">{formatCurrency(res.monthlySavings)}</td>
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
