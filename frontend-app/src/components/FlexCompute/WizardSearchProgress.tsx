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

const WizardSearchProgress: React.FC = () => {
  const [currentStep, setCurrentStep] = useState(0);
  const [progress, setProgress] = useState(0);

  useEffect(() => {
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
  }, []);

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
      </div>
    </div>
  );
};

export default WizardSearchProgress;
