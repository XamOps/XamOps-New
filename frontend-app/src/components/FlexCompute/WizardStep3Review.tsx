import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import type { CapacityPricing, SearchCapacityForm, ConfigureCapacityForm } from '../../types/flexCompute';

interface WizardStep3ReviewProps {
  pricing: CapacityPricing;
  searchForm: SearchCapacityForm;
  configureForm: ConfigureCapacityForm;
  onPurchase: () => void;
  onBack: () => void;
  isPurchasing: boolean;
  purchaseError?: string | null;
  reservedAt?: number;
}

const formatCurrency = (value: number): string =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2 }).format(value);

const WizardStep3Review: React.FC<WizardStep3ReviewProps> = ({
  pricing,
  searchForm,
  configureForm,
  onPurchase,
  onBack,
  isPurchasing,
  purchaseError,
  reservedAt,
}) => {
  const [termsAccepted, setTermsAccepted] = useState(false);

  const initialSeconds = reservedAt
    ? Math.max(0, 900 - Math.floor((Date.now() - reservedAt) / 1000))
    : 900;

  const [timeRemaining, setTimeRemaining] = useState(initialSeconds);

  useEffect(() => {
    const timer = setInterval(() => {
      setTimeRemaining((prev) => {
        if (prev <= 0) { clearInterval(timer); return 0; }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const formatTime = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${String(secs).padStart(2, '0')}`;
  };

  const isTimeWarning  = timeRemaining <= 300;
  const isTimeCritical = timeRemaining <= 60;

  const timerColor = isTimeCritical ? 'border-rose-300 bg-rose-50'
    : isTimeWarning ? 'border-amber-300 bg-amber-50'
    : 'border-blue-200 bg-blue-50';
  const timerIconBg = isTimeCritical ? 'bg-rose-100' : isTimeWarning ? 'bg-amber-100' : 'bg-blue-100';
  const timerIconColor = isTimeCritical ? '#DC2626' : isTimeWarning ? '#D97706' : '#3B82F6';
  const timerTextColor = isTimeCritical ? 'text-rose-900' : isTimeWarning ? 'text-amber-900' : 'text-blue-900';
  const timerSubColor  = isTimeCritical ? 'text-rose-600'  : isTimeWarning ? 'text-amber-600'  : 'text-blue-600';

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.01)]">
        <div className="flex items-center gap-3">
          <div className="w-7 h-7 bg-indigo-600 rounded-lg flex items-center justify-center text-[12px] font-bold text-white">3</div>
          <div>
            <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Review & Purchase</h3>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Review your capacity details and confirm purchase</p>
          </div>
        </div>
      </div>

      <div className="p-6 space-y-6">
        {/* Countdown Timer */}
        <div className={`rounded-xl p-4 border-2 transition-all ${timerColor}`}>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className={`w-11 h-11 rounded-full flex items-center justify-center ${timerIconBg} ${isTimeCritical ? 'animate-pulse' : ''}`}>
                <Icon icon="mdi:clock-outline" width={22} color={timerIconColor} />
              </div>
              <div>
                <p className={`text-[13px] font-semibold ${timerTextColor}`}>
                  {timeRemaining === 0 ? 'Reservation Expired' : 'Reservation Time Remaining'}
                </p>
                <p className={`text-[12px] ${timerSubColor}`}>
                  {timeRemaining === 0
                    ? 'Please search and reserve again'
                    : isTimeCritical
                    ? 'Complete purchase immediately!'
                    : isTimeWarning
                    ? 'Complete your purchase soon'
                    : 'Complete your purchase within 15 minutes'}
                </p>
              </div>
            </div>
            <div className={`text-[28px] font-mono font-bold tabular-nums ${timerSubColor} ${isTimeCritical ? 'animate-pulse' : ''}`}>
              {formatTime(timeRemaining)}
            </div>
          </div>
        </div>

        {/* Capacity Details */}
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.45)] mb-3 flex items-center gap-1.5">
            <Icon icon="mdi:server-outline" width={13} />
            Capacity Details
          </p>
          <div className="bg-[rgba(0,0,0,0.02)] rounded-xl p-5 grid grid-cols-2 md:grid-cols-3 gap-4 border border-[rgba(0,0,0,0.05)]">
            {[
              { label: 'Name',          value: pricing.capacityName },
              { label: 'Instance Type', value: pricing.instanceType, mono: true },
              { label: 'Region',        value: pricing.region },
              { label: 'Platform',      value: pricing.platform },
              { label: 'Tenancy',       value: pricing.tenancy },
              { label: 'Quantity',      value: String(pricing.quantity) },
              { label: 'Start Date',    value: pricing.startDate },
              { label: 'End Date',      value: pricing.endDate },
              { label: 'Auto-Renewal',  value: searchForm.autoRenewal ? 'Yes' : 'No' },
            ].map(({ label, value, mono }) => (
              <div key={label}>
                <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{label}</p>
                <p className={`text-[13px] font-medium text-[rgba(0,0,0,0.88)] ${mono ? 'font-mono' : ''}`}>{value}</p>
              </div>
            ))}
          </div>
        </div>

        {/* Configuration Details */}
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.45)] mb-3 flex items-center gap-1.5">
            <Icon icon="mdi:cog-outline" width={13} />
            Configuration
          </p>
          <div className="bg-[rgba(0,0,0,0.02)] rounded-xl p-5 grid grid-cols-2 md:grid-cols-3 gap-4 border border-[rgba(0,0,0,0.05)]">
            <div>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">VPC</p>
              <p className="text-[13px] font-mono font-medium text-[rgba(0,0,0,0.88)]">
                {configureForm.vpcId === 'default' ? <span className="text-indigo-600">Default VPC</span> : configureForm.vpcId}
              </p>
            </div>
            <div>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Security Group</p>
              <p className="text-[13px] font-mono font-medium text-[rgba(0,0,0,0.88)]">
                {configureForm.securityGroupId === 'none' ? <span className="text-indigo-600">Default SG</span> : configureForm.securityGroupId}
              </p>
            </div>
            <div>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">AMI Image</p>
              <p className="text-[13px] font-mono font-medium text-[rgba(0,0,0,0.88)]">
                {configureForm.amiImageId === 'default' ? <span className="text-indigo-600">Default AMI</span> : configureForm.amiImageId}
              </p>
            </div>
            <div>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">SSH Key</p>
              <p className="text-[13px] font-mono font-medium text-[rgba(0,0,0,0.88)]">
                {configureForm.sshKeyName === 'none' ? <span className="text-[rgba(0,0,0,0.3)]">None</span> : configureForm.sshKeyName}
              </p>
            </div>
            <div>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">EBS Volume</p>
              <p className="text-[13px] font-medium text-[rgba(0,0,0,0.88)]">{configureForm.ebsVolumeSize} GiB</p>
            </div>
          </div>
        </div>

        {/* Pricing */}
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.45)] mb-3 flex items-center gap-1.5">
            <Icon icon="mdi:tag-outline" width={13} />
            Pricing
          </p>
          <div className="bg-emerald-50 rounded-xl p-5 border border-emerald-200 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-[13px] text-[rgba(0,0,0,0.55)]">Our Price (per hour)</span>
              <span className="text-[13px] font-bold text-emerald-700">{formatCurrency(pricing.ourPrice)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-[13px] text-[rgba(0,0,0,0.55)]">AWS On-Demand Price (per hour)</span>
              <span className="text-[13px] text-[rgba(0,0,0,0.35)] line-through">{formatCurrency(pricing.awsPrice)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-[13px] text-[rgba(0,0,0,0.55)]">Savings</span>
              <span className="text-[13px] font-bold text-emerald-600 flex items-center gap-1">
                <Icon icon="mdi:trending-down" width={14} />
                {(pricing.savingsPercent ?? 0).toFixed(1)}%
              </span>
            </div>
            <hr className="border-emerald-200" />
            <div className="flex items-center justify-between">
              <span className="text-[13px] text-[rgba(0,0,0,0.55)]">Fees</span>
              <span className="text-[13px] text-[rgba(0,0,0,0.65)]">{formatCurrency(pricing.fees)}</span>
            </div>
            <div className="flex items-center justify-between pt-2 border-t border-emerald-300">
              <span className="text-[14px] font-bold text-[rgba(0,0,0,0.88)]">Total</span>
              <span className="text-[18px] font-bold text-emerald-700">{formatCurrency(pricing.total)}</span>
            </div>
          </div>
        </div>

        {/* Terms */}
        <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 space-y-3">
          <ul className="space-y-2 text-[12px] text-[rgba(0,0,0,0.55)]">
            {[
              'The instances will be deployed inside your AWS account.',
              'This capacity will not appear in AWS Console billing or Cost Explorer.',
              'All billing will be invoiced separately by Xammer.',
              'In the rare event of a disaster recovery scenario necessitating capacity withdrawal, we may reclaim your Purchased Capacity to support recovery efforts for other customers. Our liability is limited to the refund specified. These terms are governed by the laws of our jurisdiction.',
            ].map((text) => (
              <li key={text} className="flex items-start gap-2">
                <span className="w-1 h-1 rounded-full bg-amber-400 mt-1.5 flex-shrink-0" />
                <span>{text}</span>
              </li>
            ))}
          </ul>
          <label className="flex items-start gap-3 cursor-pointer pt-1">
            <input
              type="checkbox"
              checked={termsAccepted}
              onChange={(e) => setTermsAccepted(e.target.checked)}
              className="w-4 h-4 text-indigo-600 border-[rgba(0,0,0,0.2)] rounded focus:ring-indigo-500 mt-0.5 flex-shrink-0"
            />
            <span className="text-[13px] text-[rgba(0,0,0,0.65)]">
              I agree to the{' '}
              <a href="#" className="text-indigo-600 underline hover:text-indigo-800">Terms & Conditions</a>{' '}
              and authorize XamOps to manage EC2 capacity in my AWS account as described above.
            </span>
          </label>
        </div>

        {/* Purchase Error */}
        {purchaseError && (
          <div className="flex items-start gap-3 p-4 bg-red-50 border border-red-200 rounded-xl text-[13px] text-red-700">
            <Icon icon="mdi:alert-circle-outline" width={16} className="mt-0.5 flex-shrink-0" />
            <span>{purchaseError}</span>
          </div>
        )}

        {/* Actions */}
        <div className="flex items-center justify-between pt-2">
          <button
            onClick={onBack}
            className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors"
          >
            <Icon icon="mdi:arrow-left" width={14} />
            Back
          </button>
          <button
            onClick={onPurchase}
            disabled={!termsAccepted || isPurchasing || timeRemaining === 0}
            className="flex items-center gap-2 px-8 py-3 bg-emerald-600 hover:bg-emerald-700 text-white text-[13px] font-bold rounded-xl transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {timeRemaining === 0 ? (
              <><Icon icon="mdi:clock-alert-outline" width={16} />Reservation Expired</>
            ) : isPurchasing ? (
              <><Icon icon="mdi:loading" width={16} className="animate-spin" />Processing...</>
            ) : (
              <><Icon icon="mdi:cart-outline" width={16} />Purchase Capacity</>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

export default WizardStep3Review;
