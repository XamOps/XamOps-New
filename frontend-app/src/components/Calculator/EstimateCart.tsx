/**
 * Estimate Cart Component
 * Displays the cart of pricing estimates with total
 */

import React from 'react';
import type { EstimateCartItem } from '../../types/calculator';

interface EstimateCartProps {
  cart: EstimateCartItem[];
  totalCost: number;
  onRemoveItem: (id: number) => void;
}

export const EstimateCart: React.FC<EstimateCartProps> = ({
  cart,
  totalCost,
  onRemoveItem,
}) => {
  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-100 flex flex-col sticky top-8 overflow-hidden">
      {/* Header */}
      <div className="p-6 border-b border-gray-50 flex justify-between items-center bg-gradient-to-r from-gray-50 to-white">
        <h2 className="text-lg font-semibold text-gray-800 flex items-center">
          <div className="w-10 h-10 bg-indigo-50 rounded-lg flex items-center justify-center mr-3">
            <i className="fas fa-shopping-cart text-indigo-600"></i>
          </div>
          Estimate Cart
        </h2>
        {cart.length > 0 && (
          <span className="bg-indigo-100 text-indigo-700 px-3 py-1 rounded-full text-xs font-bold">
            {cart.length} {cart.length === 1 ? 'item' : 'items'}
          </span>
        )}
      </div>

      {/* Cart items */}
      <div className="overflow-y-auto max-h-[500px]">
        {cart.length === 0 ? (
          <div className="px-6 py-16 text-center">
            <div className="w-20 h-20 bg-gray-50 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-shopping-basket text-gray-300 text-3xl"></i>
            </div>
            <p className="text-gray-400 font-medium mb-2">Your cart is empty</p>
            <p className="text-xs text-gray-400">Add estimates to compare and plan costs</p>
          </div>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="bg-gray-50 text-gray-400 uppercase text-[10px] tracking-widest font-bold sticky top-0">
              <tr>
                <th className="px-6 py-4">Service</th>
                <th className="px-6 py-4">Cost/mo</th>
                <th className="px-6 py-4 text-right"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {cart.map((item) => (
                <tr
                  key={item.id}
                  className="hover:bg-gray-50 transition-colors group"
                >
                  <td className="px-6 py-4">
                    <div className="font-semibold text-gray-800 mb-1">
                      {item.serviceName}
                    </div>
                    <div className="text-[10px] text-gray-400 uppercase space-y-0.5">
                      <div className="flex items-center gap-1">
                        <i className="fas fa-map-marker-alt text-[8px]"></i>
                        <span>{item.region}</span>
                      </div>
                      {item.configSummary && (
                        <div className="truncate max-w-[200px]" title={item.configSummary}>
                          {item.configSummary}
                        </div>
                      )}
                    </div>
                  </td>
                  <td className="px-6 py-4">
                    <span className="font-bold text-gray-900">
                      ${item.monthlyCost.toFixed(2)}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-right">
                    <button
                      onClick={() => onRemoveItem(item.id)}
                      className="text-gray-300 hover:text-red-500 transition-colors opacity-0 group-hover:opacity-100"
                      title="Remove from cart"
                    >
                      <i className="fas fa-times-circle text-lg"></i>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Footer with total */}
      <div className="p-6 bg-gradient-to-r from-gray-50 to-white border-t border-gray-100">
        <div className="flex justify-between items-center mb-6">
          <span className="text-gray-500 font-medium text-sm">Total Monthly Estimate</span>
          <span className="text-3xl font-bold text-indigo-600">
            ${totalCost.toFixed(2)}
          </span>
        </div>
        <button
          disabled={cart.length === 0}
          className="w-full bg-gradient-to-r from-indigo-600 to-indigo-700 text-white py-3.5 rounded-xl font-bold hover:from-indigo-700 hover:to-indigo-800 transition-all shadow-lg shadow-indigo-100 flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed disabled:shadow-none"
        >
          <i className="fas fa-file-invoice-dollar"></i>
          Generate Proposal
        </button>
        <p className="text-[10px] text-gray-400 text-center mt-4 leading-relaxed tracking-tight uppercase">
          Estimates are calculated using current provider APIs.
          <br />
          Final billing depends on actual usage.
        </p>
      </div>
    </div>
  );
};
