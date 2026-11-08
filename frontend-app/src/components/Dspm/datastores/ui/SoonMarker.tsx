import React from 'react';

/** The XamLens "Soon" pill, for features the API cannot serve yet (Risk › Alerts, Data Flow › Graph View). */
export const SoonMarker: React.FC = () => (
  <span className="rounded-full bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.35)]">Soon</span>
);
