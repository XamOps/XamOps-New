/**
 * Wizard Search Progress Component
 *
 * Shows a loading spinner with progress steps while searching for capacity
 */

import React, { useState, useEffect } from 'react';

const steps = [
  { label: 'Establishing backend connection', icon: 'fas fa-plug' },
  { label: 'Verifying resource availability', icon: 'fas fa-server' },
  { label: 'Calculating pricing & savings', icon: 'fas fa-calculator' },
];

interface WizardSearchProgressProps {
  status?: string;
  isSearching: boolean;
  onNext: () => void;
  onBack: () => void;
}

const WizardSearchProgress: React.FC<WizardSearchProgressProps> = ({ status, isSearching, onNext, onBack }) => {
  const [currentStep, setCurrentStep] = useState(0);
  const [progress, setProgress] = useState(0);

  useEffect(() => {
    // Complete animations when search finishes
    if (!isSearching && status) {
      setProgress(100);
      setCurrentStep(steps.length - 1);
      return;
    }

    const stepInterval = setInterval(() => {
      setCurrentStep((prev) => {
        if (prev < steps.length - 1) return prev + 1;
        return prev;
      });
    }, 2000);

    const progressInterval = setInterval(() => {
      setProgress((prev) => {
        if (prev < 90) return prev + Math.random() * 8;
        return prev;
      });
    }, 500);

    return () => {
      clearInterval(stepInterval);
      clearInterval(progressInterval);
    };
  }, [isSearching, status]);

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-12">
      <div className="max-w-md mx-auto text-center">
        {/* Spinning Icon */}
        <div className="w-20 h-20 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-full flex items-center justify-center mx-auto mb-8 shadow-lg animate-pulse">
          <i className="fas fa-search text-white text-2xl"></i>
        </div>

        <h3 className="text-xl font-bold text-gray-900 mb-2">Searching Capacity</h3>
        <p className="text-sm text-gray-500 mb-8">
          Finding the best available capacity for your requirements...
        </p>

        {/* Progress Bar */}
        <div className="w-full bg-gray-200 rounded-full h-2.5 mb-8">
          <div
            className="bg-gradient-to-r from-blue-500 to-indigo-600 h-2.5 rounded-full transition-all duration-500 ease-out"
            style={{ width: `${Math.min(progress, 95)}%` }}
          ></div>
        </div>

        {/* Steps */}
        <div className="space-y-4 text-left">
          {steps.map((step, index) => {
            const isCompleted = index < currentStep;
            const isCurrent = index === currentStep;

            return (
              <div
                key={index}
                className={`flex items-center gap-3 p-3 rounded-xl transition-all ${
                  isCurrent ? 'bg-blue-50 border border-blue-200' : isCompleted ? 'opacity-60' : 'opacity-30'
                }`}
              >
                <div className={`w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 ${
                  isCompleted
                    ? 'bg-green-100 text-green-600'
                    : isCurrent
                    ? 'bg-blue-100 text-blue-600'
                    : 'bg-gray-100 text-gray-400'
                }`}>
                  {isCompleted ? (
                    <i className="fas fa-check text-xs"></i>
                  ) : isCurrent ? (
                    <i className="fas fa-spinner fa-spin text-xs"></i>
                  ) : (
                    <i className={`${step.icon} text-xs`}></i>
                  )}
                </div>
                <span className={`text-sm ${isCurrent ? 'font-medium text-gray-900' : 'text-gray-600'}`}>
                  {step.label}
                </span>
              </div>
            );
          })}
        </div>

        {/* Availability Status */}
        {status && (
          <div className="mt-6 p-4 rounded-xl border-2 border-dashed border-blue-300 bg-blue-50">
            <div className="flex items-center gap-3">
              <i className={`fas ${
                status === 'AVAILABLE_ALL' ? 'fa-check-circle text-green-600' :
                status === 'PARTIAL_AVAILABLE' ? 'fa-exclamation-circle text-yellow-600' :
                'fa-times-circle text-red-600'
              } text-xl`}></i>
              <div>
                <p className="text-sm font-semibold text-gray-900">
                  {status === 'AVAILABLE_ALL' ? 'Capacity Available' :
                   status === 'PARTIAL_AVAILABLE' ? 'Partially Available' :
                   'Not Available'}
                </p>
                <p className="text-xs text-gray-600">
                  {status === 'AVAILABLE_ALL' ? 'Full capacity is available for your requirements' :
                   status === 'PARTIAL_AVAILABLE' ? 'Some capacity is available, but not all requested' :
                   'No capacity available for the specified criteria'}
                </p>
              </div>
            </div>
          </div>
        )}

        {/* Action Buttons - Only show when search is complete */}
        {!isSearching && status && (
          <div className="mt-8 flex gap-4">
            <button
              onClick={onBack}
              disabled={isSearching}
              className="flex-1 py-3 px-6 bg-white border-2 border-gray-300 text-gray-700 font-semibold rounded-xl hover:bg-gray-50 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <i className="fas fa-arrow-left mr-2"></i>
              Back to Search
            </button>
            <button
              onClick={onNext}
              disabled={isSearching || (status !== 'AVAILABLE_ALL' && status !== 'PARTIAL_AVAILABLE')}
              className="flex-1 py-3 px-6 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-semibold rounded-xl hover:from-blue-700 hover:to-indigo-700 transition-all shadow-lg disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isSearching ? (
                <>
                  <i className="fas fa-spinner fa-spin mr-2"></i>
                  Processing...
                </>
              ) : (
                <>
                  Continue to Configure
                  <i className="fas fa-arrow-right ml-2"></i>
                </>
              )}
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default WizardSearchProgress;
