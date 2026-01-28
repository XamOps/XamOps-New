/**
 * MetricCard Component
 *
 * Reusable card component for displaying metrics on the dashboard
 * Supports loading states, icons, and custom colors
 */

import React, { ReactNode } from 'react';

interface MetricCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  icon: string;
  iconBgColor?: string;
  borderColor?: string;
  isLoading?: boolean;
  children?: ReactNode;
  onClick?: () => void;
  showViewButton?: boolean;
  onViewClick?: () => void;
}

export const MetricCard: React.FC<MetricCardProps> = ({
  title,
  value,
  subtitle,
  icon,
  iconBgColor = 'from-indigo-100 to-purple-100',
  borderColor = 'border-l-indigo-500',
  isLoading = false,
  children,
  onClick,
  showViewButton = false,
  onViewClick,
}) => {
  const cardClasses = `glass-card rounded-xl p-6 relative group metric-card border-l-4 ${borderColor} ${
    onClick ? 'cursor-pointer hover:bg-gradient-to-br hover:from-green-50/50 hover:to-emerald-50/50 transition-all' : ''
  }`;

  return (
    <div className={cardClasses} onClick={onClick}>
      <div className="flex justify-between items-center mb-3">
        <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">{title}</h3>
        <div className="flex gap-2">
          {showViewButton && onViewClick && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onViewClick();
              }}
              className="w-8 h-8 rounded-lg bg-gray-50 hover:bg-indigo-50 text-gray-400 hover:text-indigo-600 transition-all flex items-center justify-center"
              title="View Breakdown"
            >
              <i className="fas fa-eye text-xs"></i>
            </button>
          )}
          <div className={`w-10 h-10 bg-gradient-to-br ${iconBgColor} rounded-lg flex items-center justify-center`}>
            <i className={icon}></i>
          </div>
        </div>
      </div>

      {isLoading ? (
        <div className="animate-pulse space-y-4">
          <div className="h-10 w-36 bg-gray-200 shimmer rounded-lg"></div>
          {children && <div className="h-2 w-full bg-gray-200 shimmer rounded-full"></div>}
        </div>
      ) : (
        <div className="fade-in">
          <div className="flex items-baseline gap-2 mb-1">
            <span className="text-4xl font-black metric-value">{value}</span>
          </div>
          {subtitle && <p className="text-xs text-gray-500 mt-1 font-medium">{subtitle}</p>}
          {children}
        </div>
      )}
    </div>
  );
};
