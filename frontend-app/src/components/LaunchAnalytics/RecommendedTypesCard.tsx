import React from 'react';
import { RecommendedType } from '../../hooks/useLaunchAnalytics';

interface RecommendedTypesCardProps {
  data: RecommendedType[];
}

const RecommendedTypesCard: React.FC<RecommendedTypesCardProps> = ({ data }) => {
  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 hover:shadow-md transition-shadow">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h3 className="text-lg font-bold text-gray-900 mb-1">Recommended Instance Types</h3>
          <p className="text-xs text-gray-500">Highest success rate and availability</p>
        </div>
        <div className="p-3 bg-gradient-to-br from-emerald-50 to-teal-50 rounded-xl">
          <i className="fas fa-star text-emerald-600 text-xl"></i>
        </div>
      </div>

      {data.length > 0 ? (
        <div className="space-y-3">
          {data.map((rec, index) => (
            <div
              key={index}
              className="group p-4 border-2 border-emerald-100 rounded-xl hover:border-emerald-300 hover:bg-gradient-to-r hover:from-emerald-50 hover:to-teal-50 transition-all duration-300 cursor-pointer transform hover:scale-[1.02]"
            >
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-3 flex-1">
                  {/* Star badge for top recommendations */}
                  {index < 3 && (
                    <div className={`flex items-center justify-center w-8 h-8 rounded-full ${
                      index === 0 ? 'bg-gradient-to-br from-yellow-400 to-amber-500' :
                      index === 1 ? 'bg-gradient-to-br from-gray-300 to-gray-400' :
                      'bg-gradient-to-br from-orange-300 to-orange-400'
                    } shadow-md`}>
                      <i className="fas fa-star text-white text-xs"></i>
                    </div>
                  )}

                  <div className="p-2.5 bg-gradient-to-br from-emerald-100 to-emerald-200 rounded-lg group-hover:shadow-md transition-shadow">
                    <i className="fas fa-server text-emerald-600 text-lg"></i>
                  </div>

                  <div className="flex-1">
                    <p className="text-sm font-bold text-gray-900 group-hover:text-emerald-700 transition-colors">
                      {rec.instanceType}
                    </p>
                    <div className="flex items-center gap-2 text-xs text-gray-500 mt-0.5">
                      <i className="fas fa-map-marker-alt text-emerald-500"></i>
                      <span className="font-medium">{rec.region}</span>
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <span className="px-3 py-1.5 bg-gradient-to-r from-emerald-100 to-teal-100 text-emerald-700 rounded-full text-sm font-bold shadow-sm">
                    {rec.successRate.toFixed(1)}%
                  </span>
                </div>
              </div>

              <div className="flex items-center justify-between text-xs pt-3 border-t border-emerald-100">
                <div className="flex items-center gap-3">
                  <span className="flex items-center gap-1.5 text-gray-600 font-medium">
                    <i className="fas fa-bolt text-yellow-500"></i>
                    {rec.avgLaunchTime}
                  </span>
                  <span className="flex items-center gap-1.5 text-gray-600 font-medium">
                    <i className="fas fa-check-circle text-emerald-500"></i>
                    {rec.attempts}
                  </span>
                </div>

                {/* Recommended badge */}
                <span className="px-2 py-1 bg-emerald-600 text-white rounded-md text-[10px] font-bold uppercase tracking-wide">
                  Recommended
                </span>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="text-center py-12">
          <div className="inline-flex items-center justify-center w-20 h-20 rounded-full bg-gradient-to-br from-gray-100 to-gray-200 mb-4">
            <i className="fas fa-lightbulb text-gray-400 text-3xl"></i>
          </div>
          <p className="text-gray-700 font-bold text-lg mb-2">No recommendations available</p>
          <p className="text-sm text-gray-500">Need more data to generate insights</p>
        </div>
      )}
    </div>
  );
};

export default RecommendedTypesCard;
