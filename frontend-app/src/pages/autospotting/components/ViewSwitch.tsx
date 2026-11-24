import React from 'react';

export type ViewId = 'groups' | 'analytics' | 'activity' | 'commitments';

/** The AWS view switch (design-ux-ops.md section 2.2). The selected view lives in `?view=`. */
export const VIEWS: Array<{ id: ViewId; label: string }> = [
  { id: 'groups', label: 'Groups' },
  { id: 'analytics', label: 'Analytics' },
  { id: 'activity', label: 'Activity' },
  { id: 'commitments', label: 'Commitments' },
];

export const ViewSwitch: React.FC<{ view: ViewId; onChange: (v: ViewId) => void }> = ({ view, onChange }) => (
  <div role="tablist" aria-label="AutoSpotting views" className="flex gap-1 border-b border-gray-300">
    {VIEWS.map(v => (
      <button
        key={v.id}
        type="button"
        role="tab"
        aria-selected={view === v.id}
        onClick={() => onChange(v.id)}
        className={`px-3 py-2 text-sm ${
          view === v.id ? 'border-b-2 border-black font-semibold text-black' : 'text-gray-600'
        }`}
      >
        {v.label}
      </button>
    ))}
  </div>
);

export default ViewSwitch;
