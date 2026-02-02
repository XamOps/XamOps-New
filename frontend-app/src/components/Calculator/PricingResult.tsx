/**
 * Pricing Result Component
 * Displays the calculated pricing estimate with breakdown
 */

import React from 'react';
import type { PricingResult, ServiceConfig } from '../../types/calculator';

interface PricingResultProps {
  result: PricingResult | null;
  serviceConfig: ServiceConfig;
  region: string;
  onAddToCart: () => void;
}

export const PricingResult: React.FC<PricingResultProps> = ({
  result,
  serviceConfig,
  region,
  onAddToCart,
}) => {
  if (!result) return null;

  const total = Number(result.totalMonthly || 0);

  return (
    <div className="space-y-4">
      {/* Header with total cost */}
      <div className="bg-gradient-to-r from-indigo-600 to-indigo-700 rounded-2xl p-6 text-white shadow-lg shadow-indigo-100 flex justify-between items-center">
        <div>
          <p className="text-indigo-100 text-xs font-bold uppercase tracking-widest mb-1">
            Estimated Monthly Cost
          </p>
          <h3 className="text-sm font-medium">{serviceConfig.serviceName} in {region}</h3>
        </div>
        <div className="text-right">
          <div className="text-3xl font-bold">
            ${total.toFixed(2)}
            <span className="text-lg opacity-70 ml-1">USD</span>
          </div>
          <p className="text-xs text-indigo-200 mt-1">per month</p>
        </div>
      </div>

      {/* Breakdown cards */}
      {(result.compute || result.storage || result.requests) && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {result.compute && (
            <div className="bg-white p-5 rounded-xl border border-gray-100 shadow-sm hover:shadow-md transition-shadow">
              <div className="flex items-center gap-3 mb-3">
                <div className="w-10 h-10 bg-blue-50 rounded-lg flex items-center justify-center">
                  <i className="fas fa-microchip text-blue-600"></i>
                </div>
                <div>
                  <div className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">
                    Compute Units
                  </div>
                  <div className="text-sm font-semibold text-gray-800 mt-0.5">
                    {result.compute.details || result.compute.instanceType || 'N/A'}
                  </div>
                </div>
              </div>
              <div className="text-2xl font-bold text-indigo-600">
                ${Number(result.compute.monthlyCost || 0).toFixed(2)}
              </div>
            </div>
          )}

          {result.storage && (
            <div className="bg-white p-5 rounded-xl border border-gray-100 shadow-sm hover:shadow-md transition-shadow">
              <div className="flex items-center gap-3 mb-3">
                <div className="w-10 h-10 bg-green-50 rounded-lg flex items-center justify-center">
                  <i className="fas fa-database text-green-600"></i>
                </div>
                <div>
                  <div className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">
                    Storage Usage
                  </div>
                  <div className="text-sm font-semibold text-gray-800 mt-0.5">
                    {result.storage.sizeGB || result.storage.gb || 0} GB
                  </div>
                </div>
              </div>
              <div className="text-2xl font-bold text-indigo-600">
                ${Number(result.storage.monthlyTotal || result.storage.monthlyCost || 0).toFixed(2)}
              </div>
            </div>
          )}

          {result.requests && (
            <div className="bg-white p-5 rounded-xl border border-gray-100 shadow-sm hover:shadow-md transition-shadow md:col-span-2">
              <div className="flex items-center gap-3 mb-3">
                <div className="w-10 h-10 bg-purple-50 rounded-lg flex items-center justify-center">
                  <i className="fas fa-exchange-alt text-purple-600"></i>
                </div>
                <div>
                  <div className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">
                    API Operations
                  </div>
                  <div className="text-sm font-semibold text-gray-800 mt-0.5">
                    Requests & Data Transfer
                  </div>
                </div>
              </div>
              <div className="text-2xl font-bold text-indigo-600">
                ${Number(result.requests.monthlyTotal || result.requests.monthlyCost || 0).toFixed(2)}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Pricing models table */}
      {result.pricingModels && result.pricingModels.length > 0 && (
        <div className="bg-white rounded-xl border border-gray-100 overflow-hidden shadow-sm">
          <div className="px-4 py-3 bg-gray-50 border-b border-gray-100">
            <h4 className="text-sm font-bold text-gray-700 flex items-center gap-2">
              <i className="fas fa-chart-line text-indigo-600"></i>
              Pricing Model Comparison
            </h4>
          </div>
          <table className="w-full text-left text-xs">
            <thead className="bg-gray-50 text-gray-400 font-bold uppercase tracking-tighter border-b border-gray-100">
              <tr>
                <th className="px-4 py-3">Pricing Model</th>
                <th className="px-4 py-3">Monthly Cost</th>
                <th className="px-4 py-3 text-right">Savings</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {result.pricingModels.map((model, index) => (
                <tr key={index} className="hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3">
                    <span className="font-medium text-gray-700">{model.name}</span>
                  </td>
                  <td className="px-4 py-3">
                    <span className="font-bold text-gray-900">
                      ${Number(model.monthlyTotal || model.monthlyCost).toFixed(2)}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <span className="text-green-600 font-bold">{model.savings}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Add to cart button */}
      <button
        onClick={onAddToCart}
        className="w-full bg-gradient-to-r from-green-600 to-green-700 text-white py-4 rounded-xl font-bold hover:from-green-700 hover:to-green-800 transition-all shadow-lg shadow-green-100 flex items-center justify-center gap-2 transform hover:scale-[1.02] active:scale-[0.98]"
      >
        <i className="fas fa-cart-plus text-lg"></i>
        Add Estimate to Cart
      </button>
    </div>
  );
};
