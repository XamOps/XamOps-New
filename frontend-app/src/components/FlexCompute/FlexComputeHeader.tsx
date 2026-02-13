/**
 * FlexCompute Header Component
 *
 * Page header with title, description, and action buttons
 */

import React from 'react';

interface FlexComputeHeaderProps {
  onRefresh: () => void;
  onDeployStack: () => void;
  onDisconnect: () => void;
  email?: string;
  isLoading: boolean;
}

const FlexComputeHeader: React.FC<FlexComputeHeaderProps> = ({
  onRefresh,
  onDeployStack,
  onDisconnect,
  email,
  isLoading,
}) => {
  return (
    <div className="bg-white border-b border-gray-200 px-8 py-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Flex Compute</h1>
          <p className="text-sm text-gray-500 mt-1">
            Manage scheduled EC2 capacity reservations via CloudiDR
          </p>
        </div>
        <div className="flex items-center gap-3">
          {email && (
            <span className="text-xs text-gray-500 bg-gray-100 px-3 py-1.5 rounded-full">
              <i className="fas fa-link mr-1.5 text-green-500"></i>
              {email}
            </span>
          )}
          <button
            onClick={onDeployStack}
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
          >
            <i className="fab fa-aws mr-2"></i>
            Deploy Stack
          </button>
          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-blue-600 to-indigo-600 rounded-lg hover:from-blue-700 hover:to-indigo-700 transition-all disabled:opacity-50"
          >
            <i className={`fas fa-sync-alt mr-2 ${isLoading ? 'animate-spin' : ''}`}></i>
            Refresh
          </button>
          <button
            onClick={onDisconnect}
            className="px-3 py-2 text-sm font-medium text-gray-500 hover:text-red-600 transition-colors"
            title="Disconnect CloudiDR"
          >
            <i className="fas fa-sign-out-alt"></i>
          </button>
        </div>
      </div>
    </div>
  );
};

export default FlexComputeHeader;
