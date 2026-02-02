import React from 'react';
import { RiCoverage, SpCoverage } from '../../hooks/useRispCoverage';

interface RispCoverageTableProps {
  title: string;
  type: 'ri' | 'sp';
  data: RiCoverage[] | SpCoverage[];
}

const RispCoverageTable: React.FC<RispCoverageTableProps> = ({ title, type, data }) => {
  const isRiTable = type === 'ri';
  const riData = isRiTable ? (data as RiCoverage[]) : [];
  const spData = !isRiTable ? (data as SpCoverage[]) : [];

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 hover:shadow-md transition-shadow">
      <div className="flex items-center justify-between mb-6">
        <h3 className="text-lg font-bold text-gray-900">{title}</h3>
        <span className="px-3 py-1 bg-gradient-to-r from-blue-500 to-indigo-600 text-white text-xs rounded-lg font-semibold shadow-sm">
          {data.length} {data.length === 1 ? 'item' : 'items'}
        </span>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm text-left">
          <thead className="text-xs text-gray-500 uppercase bg-gray-50 rounded-lg">
            {isRiTable ? (
              <tr>
                <th className="px-4 py-3 rounded-l-lg font-semibold">Instance Type</th>
                <th className="px-4 py-3 font-semibold">Region/AZ</th>
                <th className="px-4 py-3 rounded-r-lg text-right font-semibold">Coverage</th>
              </tr>
            ) : (
              <tr>
                <th className="px-4 py-3 rounded-l-lg font-semibold">Plan Type</th>
                <th className="px-4 py-3 rounded-r-lg text-right font-semibold">Coverage</th>
              </tr>
            )}
          </thead>
          <tbody className="divide-y divide-gray-100">
            {isRiTable ? (
              riData.length > 0 ? (
                riData.map((item, index) => (
                  <tr key={`${item.instanceType}-${item.availabilityZone}-${index}`} className="hover:bg-blue-50/50 transition-colors">
                    <td className="px-4 py-3 font-medium text-gray-900">
                      <div className="flex items-center">
                        <i className="fas fa-server text-blue-500 mr-2 text-sm"></i>
                        {item.instanceType}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-gray-500">
                      <div className="font-medium">{item.region}</div>
                      <div className="text-xs text-gray-400 mt-0.5">{item.availabilityZone}</div>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <span className="font-bold text-emerald-600">
                        {item.coveragePercent.toFixed(1)}%
                      </span>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={3} className="px-4 py-12 text-center">
                    <div className="flex flex-col items-center">
                      <i className="fas fa-inbox text-4xl text-gray-300 mb-3"></i>
                      <p className="text-gray-500 font-medium">No active RI coverage</p>
                      <p className="text-xs text-gray-400 mt-1">Reserved Instances will appear here once configured</p>
                    </div>
                  </td>
                </tr>
              )
            ) : (
              spData.length > 0 ? (
                spData.map((item, index) => (
                  <tr key={`${item.planType}-${index}`} className="hover:bg-blue-50/50 transition-colors">
                    <td className="px-4 py-3 font-medium text-gray-900">
                      <div className="flex items-center">
                        <i className="fas fa-piggy-bank text-indigo-500 mr-2 text-sm"></i>
                        {item.planType}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <span className="font-bold text-emerald-600">
                        {item.coveragePercent.toFixed(1)}%
                      </span>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={2} className="px-4 py-12 text-center">
                    <div className="flex flex-col items-center">
                      <i className="fas fa-inbox text-4xl text-gray-300 mb-3"></i>
                      <p className="text-gray-500 font-medium">No active SP coverage</p>
                      <p className="text-xs text-gray-400 mt-1">Savings Plans will appear here once configured</p>
                    </div>
                  </td>
                </tr>
              )
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default RispCoverageTable;
