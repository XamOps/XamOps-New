import React from 'react';
import { Icon } from '@iconify/react';
import type { PolicyFormData } from '../../types/cloudsitter';
import ScheduleRecommendationPanel from './ScheduleRecommendationPanel';

interface CloudSitterPolicyFormProps {
  policyForm: PolicyFormData;
  policyGrid: boolean[][];
  editMode: boolean;
  isSaving: boolean;
  isDragging: boolean;
  onFormChange: (form: PolicyFormData) => void;
  onGridToggle: (d: number, h: number) => void;
  onGridDrag: (d: number, h: number) => void;
  onStartDrag: () => void;
  onStopDrag: () => void;
  onResetGrid: () => void;
  onSave: () => void;
  onCancel: () => void;
  instanceId?: string;
  instanceName?: string;
  instanceRegion?: string;
  accountId?: string | null;
  onApplyRecommendation?: (grid: boolean[][]) => void;
}

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

const CloudSitterPolicyForm: React.FC<CloudSitterPolicyFormProps> = ({
  policyForm,
  policyGrid,
  editMode,
  isSaving,
  onFormChange,
  onGridToggle,
  onGridDrag,
  onStartDrag,
  onStopDrag,
  onResetGrid,
  onSave,
  onCancel,
  instanceId,
  instanceName,
  instanceRegion,
  accountId,
  onApplyRecommendation,
}) => {
  return (
    <div className="max-w-6xl mx-auto bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-sm p-8">
      {/* Header */}
      <div className="flex items-center gap-3 mb-8 pb-5 border-b border-[rgba(0,0,0,0.06)]">
        <div className="w-10 h-10 rounded-xl bg-indigo-50 flex items-center justify-center flex-shrink-0">
          <Icon icon={editMode ? 'mdi:pencil-outline' : 'mdi:calendar-plus-outline'} width={20} color="#6366F1" />
        </div>
        <h2 className="text-[18px] font-bold text-[rgba(0,0,0,0.88)]">
          {editMode ? 'Edit Policy' : 'Create New Policy'}
        </h2>
      </div>

      {/* Form Fields */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-8 mb-8">
        {/* Left Column */}
        <div className="space-y-5">
          <div>
            <label className="block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] uppercase tracking-wide mb-1.5">Policy Name</label>
            <input
              type="text"
              value={policyForm.name}
              onChange={(e) => onFormChange({ ...policyForm, name: e.target.value })}
              placeholder="e.g. Weekdays 9-5 EST"
              className="w-full border border-[rgba(0,0,0,0.12)] rounded-lg px-4 py-2.5 text-[13px] text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-indigo-300 focus:border-indigo-400 transition-shadow"
            />
          </div>
          <div>
            <label className="block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] uppercase tracking-wide mb-1.5">Policy Type</label>
            <div className="flex gap-3">
              <button className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-[13px] font-semibold border border-indigo-300 bg-indigo-50 text-indigo-700">
                <Icon icon="mdi:calendar-clock" width={14} />
                Fixed Schedule
              </button>
              <button
                disabled
                className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-[13px] font-semibold border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] text-[rgba(0,0,0,0.3)] cursor-not-allowed"
              >
                <Icon icon="mdi:bed-outline" width={14} />
                Idle Detection (Soon)
              </button>
            </div>
          </div>
        </div>

        {/* Right Column */}
        <div className="space-y-5">
          <div>
            <label className="block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] uppercase tracking-wide mb-1.5">Timezone</label>
            <div className="relative">
              <Icon icon="mdi:earth" width={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
              <select
                value={policyForm.timezone}
                onChange={(e) => onFormChange({ ...policyForm, timezone: e.target.value })}
                className="w-full border border-[rgba(0,0,0,0.12)] rounded-lg pl-9 pr-4 py-2.5 text-[13px] text-[rgba(0,0,0,0.88)] focus:outline-none focus:ring-2 focus:ring-indigo-300 bg-white"
              >
                <option value="Asia/Kolkata">Asia/Kolkata (IST)</option>
                <option value="UTC">UTC (Universal)</option>
                <option value="US/Eastern">US/Eastern (EST)</option>
                <option value="US/Pacific">US/Pacific (PST)</option>
                <option value="Europe/London">Europe/London (GMT)</option>
              </select>
            </div>
          </div>
          <div>
            <label className="block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] uppercase tracking-wide mb-1.5">Notifications</label>
            <label className="flex items-center gap-3 p-3 border border-[rgba(0,0,0,0.08)] rounded-lg bg-[rgba(0,0,0,0.015)] cursor-pointer">
              <input
                type="checkbox"
                checked={policyForm.notificationsEnabled}
                onChange={(e) => onFormChange({ ...policyForm, notificationsEnabled: e.target.checked })}
                className="w-4 h-4 text-indigo-600 rounded border-gray-300 focus:ring-indigo-500"
              />
              <span className="text-[13px] text-[rgba(0,0,0,0.65)]">Notify via email on state change</span>
            </label>
          </div>
        </div>
      </div>

      {/* Smart Recommendation Panel */}
      {instanceId && instanceRegion && accountId && onApplyRecommendation && (
        <ScheduleRecommendationPanel
          instanceId={instanceId}
          instanceName={instanceName}
          region={instanceRegion}
          accountId={accountId}
          onApply={onApplyRecommendation}
        />
      )}

      {/* Weekly Schedule Grid */}
      <div className="bg-[rgba(0,0,0,0.015)] p-6 rounded-xl border border-[rgba(0,0,0,0.06)] mb-8">
        <div className="flex justify-between items-center mb-4">
          <div>
            <h3 className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">Weekly Schedule</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Click or drag to toggle status.</p>
          </div>
          <div className="flex gap-4 text-[11px] font-semibold bg-white px-3 py-1.5 rounded-lg border border-[rgba(0,0,0,0.08)] shadow-sm">
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-sm bg-emerald-500" /> Running
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-sm bg-rose-200" /> Stopped
            </div>
          </div>
        </div>

        <div
          className="relative select-none bg-white border border-[rgba(0,0,0,0.1)] rounded-lg overflow-hidden shadow-sm"
          onMouseDown={onStartDrag}
          onMouseUp={onStopDrag}
          onMouseLeave={onStopDrag}
        >
          {/* Header Row */}
          <div className="grid grid-cols-[80px_repeat(24,1fr)] bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.4)]">
            <div className="p-2 text-center text-[10px] font-bold border-r border-[rgba(0,0,0,0.06)]">Time</div>
            {Array.from({ length: 24 }).map((_, h) => (
              <div key={h} className="text-[9px] font-mono text-center py-2 border-r last:border-0 border-[rgba(0,0,0,0.04)]">
                {h.toString().padStart(2, '0')}
              </div>
            ))}
          </div>

          {/* Day Rows */}
          {DAYS.map((day, dIndex) => (
            <div key={dIndex} className="grid grid-cols-[80px_repeat(24,1fr)] h-10 border-b last:border-0 border-[rgba(0,0,0,0.04)]">
              <div className="flex items-center px-3 text-[10px] font-bold text-[rgba(0,0,0,0.5)] uppercase bg-[rgba(0,0,0,0.01)] border-r border-[rgba(0,0,0,0.06)] tracking-wider">
                {day.substring(0, 3)}
              </div>
              {Array.from({ length: 24 }).map((_, hIndex) => (
                <div
                  key={hIndex}
                  className={`h-full border-r last:border-0 border-[rgba(0,0,0,0.03)] cursor-pointer transition-all duration-75 ${
                    policyGrid[dIndex]?.[hIndex]
                      ? 'bg-emerald-500 hover:bg-emerald-400'
                      : 'bg-[rgba(0,0,0,0.01)] hover:bg-[rgba(0,0,0,0.04)]'
                  }`}
                  onMouseEnter={() => onGridDrag(dIndex, hIndex)}
                  onMouseDown={(e) => { e.preventDefault(); onGridToggle(dIndex, hIndex); }}
                />
              ))}
            </div>
          ))}
        </div>

        <div className="flex justify-between items-center mt-3">
          <div className="flex items-center gap-1 text-[11px] text-[rgba(0,0,0,0.4)] italic">
            <Icon icon="mdi:information-outline" width={12} />
            Times are shown in {policyForm.timezone}
          </div>
          <button
            onClick={onResetGrid}
            className="flex items-center gap-1 text-[11px] text-rose-600 hover:text-rose-700 font-semibold"
          >
            <Icon icon="mdi:restore" width={12} />
            Reset to Always Running
          </button>
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center justify-end gap-3 pt-5 border-t border-[rgba(0,0,0,0.06)]">
        <button
          onClick={onCancel}
          className="px-5 py-2.5 text-[13px] text-[rgba(0,0,0,0.55)] bg-white border border-[rgba(0,0,0,0.1)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] font-medium transition-colors"
        >
          Cancel
        </button>
        <button
          onClick={onSave}
          disabled={isSaving}
          className="flex items-center gap-2 px-7 py-2.5 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 text-[13px] font-bold transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {isSaving ? (
            <><Icon icon="mdi:loading" width={14} className="animate-spin" /> Saving…</>
          ) : (
            <><Icon icon={editMode ? 'mdi:check' : 'mdi:content-save-outline'} width={14} /> {editMode ? 'Update Policy' : 'Save Policy'}</>
          )}
        </button>
      </div>
    </div>
  );
};

export default CloudSitterPolicyForm;
