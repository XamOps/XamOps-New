/**
 * CloudSitter Policy Form Component
 *
 * Create/Edit policy form with weekly schedule grid
 */

import React from 'react';
import type { PolicyFormData } from '../../types/cloudsitter';

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
}) => {
  return (
    <div className="max-w-6xl mx-auto bg-white rounded-2xl shadow-sm border border-gray-200 p-8">
      {/* Header */}
      <div className="flex justify-between items-center mb-8 border-b pb-4">
        <h2 className="text-2xl font-bold text-gray-800 flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-indigo-100 flex items-center justify-center text-indigo-600">
            <i className={`fas ${editMode ? 'fa-edit' : 'fa-calendar-plus'}`}></i>
          </div>
          <span>{editMode ? 'Edit Policy' : 'Create New Policy'}</span>
        </h2>
      </div>

      {/* Form Fields */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-8 mb-8">
        {/* Left Column */}
        <div className="space-y-6">
          <div>
            <label className="block text-sm font-bold text-gray-700 mb-1.5">Policy Name</label>
            <input
              type="text"
              value={policyForm.name}
              onChange={(e) => onFormChange({ ...policyForm, name: e.target.value })}
              placeholder="e.g. Weekdays 9-5 EST"
              className="w-full border-gray-300 rounded-lg shadow-sm focus:ring-indigo-500 focus:border-indigo-500 px-4 py-2.5 transition-shadow"
            />
          </div>
          <div>
            <label className="block text-sm font-bold text-gray-700 mb-1.5">Policy Type</label>
            <div className="flex gap-3">
              <button className="flex-1 px-4 py-2.5 rounded-lg text-sm font-medium border border-indigo-600 bg-indigo-50 text-indigo-700 shadow-sm relative overflow-hidden">
                <i className="fas fa-calendar-alt mr-2"></i> Fixed Schedule
              </button>
              <button
                disabled
                className="flex-1 px-4 py-2.5 rounded-lg text-sm font-medium border border-gray-200 bg-white text-gray-400 cursor-not-allowed"
              >
                <i className="fas fa-bed mr-2"></i> Idle Detection (Soon)
              </button>
            </div>
          </div>
        </div>

        {/* Right Column */}
        <div className="space-y-6">
          <div>
            <label className="block text-sm font-bold text-gray-700 mb-1.5">Timezone</label>
            <div className="relative">
              <i className="fas fa-globe absolute left-3 top-1/2 -translate-y-1/2 text-gray-400"></i>
              <select
                value={policyForm.timezone}
                onChange={(e) => onFormChange({ ...policyForm, timezone: e.target.value })}
                className="w-full border-gray-300 rounded-lg shadow-sm pl-10 pr-4 py-2.5 focus:ring-indigo-500 focus:border-indigo-500"
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
            <label className="block text-sm font-bold text-gray-700 mb-1.5">Notifications</label>
            <div className="flex items-center gap-3 p-3 border rounded-lg bg-gray-50">
              <input
                type="checkbox"
                checked={policyForm.notificationsEnabled}
                onChange={(e) =>
                  onFormChange({ ...policyForm, notificationsEnabled: e.target.checked })
                }
                className="w-5 h-5 text-indigo-600 rounded border-gray-300 focus:ring-indigo-500"
              />
              <span className="text-sm text-gray-700">Notify via email on state change</span>
            </div>
          </div>
        </div>
      </div>

      {/* Weekly Schedule Grid */}
      <div className="bg-gray-50 p-6 rounded-xl border border-gray-200 shadow-inner mb-8">
        <div className="flex justify-between items-center mb-4">
          <div>
            <h3 className="text-sm font-bold text-gray-800">Weekly Schedule</h3>
            <p className="text-xs text-gray-500">Click or drag to toggle status.</p>
          </div>
          <div className="flex gap-4 text-xs font-medium bg-white px-3 py-1.5 rounded-lg border border-gray-200 shadow-sm">
            <div className="flex items-center gap-2">
              <div className="w-3 h-3 rounded-sm bg-emerald-500"></div> Running
            </div>
            <div className="flex items-center gap-2">
              <div className="w-3 h-3 rounded-sm bg-rose-400 opacity-50"></div> Stopped
            </div>
          </div>
        </div>

        <div
          className="relative select-none bg-white border border-gray-300 rounded-lg overflow-hidden shadow-sm"
          onMouseDown={onStartDrag}
          onMouseUp={onStopDrag}
          onMouseLeave={onStopDrag}
        >
          {/* Header Row */}
          <div className="grid grid-cols-[80px_repeat(24,1fr)] bg-gray-100 border-b border-gray-200 text-gray-500">
            <div className="p-2 text-center text-xs font-bold border-r">Time</div>
            {Array.from({ length: 24 }).map((_, h) => (
              <div
                key={h}
                className="text-[10px] font-mono text-center py-2 border-r last:border-0 border-gray-200"
              >
                {h.toString().padStart(2, '0')}
              </div>
            ))}
          </div>

          {/* Day Rows */}
          {DAYS.map((day, dIndex) => (
            <div
              key={dIndex}
              className="grid grid-cols-[80px_repeat(24,1fr)] h-10 border-b last:border-0 border-gray-200"
            >
              <div className="flex items-center px-3 text-xs font-bold text-gray-600 uppercase bg-gray-50 border-r border-gray-200 tracking-wider">
                {day.substring(0, 3)}
              </div>
              {Array.from({ length: 24 }).map((_, hIndex) => (
                <div
                  key={hIndex}
                  className={`h-full border-r last:border-0 border-gray-100 cursor-pointer transition-all duration-75 relative group ${
                    policyGrid[dIndex]?.[hIndex]
                      ? 'bg-emerald-500 hover:bg-emerald-400'
                      : 'bg-gray-50 hover:bg-gray-100'
                  }`}
                  onMouseEnter={() => onGridDrag(dIndex, hIndex)}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    onGridToggle(dIndex, hIndex);
                  }}
                ></div>
              ))}
            </div>
          ))}
        </div>

        <div className="flex justify-between items-center mt-3">
          <div className="text-xs text-gray-500 italic">
            <i className="fas fa-info-circle mr-1"></i> Times are shown in {policyForm.timezone}
          </div>
          <button
            onClick={onResetGrid}
            className="text-xs text-red-600 hover:text-red-800 font-medium flex items-center gap-1 hover:underline"
          >
            <i className="fas fa-undo"></i> Reset to Always Running
          </button>
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center justify-between pt-6 border-t border-gray-100">
        <div className="flex items-center gap-2 text-gray-500 text-sm"></div>
        <div className="flex gap-4">
          <button
            onClick={onCancel}
            className="px-6 py-2.5 text-gray-600 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 font-medium transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={onSave}
            disabled={isSaving}
            className="px-8 py-2.5 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 font-bold shadow-lg shadow-indigo-200 transition-all transform hover:-translate-y-0.5 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSaving ? (
              <>
                <i className="fas fa-spinner fa-spin mr-2"></i> Saving...
              </>
            ) : (
              <>
                <i className={`fas ${editMode ? 'fa-check' : 'fa-save'}`}></i>{' '}
                {editMode ? 'Update Policy' : 'Save Policy'}
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

export default CloudSitterPolicyForm;
