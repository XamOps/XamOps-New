import React from 'react';
import type { AzureReservationRecommendation } from '../../types/azureReservations';

interface AzureReservationRecommendationsProps {
  recommendations: AzureReservationRecommendation[];
}

const formatINR = (value: number) =>
  '₹' + value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const AzureReservationRecommendations: React.FC<AzureReservationRecommendationsProps> = ({
  recommendations,
}) => {
  if (recommendations.length === 0) {
    return (
      <div className="glass-card rounded-xl p-12 text-center shadow-sm">
        <div className="flex flex-col items-center">
          <div className="w-20 h-20 bg-gradient-to-br from-gray-100 to-gray-200 rounded-full flex items-center justify-center mb-5 shadow-sm">
            <i className="fas fa-lightbulb text-gray-400 text-3xl"></i>
          </div>
          <h3 className="text-xl font-black text-gray-800 mb-2">No Recommendations Available</h3>
          <p className="text-gray-500 text-sm max-w-md">
            Recommendations appear when services have significant monthly spend (&gt;₹100).
            Ensure billing data has been ingested and refresh the dashboard.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 mb-2">
        <i className="fas fa-lightbulb text-amber-500"></i>
        <span className="text-sm font-semibold text-gray-600">
          {recommendations.length} service{recommendations.length !== 1 ? 's' : ''} with reservation
          savings potential
        </span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {recommendations.map((rec, idx) => (
          <div
            key={idx}
            className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow border border-blue-50"
          >
            {/* Header */}
            <div className="flex items-start justify-between mb-4">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 bg-blue-100 rounded-lg flex items-center justify-center flex-shrink-0">
                  <i className="fas fa-cloud text-blue-600 text-sm"></i>
                </div>
                <div>
                  <h4 className="font-bold text-gray-900 text-sm leading-tight">{rec.serviceName}</h4>
                  <p className="text-xs text-gray-500 mt-0.5">Consider reserving this service</p>
                </div>
              </div>
              <span className="ml-2 flex-shrink-0 bg-blue-50 text-blue-700 text-xs font-bold px-2.5 py-1 rounded-full border border-blue-100">
                {rec.recommendedTerm}
              </span>
            </div>

            {/* Cost details */}
            <div className="space-y-2 mb-4">
              <div className="flex items-center justify-between">
                <span className="text-xs text-gray-500">Current monthly cost</span>
                <span className="text-sm font-bold text-gray-800">
                  {formatINR(rec.currentMonthlyCost)}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs text-gray-500">Estimated savings</span>
                <span className="text-sm font-bold text-green-600">
                  {formatINR(rec.estimatedMonthlySavings)}/mo
                </span>
              </div>
            </div>

            {/* Savings bar */}
            <div className="mb-3">
              <div className="flex justify-between text-xs text-gray-500 mb-1">
                <span>Savings potential</span>
                <span className="font-semibold text-green-600">
                  ~{rec.estimatedSavingsPercentage.toFixed(0)}%
                </span>
              </div>
              <div className="w-full bg-gray-100 rounded-full h-2">
                <div
                  className="bg-gradient-to-r from-green-400 to-green-600 h-2 rounded-full"
                  style={{ width: `${Math.min(rec.estimatedSavingsPercentage, 100)}%` }}
                ></div>
              </div>
            </div>

            {/* Reason */}
            <p className="text-xs text-gray-500 italic">{rec.reason}</p>
          </div>
        ))}
      </div>
    </div>
  );
};

export default AzureReservationRecommendations;
