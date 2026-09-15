import React from 'react';
import { Icon } from '@iconify/react';
import { AzureFinOpsReportData } from '../../types/azureFinops';

interface AzureFinOpsInsightsSectionProps {
  reportData: AzureFinOpsReportData | null;
  isLoading: boolean;
}

const impactBadgeCls = (impact: string): string => {
  switch (impact?.toLowerCase()) {
    case 'high':   return 'bg-red-50 text-red-600 border border-red-100';
    case 'medium': return 'bg-amber-50 text-amber-600 border border-amber-100';
    case 'low':    return 'bg-blue-50 text-blue-600 border border-blue-100';
    default:       return 'bg-gray-50 text-gray-500 border border-gray-100';
  }
};

const formatCurrency = (v: number): string =>
  '₹' + v.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * Formats an amount whose currency is not known.
 *
 * Anomaly amounts come from the Azure cost export in the enrollment's billing currency, which could
 * be USD, INR, EUR or anything else. Reusing the ₹ formatter above would label a USD figure as
 * rupees — the same misstatement that was just removed from the GCP disk page and refused on the
 * reservation cards — so the number is shown bare and the unit is captioned once beneath the table.
 */
const formatAmountNoUnit = (v: number): string =>
  (Number.isFinite(v) ? v : 0).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

const AzureFinOpsInsightsSection: React.FC<AzureFinOpsInsightsSectionProps> = ({ reportData, isLoading }) => {
  const rightsizingItems = reportData?.rightsizingItems ?? [];
  const wasteItems = reportData?.wasteItems ?? [];
  const costAnomalies = reportData?.costAnomalies ?? [];
  // Distinguishes "spend looks normal" from "detection never ran". Without it an empty anomaly list
  // renders as a clean bill of health, which is what this whole feature exists to stop.
  const anomaliesUnavailable = reportData?.costAnomaliesUnavailableReason ?? null;

  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 animate-pulse">
        <div className="h-4 bg-[rgba(0,0,0,0.04)] rounded-lg w-1/3 mb-4" />
        <div className="space-y-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-10 bg-[rgba(0,0,0,0.04)] rounded-lg" />
          ))}
        </div>
      </div>
    );
  }

  const totalItems =
    rightsizingItems.length + wasteItems.length + costAnomalies.length + (anomaliesUnavailable ? 1 : 0);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 widget-panel-hover">
      {/* Header */}
      <div className="flex items-center gap-3 mb-5">
        <div
          className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]"
          style={{ backgroundColor: '#F59E0B14' }}
        >
          <Icon icon="mdi:lightbulb-on" width={16} color="#D97706" />
        </div>
        <div>
          <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Insights &amp; Opportunities</h3>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">Cost anomalies, rightsizing and waste optimization</p>
        </div>
      </div>

      {totalItems === 0 ? (
        <div className="flex flex-col items-center py-12 gap-3">
          <div className="w-12 h-12 bg-green-50 rounded-full flex items-center justify-center">
            <Icon icon="mdi:check-circle" width={28} color="#16A34A" />
          </div>
          <h4 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">No Optimization Opportunities Found</h4>
          <p className="text-[13px] text-[rgba(0,0,0,0.4)]">
            No rightsizing, waste or cost-anomaly findings for this subscription.
          </p>
        </div>
      ) : (
        <div className="space-y-6">
          {/*
            Cost anomalies. Azure had no anomaly detection at all — the dashboard field was hardcoded
            to an empty list and the report DTO had no field for it — so this panel could only ever
            show optimisation opportunities while AWS and GCP also showed spend spikes.
          */}
          {anomaliesUnavailable && (
            <>
              <div className="flex items-start gap-2 rounded-lg border border-amber-100 bg-amber-50 px-4 py-3">
                <Icon icon="mdi:alert-outline" width={16} color="#B45309" className="flex-shrink-0 mt-0.5" />
                <div>
                  <p className="text-[12px] font-semibold text-amber-800">Cost anomaly detection did not run</p>
                  <p className="text-[12px] text-amber-800 mt-0.5">{anomaliesUnavailable}</p>
                </div>
              </div>
              <div className="border-t border-[rgba(0,0,0,0.06)]" />
            </>
          )}

          {costAnomalies.length > 0 && (
            <>
              <div>
                <div className="flex items-center gap-2 mb-3">
                  <Icon icon="mdi:chart-line-variant" width={15} color="rgba(0,0,0,0.45)" />
                  <span className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.45)]">
                    Cost Anomalies
                  </span>
                  <span className="ml-1 text-[11px] font-semibold px-2 py-0.5 rounded-full bg-red-50 text-red-600 border border-red-100">
                    {costAnomalies.length}
                  </span>
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead>
                      <tr>
                        <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Service</th>
                        <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Date</th>
                        <th className="px-4 py-3 text-right text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Spend Above Normal</th>
                      </tr>
                    </thead>
                    <tbody>
                      {costAnomalies.map((anomaly, idx) => (
                        <tr key={anomaly.anomalyId || idx} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                          <td className="px-4 py-3 text-[14px] font-medium text-[rgba(0,0,0,0.88)]">
                            {anomaly.service || '—'}
                          </td>
                          <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.55)]">
                            {anomaly.startDate || '—'}
                          </td>
                          <td className="px-4 py-3 text-right text-[14px] font-semibold text-red-600">
                            {formatAmountNoUnit(anomaly.unexpectedSpend)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-2">
                  A day is flagged when a service costs more than 1.5&times; its trailing seven-day
                  average. The figure shown is the amount above that average, not the whole day, and is
                  in this subscription&rsquo;s billing currency.
                </p>
              </div>

              <div className="border-t border-[rgba(0,0,0,0.06)]" />
            </>
          )}

          {/* Rightsizing Section */}
          <div>
            <div className="flex items-center gap-2 mb-3">
              <Icon icon="mdi:resize" width={15} color="rgba(0,0,0,0.45)" />
              <span className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.45)]">
                Rightsizing Opportunities
              </span>
              <span className="ml-1 text-[11px] font-semibold px-2 py-0.5 rounded-full bg-blue-50 text-blue-600 border border-blue-100">
                {rightsizingItems.length}
              </span>
            </div>
            {rightsizingItems.length === 0 ? (
              <div className="flex items-center gap-2 py-4 text-[13px] text-[rgba(0,0,0,0.4)]">
                <Icon icon="mdi:check-circle-outline" width={16} color="#16A34A" />
                No rightsizing opportunities found
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr>
                      <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Resource</th>
                      <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Type</th>
                      <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Current → Recommended</th>
                      <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Impact</th>
                      <th className="px-4 py-3 text-right text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Monthly Savings</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rightsizingItems.map((item, idx) => (
                      <tr key={idx} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                        <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)] font-medium max-w-[180px] truncate">
                          {item.resourceName || '—'}
                        </td>
                        <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.55)]">
                          {item.resourceType || '—'}
                        </td>
                        <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)]">
                          <span className="font-mono text-[12px]">{item.currentSku || '—'}</span>
                          <span className="mx-1 text-[rgba(0,0,0,0.3)]">→</span>
                          <span className="font-mono text-[12px] text-green-600 font-semibold">{item.targetSku || '—'}</span>
                        </td>
                        <td className="px-4 py-3">
                          <span className={`text-[11px] font-semibold px-2 py-0.5 rounded-full ${impactBadgeCls(item.impact)}`}>
                            {item.impact || '—'}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-right text-[14px] font-semibold text-green-600">
                          {formatCurrency(item.monthlySavings)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* Divider */}
          <div className="border-t border-[rgba(0,0,0,0.06)]" />

          {/* Waste Section */}
          <div>
            <div className="flex items-center gap-2 mb-3">
              <Icon icon="mdi:delete-outline" width={15} color="rgba(0,0,0,0.45)" />
              <span className="text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.45)]">
                Waste Resources
              </span>
              <span className="ml-1 text-[11px] font-semibold px-2 py-0.5 rounded-full bg-amber-50 text-amber-600 border border-amber-100">
                {wasteItems.length}
              </span>
            </div>
            {wasteItems.length === 0 ? (
              <div className="flex items-center gap-2 py-4 text-[13px] text-[rgba(0,0,0,0.4)]">
                <Icon icon="mdi:check-circle-outline" width={16} color="#16A34A" />
                No waste resources detected
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr>
                      <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Resource</th>
                      <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Type</th>
                      <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Region</th>
                      <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Reason</th>
                      <th className="px-4 py-3 text-right text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Monthly Savings</th>
                    </tr>
                  </thead>
                  <tbody>
                    {wasteItems.map((item, idx) => (
                      <tr key={idx} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                        <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.88)] font-medium max-w-[160px] truncate">
                          {item.resourceName || item.resourceId?.split('/').pop() || '—'}
                        </td>
                        <td className="px-4 py-3">
                          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-amber-50 text-amber-600 border border-amber-100">
                            {item.resourceType || '—'}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.55)]">
                          {item.region || '—'}
                        </td>
                        <td className="px-4 py-3 text-[14px] font-normal text-[rgba(0,0,0,0.55)] max-w-[200px] truncate">
                          {item.reason || '—'}
                        </td>
                        <td className="px-4 py-3 text-right text-[14px] font-semibold text-red-600">
                          {formatCurrency(item.monthlySavings)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default AzureFinOpsInsightsSection;
