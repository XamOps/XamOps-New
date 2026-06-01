import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';

const steps = [
  { label: 'Establishing backend connection',  icon: 'mdi:connection' },
  { label: 'Verifying resource availability',  icon: 'mdi:server-outline' },
  { label: 'Calculating pricing & savings',    icon: 'mdi:calculator-variant-outline' },
];

interface WizardSearchProgressProps {
  status?: string;
  isSearching: boolean;
  onNext: () => void;
  onBack: () => void;
}

const WizardSearchProgress: React.FC<WizardSearchProgressProps> = ({ status, isSearching, onNext, onBack }) => {
  const [currentStep, setCurrentStep] = useState(0);
  const [progress, setProgress]       = useState(0);

  useEffect(() => {
    if (!isSearching && status) {
      setProgress(100);
      setCurrentStep(steps.length - 1);
      return;
    }
    const stepInterval = setInterval(() => {
      setCurrentStep((prev) => (prev < steps.length - 1 ? prev + 1 : prev));
    }, 2000);
    const progressInterval = setInterval(() => {
      setProgress((prev) => (prev < 90 ? prev + Math.random() * 8 : prev));
    }, 500);
    return () => { clearInterval(stepInterval); clearInterval(progressInterval); };
  }, [isSearching, status]);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-sm p-12">
      <div className="max-w-md mx-auto text-center">
        {/* Icon */}
        <div className="w-20 h-20 bg-indigo-600 rounded-full flex items-center justify-center mx-auto mb-8 shadow-lg animate-pulse">
          <Icon icon="mdi:magnify" color="#fff" width={32} />
        </div>

        <h3 className="text-[20px] font-bold text-[rgba(0,0,0,0.88)] mb-2">Searching Capacity</h3>
        <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-8">
          Finding the best available capacity for your requirements…
        </p>

        {/* Progress Bar */}
        <div className="w-full bg-[rgba(0,0,0,0.06)] rounded-full h-2 mb-8">
          <div
            className="bg-indigo-600 h-2 rounded-full transition-all duration-500 ease-out"
            style={{ width: `${Math.min(progress, 95)}%` }}
          />
        </div>

        {/* Steps */}
        <div className="space-y-3 text-left">
          {steps.map((step, index) => {
            const isCompleted = index < currentStep;
            const isCurrent   = index === currentStep;
            return (
              <div
                key={index}
                className={`flex items-center gap-3 p-3 rounded-xl transition-all ${
                  isCurrent ? 'bg-indigo-50 border border-indigo-200' : isCompleted ? 'opacity-60' : 'opacity-30'
                }`}
              >
                <div className={`w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 ${
                  isCompleted ? 'bg-emerald-100' : isCurrent ? 'bg-indigo-100' : 'bg-[rgba(0,0,0,0.04)]'
                }`}>
                  {isCompleted
                    ? <Icon icon="mdi:check" width={14} color="#16A34A" />
                    : isCurrent
                      ? <Icon icon="mdi:loading" width={14} color="#6366F1" className="animate-spin" />
                      : <Icon icon={step.icon} width={14} color="rgba(0,0,0,0.3)" />}
                </div>
                <span className={`text-[13px] ${isCurrent ? 'font-medium text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.55)]'}`}>
                  {step.label}
                </span>
              </div>
            );
          })}
        </div>

        {/* Status result */}
        {status && (
          <div className={`mt-6 p-4 rounded-xl border-2 border-dashed ${
            status === 'AVAILABLE_ALL'       ? 'border-emerald-300 bg-emerald-50' :
            status === 'PARTIAL_AVAILABLE'   ? 'border-amber-300 bg-amber-50' :
                                              'border-rose-300 bg-rose-50'
          }`}>
            <div className="flex items-center gap-3">
              <Icon
                icon={
                  status === 'AVAILABLE_ALL'     ? 'mdi:check-circle' :
                  status === 'PARTIAL_AVAILABLE' ? 'mdi:alert-circle' :
                                                   'mdi:close-circle'
                }
                width={22}
                color={
                  status === 'AVAILABLE_ALL'     ? '#16A34A' :
                  status === 'PARTIAL_AVAILABLE' ? '#D97706' : '#DC2626'
                }
              />
              <div className="text-left">
                <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                  {status === 'AVAILABLE_ALL'     ? 'Capacity Available' :
                   status === 'PARTIAL_AVAILABLE' ? 'Partially Available' : 'Not Available'}
                </p>
                <p className="text-[12px] text-[rgba(0,0,0,0.55)]">
                  {status === 'AVAILABLE_ALL'     ? 'Full capacity is available for your requirements' :
                   status === 'PARTIAL_AVAILABLE' ? 'Some capacity is available, but not all requested' :
                   'No capacity available for the specified criteria'}
                </p>
              </div>
            </div>
          </div>
        )}

        {/* Actions */}
        {!isSearching && status && (
          <div className="mt-8 flex gap-3">
            <button
              onClick={onBack}
              className="flex-1 flex items-center justify-center gap-2 py-2.5 px-5 bg-white border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.65)] text-[13px] font-semibold rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors"
            >
              <Icon icon="mdi:arrow-left" width={14} />
              Back to Search
            </button>
            <button
              onClick={onNext}
              disabled={isSearching || (status !== 'AVAILABLE_ALL' && status !== 'PARTIAL_AVAILABLE')}
              className="flex-1 flex items-center justify-center gap-2 py-2.5 px-5 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isSearching
                ? <><Icon icon="mdi:loading" width={14} className="animate-spin" /> Processing…</>
                : <>Continue to Configure <Icon icon="mdi:arrow-right" width={14} /></>
              }
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default WizardSearchProgress;
