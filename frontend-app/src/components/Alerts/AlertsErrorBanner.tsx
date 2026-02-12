/**
 * AlertsErrorBanner Component
 *
 * Displays error information when alert services fail
 * Supports partial success (some services failed) and complete failure (all services failed)
 */

import React from 'react';
import type { AlertError } from '../../types/alerts';

interface AlertsErrorBannerProps {
  errors: AlertError[];
  onRetry: () => void;
  isPartialSuccess: boolean;
}

export const AlertsErrorBanner: React.FC<AlertsErrorBannerProps> = ({
  errors,
  onRetry,
  isPartialSuccess,
}) => {
  if (errors.length === 0) return null;

  const getErrorIcon = (errorType: string): string => {
    switch (errorType) {
      case 'TIMEOUT':
        return 'fa-clock';
      case 'PERMISSION_DENIED':
        return 'fa-lock';
      case 'RATE_LIMIT':
        return 'fa-tachometer-alt';
      case 'SERVICE_NOT_ENABLED':
        return 'fa-power-off';
      default:
        return 'fa-exclamation-circle';
    }
  };

  const getErrorTypeLabel = (errorType: string): string => {
    switch (errorType) {
      case 'TIMEOUT':
        return 'Timeout';
      case 'PERMISSION_DENIED':
        return 'Permission Denied';
      case 'RATE_LIMIT':
        return 'Rate Limited';
      case 'SERVICE_NOT_ENABLED':
        return 'Service Not Enabled';
      case 'API_ERROR':
        return 'API Error';
      default:
        return 'Unknown Error';
    }
  };

  const severityClass = isPartialSuccess ? 'warning' : 'error';
  const borderColor = isPartialSuccess ? 'border-l-yellow-500' : 'border-l-red-500';
  const bgColor = isPartialSuccess ? 'bg-yellow-50' : 'bg-red-50';
  const iconColor = isPartialSuccess ? 'text-yellow-600' : 'text-red-600';
  const titleColor = isPartialSuccess ? 'text-yellow-900' : 'text-red-900';

  return (
    <div
      className={`mb-6 rounded-xl border-l-4 ${borderColor} ${bgColor} p-6 shadow-lg`}
      role="alert"
    >
      {/* Header */}
      <div className="mb-4 flex items-center gap-3">
        <i className={`fas fa-exclamation-triangle text-2xl ${iconColor}`}></i>
        <h4 className={`text-lg font-semibold ${titleColor}`}>
          {isPartialSuccess
            ? 'Some alert services encountered errors'
            : 'Unable to load alerts'}
        </h4>
      </div>

      {/* Error List */}
      <div className="mb-4 space-y-3">
        {errors.map((error, index) => (
          <div
            key={index}
            className="flex items-start gap-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
          >
            {/* Error Icon */}
            <i
              className={`fas ${getErrorIcon(error.errorType)} mt-1 flex-shrink-0 text-gray-500`}
            ></i>

            {/* Error Details */}
            <div className="flex-1">
              <div className="mb-1 flex items-center gap-2">
                <span className="font-semibold text-gray-800">{error.service}</span>
                <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
                  {getErrorTypeLabel(error.errorType)}
                </span>
              </div>
              <p className="text-sm text-gray-600">{error.message}</p>
            </div>

            {/* Partial Data Badge */}
            {error.partial && (
              <span className="flex-shrink-0 rounded-full bg-blue-100 px-3 py-1 text-xs font-semibold text-blue-700">
                Partial Data
              </span>
            )}
          </div>
        ))}
      </div>

      {/* Retry Button */}
      <div className="flex justify-end">
        <button
          onClick={onRetry}
          className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-md transition-all hover:bg-indigo-700 active:scale-95"
        >
          <i className="fas fa-redo"></i>
          <span>Retry Failed Services</span>
        </button>
      </div>
    </div>
  );
};

export default AlertsErrorBanner;
