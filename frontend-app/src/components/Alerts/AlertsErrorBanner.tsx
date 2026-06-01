/**
 * AlertsErrorBanner Component
 *
 * Displays error information when alert services fail
 * Supports partial success (some services failed) and complete failure (all services failed)
 */

import React from 'react';
import { Icon } from '@iconify/react';
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
      case 'TIMEOUT':           return 'mdi:clock-outline';
      case 'PERMISSION_DENIED': return 'mdi:lock-outline';
      case 'RATE_LIMIT':        return 'mdi:speedometer';
      case 'SERVICE_NOT_ENABLED': return 'mdi:power';
      default:                  return 'mdi:alert-circle-outline';
    }
  };

  const getErrorTypeLabel = (errorType: string): string => {
    switch (errorType) {
      case 'TIMEOUT':             return 'Timeout';
      case 'PERMISSION_DENIED':   return 'Permission Denied';
      case 'RATE_LIMIT':          return 'Rate Limited';
      case 'SERVICE_NOT_ENABLED': return 'Service Not Enabled';
      case 'API_ERROR':           return 'API Error';
      default:                    return 'Unknown Error';
    }
  };

  const borderColor = isPartialSuccess ? 'border-l-yellow-500' : 'border-l-red-500';
  const bgColor     = isPartialSuccess ? 'bg-yellow-50' : 'bg-red-50';
  const iconColor   = isPartialSuccess ? 'text-yellow-600' : 'text-red-600';
  const titleColor  = isPartialSuccess ? 'text-yellow-900' : 'text-red-900';

  return (
    <div
      className={`mb-6 rounded-lg border-l-4 ${borderColor} ${bgColor} p-5 shadow-sm`}
      role="alert"
    >
      <div className="mb-4 flex items-center gap-3">
        <Icon icon="mdi:alert" width={20} className={iconColor} />
        <h4 className={`text-[15px] font-semibold ${titleColor}`}>
          {isPartialSuccess
            ? 'Some alert services encountered errors'
            : 'Unable to load alerts'}
        </h4>
      </div>

      <div className="mb-4 space-y-3">
        {errors.map((error, index) => (
          <div
            key={index}
            className="flex items-start gap-3 rounded-lg border border-[rgba(0,0,0,0.08)] bg-white p-4"
          >
            <Icon
              icon={getErrorIcon(error.errorType)}
              width={16}
              className="mt-1 flex-shrink-0 text-[rgba(0,0,0,0.4)]"
            />
            <div className="flex-1">
              <div className="mb-1 flex items-center gap-2">
                <span className="font-semibold text-[rgba(0,0,0,0.88)] text-[13px]">{error.service}</span>
                <span className="rounded-full bg-[rgba(0,0,0,0.04)] px-2 py-0.5 text-xs font-medium text-[rgba(0,0,0,0.6)]">
                  {getErrorTypeLabel(error.errorType)}
                </span>
              </div>
              <p className="text-sm text-[rgba(0,0,0,0.6)]">{error.message}</p>
            </div>
            {error.partial && (
              <span className="flex-shrink-0 rounded-full bg-blue-100 px-3 py-1 text-xs font-semibold text-blue-700">
                Partial Data
              </span>
            )}
          </div>
        ))}
      </div>

      <div className="flex justify-end">
        <button
          onClick={onRetry}
          className="flex items-center gap-2 rounded-md bg-[rgba(0,0,0,0.88)] px-3 py-1.5 text-xs font-medium text-white hover:bg-black transition-colors"
        >
          <Icon icon="mdi:refresh" width={14} />
          <span>Retry Failed Services</span>
        </button>
      </div>
    </div>
  );
};

export default AlertsErrorBanner;
