import React, { useState, useEffect } from 'react';
import apiClient from '../../utils/apiClient';

interface RispSettingsProps {
  accountId: string | null;
}

interface Settings {
  respectRiSpCommitments: boolean;
  riSpUtilizationThreshold: number;
}

const RispSettings: React.FC<RispSettingsProps> = ({ accountId }) => {
  const [settings, setSettings] = useState<Settings>({
    respectRiSpCommitments: false,
    riSpUtilizationThreshold: 90,
  });
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null);

  // Fetch current settings
  useEffect(() => {
    if (!accountId) return;

    const fetchSettings = async () => {
      setLoading(true);
      try {
        const response = await apiClient.get(`/api/autospotting/settings/${accountId}`);
        setSettings({
          respectRiSpCommitments: response.data.respectRiSpCommitments || false,
          riSpUtilizationThreshold: response.data.riSpUtilizationThreshold || 90,
        });
      } catch (error) {
        console.error('Failed to fetch settings:', error);
      } finally {
        setLoading(false);
      }
    };

    fetchSettings();
  }, [accountId]);

  // Save settings
  const handleSave = async () => {
    if (!accountId) return;

    setSaving(true);
    setMessage(null);

    try {
      await apiClient.put(`/api/autospotting/settings/${accountId}/risp-respect`, {
        respectRiSpCommitments: settings.respectRiSpCommitments,
        riSpUtilizationThreshold: settings.riSpUtilizationThreshold,
      });

      setMessage({
        text: 'Settings saved successfully!',
        type: 'success',
      });

      setTimeout(() => setMessage(null), 3000);
    } catch (error: any) {
      setMessage({
        text: error.response?.data?.error || 'Failed to save settings',
        type: 'error',
      });
    } finally {
      setSaving(false);
    }
  };

  if (!accountId) return null;

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-8 mt-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h3 className="text-xl font-bold text-gray-900 flex items-center gap-3">
            <i className="fas fa-cog text-blue-600"></i>
            RI/SP Respect Settings
          </h3>
          <p className="text-sm text-gray-500 mt-1">
            Configure how AutoSpotting handles Reserved Instance and Savings Plan commitments
          </p>
        </div>
      </div>

      {loading ? (
        <div className="text-center py-8">
          <i className="fas fa-spinner fa-spin text-3xl text-blue-600"></i>
          <p className="text-gray-600 mt-2">Loading settings...</p>
        </div>
      ) : (
        <div className="space-y-6">
          {/* Toggle Switch */}
          <div className="flex items-center justify-between p-4 bg-gray-50 rounded-lg border border-gray-200">
            <div className="flex-1">
              <label htmlFor="respectRiSp" className="text-sm font-semibold text-gray-900 cursor-pointer">
                Respect RI/SP Commitments
              </label>
              <p className="text-xs text-gray-600 mt-1">
                When enabled, AutoSpotting will only convert to Spot when RI/SP utilization is high
              </p>
            </div>

            <label className="relative inline-flex items-center cursor-pointer ml-4">
              <input
                id="respectRiSp"
                type="checkbox"
                checked={settings.respectRiSpCommitments}
                onChange={(e) => setSettings({ ...settings, respectRiSpCommitments: e.target.checked })}
                className="sr-only peer"
              />
              <div className="w-14 h-7 bg-gray-300 peer-focus:outline-none peer-focus:ring-4 peer-focus:ring-blue-300 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-0.5 after:left-[4px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-6 after:w-6 after:transition-all peer-checked:bg-blue-600"></div>
              <span className="ml-3 text-sm font-medium text-gray-900">
                {settings.respectRiSpCommitments ? 'Enabled' : 'Disabled'}
              </span>
            </label>
          </div>

          {/* Threshold Slider */}
          <div className="p-4 bg-gray-50 rounded-lg border border-gray-200">
            <label htmlFor="threshold" className="text-sm font-semibold text-gray-900 block mb-3">
              Utilization Threshold: {settings.riSpUtilizationThreshold}%
            </label>
            <p className="text-xs text-gray-600 mb-4">
              Only convert to Spot when (RI + SP) / 2 utilization ≥ this threshold
            </p>

            <div className="flex items-center gap-4">
              <span className="text-xs text-gray-500 font-medium">0%</span>
              <input
                id="threshold"
                type="range"
                min="0"
                max="100"
                step="5"
                value={settings.riSpUtilizationThreshold}
                onChange={(e) => setSettings({ ...settings, riSpUtilizationThreshold: parseInt(e.target.value) })}
                disabled={!settings.respectRiSpCommitments}
                className="flex-1 h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                style={{
                  background: settings.respectRiSpCommitments
                    ? `linear-gradient(to right, rgb(37 99 235) 0%, rgb(37 99 235) ${settings.riSpUtilizationThreshold}%, rgb(229 231 235) ${settings.riSpUtilizationThreshold}%, rgb(229 231 235) 100%)`
                    : '#E5E7EB',
                }}
              />
              <span className="text-xs text-gray-500 font-medium">100%</span>
            </div>

            <div className="mt-3 text-xs text-gray-600 bg-blue-50 border border-blue-200 rounded p-3">
              <i className="fas fa-info-circle text-blue-600 mr-2"></i>
              <strong>Example:</strong> If set to 90%, AutoSpotting will only enable Spot when your RI/SP average utilization is ≥ 90%. This prevents wasting unused commitments.
            </div>
          </div>

          {/* Save Button */}
          <div className="flex items-center justify-between pt-4 border-t border-gray-200">
            <div className="text-xs text-gray-500">
              {settings.respectRiSpCommitments ? (
                <>
                  <i className="fas fa-shield-alt text-green-600 mr-1"></i>
                  <strong>Protected:</strong> Instances will stay On-Demand when RI/SP are underutilized
                </>
              ) : (
                <>
                  <i className="fas fa-rocket text-orange-600 mr-1"></i>
                  <strong>Standard:</strong> AutoSpotting will convert to Spot immediately (ignores RI/SP)
                </>
              )}
            </div>

            <button
              onClick={handleSave}
              disabled={saving}
              className="px-6 py-2.5 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:bg-blue-400 disabled:cursor-not-allowed transition-all font-semibold text-sm flex items-center shadow-lg hover:shadow-xl"
            >
              {saving ? (
                <>
                  <i className="fas fa-spinner fa-spin mr-2"></i>
                  Saving...
                </>
              ) : (
                <>
                  <i className="fas fa-save mr-2"></i>
                  Save Settings
                </>
              )}
            </button>
          </div>

          {/* Success/Error Message */}
          {message && (
            <div
              className={`p-4 rounded-lg border ${
                message.type === 'success'
                  ? 'bg-green-50 border-green-200 text-green-800'
                  : 'bg-red-50 border-red-200 text-red-800'
              }`}
            >
              <i className={`fas ${message.type === 'success' ? 'fa-check-circle' : 'fa-exclamation-circle'} mr-2`}></i>
              {message.text}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default RispSettings;
