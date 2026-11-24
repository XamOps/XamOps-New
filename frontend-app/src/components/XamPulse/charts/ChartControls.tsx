import React from 'react';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';
import { setPrefs, usePrefs } from '../../../hooks/xampulse/prefs';
import { COMPARE_MODES, type CompareMode } from '../../../utils/xampulse/compare';

/**
 * Chart options shared by Host 360 and Metrics: the learned normal range (a saved preference) and "compare with"
 * (in the URL, so a shared link shows the same comparison).
 */
export const ChartControls: React.FC<{ id: string }> = ({ id }) => {
  const ctx = useViewContext();
  const prefs = usePrefs();
  return (
    <div className="xp-row" role="group" aria-label="Chart options">
      <button type="button" className="xp-btn xp-btn--sm" aria-pressed={prefs.bands} onClick={() => setPrefs({ bands: !prefs.bands })}
        title="Shade the range this value usually stays in at this hour of the week, learned from the host's own history">
        Normal range
      </button>
      <label className="xp-sr" htmlFor={id}>Compare with</label>
      <select id={id} className="xp-select" value={ctx.compare} onChange={(e) => ctx.update({ compare: e.target.value as CompareMode })}>
        {COMPARE_MODES.map(([mode, label]) => <option key={mode} value={mode}>{mode === 'off' ? 'Compare: off' : `Compare: ${label.toLowerCase()}`}</option>)}
      </select>
    </div>
  );
};
