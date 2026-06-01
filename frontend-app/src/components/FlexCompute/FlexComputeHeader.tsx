import React from 'react';
import { Icon } from '@iconify/react';

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
    <div className="bg-white border-b border-[rgba(0,0,0,0.08)] px-8 py-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-[20px] font-bold text-[rgba(0,0,0,0.88)]">Flex Compute</h1>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mt-0.5">
            Manage scheduled EC2 capacity reservations via CloudiDR
          </p>
        </div>
        <div className="flex items-center gap-2">
          {email && (
            <span className="flex items-center gap-1.5 text-[12px] text-[rgba(0,0,0,0.55)] bg-[rgba(0,0,0,0.04)] px-3 py-1.5 rounded-full">
              <Icon icon="mdi:link-variant" width={12} color="#16A34A" />
              {email}
            </span>
          )}
          <button
            onClick={onDeployStack}
            className="flex items-center gap-2 px-4 py-2 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors"
          >
            <Icon icon="mdi:aws" width={14} color="#FF9900" />
            Deploy Stack
          </button>
          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="flex items-center gap-2 px-4 py-2 text-[13px] font-semibold text-white bg-indigo-600 hover:bg-indigo-700 rounded-xl transition-colors disabled:opacity-50"
          >
            <Icon icon="mdi:refresh" width={14} className={isLoading ? 'animate-spin' : ''} />
            Refresh
          </button>
          <button
            onClick={onDisconnect}
            className="w-8 h-8 flex items-center justify-center rounded-xl text-[rgba(0,0,0,0.4)] hover:text-red-600 hover:bg-red-50 transition-colors"
            title="Disconnect CloudiDR"
          >
            <Icon icon="mdi:logout-variant" width={16} />
          </button>
        </div>
      </div>
    </div>
  );
};

export default FlexComputeHeader;
