import React from 'react';
import { RegionStats } from '../../hooks/useLaunchAnalytics';

interface RegionSuccessChartProps {
  data: RegionStats[];
}

const RegionSuccessChart: React.FC<RegionSuccessChartProps> = ({ data }) => {
  const getGradientClass = (successRate: number) => {
    if (successRate >= 95) {
      return 'bg-gradient-to-r from-purple-400 via-indigo-500 to-blue-500';
    } else if (successRate >= 80) {
      return 'bg-gradient-to-r from-yellow-400 via-orange-400 to-orange-500';
    }
    return 'bg-gradient-to-r from-red-400 via-red-500 to-red-600';
  };

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 hover:shadow-md transition-shadow">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h3 className="text-lg font-bold text-gray-900 mb-1">Success Rate by Region</h3>
          <p className="text-xs text-gray-500">Geographic distribution of launch success</p>
        </div>
        <div className="p-3 bg-gradient-to-br from-purple-50 to-pink-50 rounded-xl">
          <i className="fas fa-globe text-purple-600 text-xl"></i>
        </div>
      </div>

      {data.length > 0 ? (
        <div className="space-y-5">
          {data.map((item, index) => (
            <div key={index} className="group">
              <div className="flex items-center justify-between mb-2.5">
                <div className="flex items-center gap-3">
                  <div className="flex items-center justify-center w-8 h-8 rounded-full bg-purple-100 text-purple-600 font-bold text-xs">
                    {index + 1}
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <i className="fas fa-map-marker-alt text-purple-500 text-xs"></i>
                      <span className="text-sm font-bold text-gray-800">{item.region}</span>
                    </div>
                    <span className="text-[11px] text-gray-500 font-medium ml-5">
                      {item.attempts} attempts
                    </span>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <div className="text-xs text-gray-500 hidden sm:block">
                    <span className="text-green-600 font-semibold">{item.successes}</span>
                    <span className="mx-1">/</span>
                    <span className="text-red-600 font-semibold">{item.failures}</span>
                  </div>
                  <span className="text-sm font-bold text-purple-600 min-w-[55px] text-right">
                    {item.successRate.toFixed(1)}%
                  </span>
                </div>
              </div>

              <div className="relative w-full bg-gray-100 rounded-full h-4 overflow-hidden shadow-inner">
                <div
                  className={`h-4 rounded-full transition-all duration-700 ease-out flex items-center justify-end pr-2 group-hover:shadow-lg ${getGradientClass(
                    item.successRate
                  )}`}
                  style={{ width: `${Math.min(item.successRate, 100)}%` }}
                >
                  {item.successRate > 20 && (
                    <span className="text-[10px] font-bold text-white">
                      {item.successRate.toFixed(0)}%
                    </span>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="text-center py-12">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-gray-100 mb-3">
            <i className="fas fa-inbox text-gray-400 text-2xl"></i>
          </div>
          <p className="text-gray-600 font-semibold">No region data available</p>
          <p className="text-sm text-gray-500 mt-1">Data will appear once launches are recorded</p>
        </div>
      )}
    </div>
  );
};

export default RegionSuccessChart;
