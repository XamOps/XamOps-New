import React from 'react';
import { LaunchAnalyticsData } from '../../hooks/useLaunchAnalytics';

interface LaunchAnalyticsKPIsProps {
  data: LaunchAnalyticsData;
}

const LaunchAnalyticsKPIs: React.FC<LaunchAnalyticsKPIsProps> = ({ data }) => {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-6 mb-6">
      {/* Total Attempts Card */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-gray-100 hover:shadow-lg transition-all duration-300 transform hover:-translate-y-1">
        <div className="flex items-center justify-between mb-4">
          <div className="p-3 bg-gradient-to-br from-blue-50 to-blue-100 rounded-xl">
            <i className="fas fa-rocket text-blue-600 text-2xl"></i>
          </div>
          <div className="text-right">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1">
              Total Attempts
            </p>
            <h3 className="text-4xl font-bold text-gray-900 tracking-tight">
              {data.totalAttempts.toLocaleString()}
            </h3>
          </div>
        </div>
        <div className="flex items-center text-xs text-gray-500 font-medium">
          <i className="fas fa-calendar-alt mr-2 text-blue-500"></i>
          <span>Selected Period</span>
        </div>
      </div>

      {/* Success Rate Card */}
      <div className="bg-gradient-to-br from-emerald-500 via-green-500 to-teal-600 rounded-2xl shadow-lg p-6 text-white hover:shadow-xl transition-all duration-300 transform hover:-translate-y-1">
        <div className="flex items-center justify-between mb-4">
          <div className="p-3 bg-white/20 backdrop-blur-sm rounded-xl shadow-inner">
            <i className="fas fa-check-circle text-white text-2xl"></i>
          </div>
          <div className="text-right">
            <p className="text-xs font-semibold text-emerald-100 uppercase tracking-wider mb-1">
              Success Rate
            </p>
            <h3 className="text-4xl font-bold tracking-tight">
              {data.successRate.toFixed(1)}%
            </h3>
          </div>
        </div>
        <div className="w-full bg-white/20 rounded-full h-2.5 overflow-hidden">
          <div
            className="bg-white rounded-full h-2.5 transition-all duration-700 ease-out shadow-lg"
            style={{ width: `${Math.min(data.successRate, 100)}%` }}
          ></div>
        </div>
      </div>

      {/* Successful Launches Card */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-green-100 hover:shadow-lg transition-all duration-300 transform hover:-translate-y-1">
        <div className="flex items-center justify-between mb-4">
          <div className="p-3 bg-gradient-to-br from-green-50 to-green-100 rounded-xl">
            <i className="fas fa-thumbs-up text-green-600 text-2xl"></i>
          </div>
          <div className="text-right">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1">
              Successful
            </p>
            <h3 className="text-4xl font-bold text-green-600 tracking-tight">
              {data.successful.toLocaleString()}
            </h3>
          </div>
        </div>
        {data.totalAttempts > 0 && (
          <div className="flex items-center text-xs text-green-600 font-bold bg-green-50 rounded-lg px-3 py-1.5 w-fit">
            <i className="fas fa-arrow-up mr-1.5"></i>
            <span>{((data.successful / data.totalAttempts) * 100).toFixed(1)}% of total</span>
          </div>
        )}
      </div>

      {/* Failed Launches Card */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-red-100 hover:shadow-lg transition-all duration-300 transform hover:-translate-y-1">
        <div className="flex items-center justify-between mb-4">
          <div className="p-3 bg-gradient-to-br from-red-50 to-red-100 rounded-xl">
            <i className="fas fa-exclamation-triangle text-red-600 text-2xl"></i>
          </div>
          <div className="text-right">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1">
              Failed
            </p>
            <h3 className="text-4xl font-bold text-red-600 tracking-tight">
              {data.failed.toLocaleString()}
            </h3>
          </div>
        </div>
        {data.failed > 0 && (
          <div className="flex items-center text-xs text-red-600 font-bold bg-red-50 rounded-lg px-3 py-1.5 w-fit">
            <i className="fas fa-arrow-down mr-1.5"></i>
            <span>{((data.failed / data.totalAttempts) * 100).toFixed(1)}% of total</span>
          </div>
        )}
      </div>
    </div>
  );
};

export default LaunchAnalyticsKPIs;
