import React from 'react';
import { Icon } from '@iconify/react';

/** "SLA breached · date, time" separator in the ticket thread. BillOps admin view only. */
const SlaBreachDivider: React.FC<{ breachAt: number }> = ({ breachAt }) => (
  <div className="flex items-center gap-3 py-1" role="separator" aria-label="SLA breached">
    <div className="h-px bg-red-300 flex-1"></div>
    <span className="flex items-center gap-1.5 text-[10px] font-semibold text-red-700 px-2.5 py-0.5 bg-red-50 border border-red-200 rounded whitespace-nowrap">
      <Icon icon="mdi:alert-circle-outline" width={12} />
      SLA breached · {new Date(breachAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })}
    </span>
    <div className="h-px bg-red-300 flex-1"></div>
  </div>
);

export default SlaBreachDivider;
