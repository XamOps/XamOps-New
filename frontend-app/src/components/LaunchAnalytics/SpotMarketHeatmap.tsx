import React from 'react';
import { InstanceTypeStats } from '../../hooks/useLaunchAnalytics';

interface SpotMarketHeatmapProps {
  data: InstanceTypeStats[];
}

const SpotMarketHeatmap: React.FC<SpotMarketHeatmapProps> = ({ data }) => {
  const getColorClasses = (successRate: number) => {
    if (successRate >= 95) {
      return {
        bg: 'bg-emerald-50',
        border: 'border-emerald-200',
        text: 'text-emerald-700',
        dot: 'bg-emerald-500',
        ping: 'bg-emerald-400',
        hover: 'hover:border-emerald-300 hover:shadow-emerald-100'
      };
    } else if (successRate >= 80) {
      return {
        bg: 'bg-yellow-50',
        border: 'border-yellow-200',
        text: 'text-yellow-700',
        dot: 'bg-yellow-500',
        ping: 'bg-yellow-400',
        hover: 'hover:border-yellow-300 hover:shadow-yellow-100'
      };
    }
    return {
      bg: 'bg-red-50',
      border: 'border-red-200',
      text: 'text-red-700',
      dot: 'bg-red-500',
      ping: 'bg-red-400',
      hover: 'hover:border-red-300 hover:shadow-red-100'
    };
  };

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 hover:shadow-md transition-shadow">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h3 className="text-lg font-bold text-gray-900 mb-1">Spot Market Heatmap</h3>
          <p className="text-xs text-gray-500">Instance Type Success Rates</p>
        </div>
        <div className="p-3 bg-gradient-to-br from-blue-50 to-indigo-50 rounded-xl">
          <i className="fas fa-th text-blue-600 text-xl"></i>
        </div>
      </div>

      {/* Legend */}
      <div className="flex flex-wrap gap-4 mb-5 pb-5 border-b border-gray-100">
        <div className="flex items-center gap-2">
          <div className="w-3 h-3 rounded-full bg-emerald-500"></div>
          <span className="text-xs font-medium text-gray-600">Excellent (≥95%)</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="w-3 h-3 rounded-full bg-yellow-500"></div>
          <span className="text-xs font-medium text-gray-600">Good (80-95%)</span>
        </div>
        <div className="flex items-center gap-2">
          <div className="w-3 h-3 rounded-full bg-red-500"></div>
          <span className="text-xs font-medium text-gray-600">Poor (&lt;80%)</span>
        </div>
      </div>

      {data.length > 0 ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
          {data.map((item, index) => {
            const colors = getColorClasses(item.successRate);
            return (
              <div
                key={index}
                className={`p-4 rounded-xl border-2 transition-all duration-300 transform hover:scale-105 ${colors.bg} ${colors.border} ${colors.hover} hover:shadow-lg cursor-pointer group`}
              >
                <div className="flex justify-between items-start mb-3">
                  <span className="font-mono text-sm font-bold text-gray-800 truncate max-w-[80%]">
                    {item.instanceType}
                  </span>

                  {/* Animated pulse dot for high-activity instances */}
                  {item.attempts > 10 && (
                    <div className="relative flex h-3 w-3">
                      <span
                        className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${colors.ping}`}
                      ></span>
                      <span className={`relative inline-flex rounded-full h-3 w-3 ${colors.dot}`}></span>
                    </div>
                  )}
                </div>

                <div className="text-right">
                  <div className={`text-2xl font-bold ${colors.text} mb-1`}>
                    {item.successRate.toFixed(0)}%
                  </div>
                  <div className="text-[11px] text-gray-600 font-medium flex items-center justify-end gap-1">
                    <i className="fas fa-rocket text-[10px]"></i>
                    {item.attempts} launches
                  </div>
                </div>

                {/* Tooltip on hover */}
                <div className="mt-3 pt-3 border-t border-gray-200 opacity-0 group-hover:opacity-100 transition-opacity">
                  <div className="flex justify-between text-[10px] text-gray-600">
                    <span>Success: {item.successes}</span>
                    <span>Failed: {item.failures}</span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        <div className="text-center py-12">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-gray-100 mb-3">
            <i className="fas fa-inbox text-gray-400 text-2xl"></i>
          </div>
          <p className="text-gray-600 font-semibold">No instance type data available</p>
          <p className="text-sm text-gray-500 mt-1">Data will appear once launches are recorded</p>
        </div>
      )}
    </div>
  );
};

export default SpotMarketHeatmap;
