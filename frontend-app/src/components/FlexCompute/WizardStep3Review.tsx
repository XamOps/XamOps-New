/**
 * Wizard Step 3 - Review & Purchase
 *
 * Summary of capacity details, pricing comparison, terms acceptance, and purchase button
 */

import React, { useState } from 'react';
import type { CapacityPricing, SearchCapacityForm } from '../../types/flexCompute';

interface WizardStep3ReviewProps {
  pricing: CapacityPricing;
  searchForm: SearchCapacityForm;
  onPurchase: () => void;
  onBack: () => void;
  isPurchasing: boolean;
}

const formatCurrency = (value: number): string => {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
  }).format(value);
};

const WizardStep3Review: React.FC<WizardStep3ReviewProps> = ({
  pricing,
  searchForm,
  onPurchase,
  onBack,
  isPurchasing,
}) => {
  const [termsAccepted, setTermsAccepted] = useState(false);

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
      {/* Header */}
      <div className="bg-gradient-to-r from-green-600 to-emerald-600 px-6 py-4 text-white">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-white/20 rounded-lg flex items-center justify-center text-sm font-bold">3</div>
          <div>
            <h3 className="font-semibold">Review & Purchase</h3>
            <p className="text-xs text-green-100">Review your capacity details and confirm purchase</p>
          </div>
        </div>
      </div>

      <div className="p-6 space-y-6">
        {/* Capacity Details */}
        <div>
          <h4 className="text-sm font-semibold text-gray-700 uppercase tracking-wide mb-3">
            <i className="fas fa-server mr-2 text-gray-400"></i>Capacity Details
          </h4>
          <div className="bg-gray-50 rounded-xl p-5 grid grid-cols-2 md:grid-cols-3 gap-4">
            <div>
              <p className="text-xs text-gray-500">Name</p>
              <p className="text-sm font-medium text-gray-900">{pricing.capacityName}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Instance Type</p>
              <p className="text-sm font-mono font-medium text-gray-900">{pricing.instanceType}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Region</p>
              <p className="text-sm font-medium text-gray-900">{pricing.region}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Platform</p>
              <p className="text-sm font-medium text-gray-900">{pricing.platform}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Tenancy</p>
              <p className="text-sm font-medium text-gray-900">{pricing.tenancy}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Quantity</p>
              <p className="text-sm font-medium text-gray-900">{pricing.quantity}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Start Date</p>
              <p className="text-sm font-medium text-gray-900">{pricing.startDate}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">End Date</p>
              <p className="text-sm font-medium text-gray-900">{pricing.endDate}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Auto-Renewal</p>
              <p className="text-sm font-medium text-gray-900">{searchForm.autoRenewal ? 'Yes' : 'No'}</p>
            </div>
          </div>
        </div>

        {/* Pricing Comparison */}
        <div>
          <h4 className="text-sm font-semibold text-gray-700 uppercase tracking-wide mb-3">
            <i className="fas fa-tag mr-2 text-gray-400"></i>Pricing
          </h4>
          <div className="bg-gradient-to-br from-green-50 to-emerald-50 rounded-xl p-5 border border-green-200">
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-sm text-gray-600">Our Price (per hour)</span>
                <span className="text-sm font-bold text-green-700">{formatCurrency(pricing.ourPrice)}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-sm text-gray-600">AWS On-Demand Price (per hour)</span>
                <span className="text-sm text-gray-500 line-through">{formatCurrency(pricing.awsPrice)}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-sm text-gray-600">Savings</span>
                <span className="text-sm font-bold text-green-600">
                  <i className="fas fa-arrow-down mr-1"></i>
                  {pricing.savingsPercent.toFixed(1)}%
                </span>
              </div>
              <hr className="border-green-200" />
              <div className="flex items-center justify-between">
                <span className="text-sm text-gray-600">Fees</span>
                <span className="text-sm text-gray-700">{formatCurrency(pricing.fees)}</span>
              </div>
              <div className="flex items-center justify-between pt-2 border-t border-green-300">
                <span className="text-base font-bold text-gray-900">Total</span>
                <span className="text-lg font-bold text-green-700">{formatCurrency(pricing.total)}</span>
              </div>
            </div>
          </div>
        </div>

        {/* Terms */}
        <div className="bg-yellow-50 border border-yellow-200 rounded-xl p-4">
          <label className="flex items-start gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={termsAccepted}
              onChange={(e) => setTermsAccepted(e.target.checked)}
              className="w-4 h-4 text-blue-600 border-gray-300 rounded focus:ring-blue-500 mt-0.5"
            />
            <span className="text-sm text-gray-700">
              I agree to the{' '}
              <a href="#" className="text-blue-600 underline hover:text-blue-800">Terms & Conditions</a>{' '}
              and authorize CloudiDR to manage EC2 capacity in my AWS account as described above.
            </span>
          </label>
        </div>

        {/* Actions */}
        <div className="flex items-center justify-between pt-2">
          <button
            onClick={onBack}
            className="px-6 py-2.5 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
          >
            <i className="fas fa-arrow-left mr-2"></i>
            Back
          </button>
          <button
            onClick={onPurchase}
            disabled={!termsAccepted || isPurchasing}
            className="px-10 py-3 bg-gradient-to-r from-green-600 to-emerald-600 text-white text-sm font-bold rounded-xl hover:from-green-700 hover:to-emerald-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed shadow-lg shadow-green-500/25"
          >
            {isPurchasing ? (
              <><i className="fas fa-spinner fa-spin mr-2"></i>Processing...</>
            ) : (
              <><i className="fas fa-shopping-cart mr-2"></i>Purchase Capacity</>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

export default WizardStep3Review;
