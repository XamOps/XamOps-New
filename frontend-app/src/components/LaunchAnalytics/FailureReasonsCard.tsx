import React from 'react';
import { FailureReason } from '../../hooks/useLaunchAnalytics';

interface FailureReasonsCardProps {
  data: FailureReason[];
}

const FailureReasonsCard: React.FC<FailureReasonsCardProps> = ({ data }) => {
  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 hover:shadow-md transition-shadow">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h3 className="text-lg font-bold text-gray-900 mb-1">Top Failure Reasons</h3>
          <p className="text-xs text-gray-500">Most common causes of launch failures</p>
        </div>
        <div className="p-3 bg-gradient-to-br from-red-50 to-orange-50 rounded-xl">
          <i className="fas fa-bug text-red-600 text-xl"></i>
        </div>
      </div>

      {data.length > 0 ? (
        <div className="space-y-3">
          {data.map((reason, index) => (
            <div
              key={index}
              className="group flex items-center justify-between p-4 bg-gradient-to-r from-gray-50 to-gray-100 rounded-xl hover:from-red-50 hover:to-orange-50 transition-all duration-300 border border-gray-100 hover:border-red-200 cursor-pointer"
            >
              <div className="flex items-center gap-4 flex-1">
                <div className="flex items-center justify-center w-10 h-10 rounded-full bg-gradient-to-br from-red-100 to-red-200 text-red-700 font-bold text-sm shadow-sm group-hover:shadow-md transition-shadow">
                  {index + 1}
                </div>
                <div className="flex-1">
                  <p className="text-sm font-semibold text-gray-900 mb-1 group-hover:text-red-700 transition-colors">
                    {reason.reason}
                  </p>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-gray-600 font-medium">
                      <i className="fas fa-exclamation-circle text-red-500 mr-1"></i>
                      {reason.count} occurrences
                    </span>
                  </div>
                </div>
              </div>
              <div className="text-right">
                <div className="text-2xl font-bold text-red-600 mb-1">
                  {reason.percentage.toFixed(1)}%
                </div>
                <div className="w-16 bg-gray-200 rounded-full h-1.5 overflow-hidden">
                  <div
                    className="bg-gradient-to-r from-red-500 to-orange-500 h-1.5 rounded-full transition-all duration-500"
                    style={{ width: `${Math.min(reason.percentage, 100)}%` }}
                  ></div>
                </div>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="text-center py-12">
          <div className="inline-flex items-center justify-center w-20 h-20 rounded-full bg-gradient-to-br from-green-100 to-emerald-100 mb-4 shadow-lg">
            <i className="fas fa-check-circle text-green-600 text-3xl"></i>
          </div>
          <p className="text-gray-800 font-bold text-lg mb-2">No failures recorded</p>
          <p className="text-sm text-gray-600 mb-1">All launches successful!</p>
          <div className="inline-flex items-center gap-2 text-emerald-600 font-semibold text-sm mt-2">
            <i className="fas fa-trophy"></i>
            <span>Perfect Performance</span>
          </div>
        </div>
      )}
    </div>
  );
};

export default FailureReasonsCard;
