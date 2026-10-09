import React from 'react';
import { Icon } from '@iconify/react';
import { PAUSED_MESSAGE } from '../../utils/autospottingErrors';

interface ConversionsPausedNoticeProps {
  className?: string;
}

/** Neutral notice shown while new Spot conversions are paused server-side. */
const ConversionsPausedNotice: React.FC<ConversionsPausedNoticeProps> = ({ className = '' }) => (
  <div
    role="status"
    className={`flex items-start gap-3 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.7)] rounded-xl px-4 py-3 text-xs ${className}`}
  >
    <Icon icon="mdi:pause-circle-outline" width={16} className="mt-0.5 flex-shrink-0 text-[rgba(0,0,0,0.55)]" aria-hidden="true" />
    <span>{PAUSED_MESSAGE}</span>
  </div>
);

export default ConversionsPausedNotice;
