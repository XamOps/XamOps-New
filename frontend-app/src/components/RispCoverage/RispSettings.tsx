import React, { useState, useEffect } from 'react';
import { gooeyToast } from 'goey-toast';
import apiClient from '../../utils/apiClient';
import { AriaSwitch, AriaSlider } from '../ui';

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

    try {
      await apiClient.put(`/api/autospotting/settings/${accountId}/risp-respect`, {
        respectRiSpCommitments: settings.respectRiSpCommitments,
        riSpUtilizationThreshold: settings.riSpUtilizationThreshold,
      });
      gooeyToast.success('Settings saved successfully!');
    } catch (error: any) {
      gooeyToast.error('Failed to save settings', {
        description: error.response?.data?.error || 'Unknown error',
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
          <AriaSwitch
            isSelected={settings.respectRiSpCommitments}
            onChange={(checked) => setSettings({ ...settings, respectRiSpCommitments: checked })}
            label="Respect RI/SP Commitments"
            description="When enabled, AutoSpotting will only convert to Spot when RI/SP utilization is high"
          />

          {/* Threshold Slider */}
          <div className="p-4 bg-gray-50 rounded-lg border border-gray-200">
            <AriaSlider
              label="Utilization Threshold"
              value={settings.riSpUtilizationThreshold}
              onChange={(v) => setSettings({ ...settings, riSpUtilizationThreshold: v })}
              minValue={0}
              maxValue={100}
              step={5}
              isDisabled={!settings.respectRiSpCommitments}
              formatValue={(v) => `${v}%`}
              description="Only convert to Spot when (RI + SP) / 2 utilization ≥ this threshold"
            />
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

        </div>
      )}
    </div>
  );
};

export default RispSettings;
