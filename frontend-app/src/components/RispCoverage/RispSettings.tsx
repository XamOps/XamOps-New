import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { gooeyToast } from 'goey-toast';
import apiClient from '../../utils/apiClient';
import {
  getCommitmentSettings,
  legacyRispRespectPath,
  legacySettingsPath,
  saveCommitmentSettings,
} from '../../api/autospottingApi';
import { AriaSwitch, AriaSlider } from '../ui';

interface RispSettingsProps {
  accountId: string | null;
  /** The XamOps account id: the shell's Commitments view reads and saves through the v2 API. */
  v2AccountId?: number | null;
}

interface Settings {
  respectRiSpCommitments: boolean;
  riSpUtilizationThreshold: number;
}

const RispSettings: React.FC<RispSettingsProps> = ({ accountId, v2AccountId }) => {
  const [settings, setSettings] = useState<Settings>({
    respectRiSpCommitments: false,
    riSpUtilizationThreshold: 90,
  });
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // Fetch current settings
  useEffect(() => {
    if (!accountId && v2AccountId == null) return;

    const fetchSettings = async () => {
      setLoading(true);
      try {
        // The v2 body is the settings themselves; the legacy response wraps them in `data`.
        const stored = v2AccountId != null
          ? await getCommitmentSettings(v2AccountId)
          : (await apiClient.get(legacySettingsPath(accountId as string))).data;
        setSettings({
          respectRiSpCommitments: stored.respectRiSpCommitments || false,
          riSpUtilizationThreshold: stored.riSpUtilizationThreshold ?? 90,
        });
      } catch (error) {
        console.error('Failed to fetch settings:', error);
      } finally {
        setLoading(false);
      }
    };

    fetchSettings();
  }, [accountId, v2AccountId]);

  // Save settings
  const handleSave = async () => {
    if (!accountId && v2AccountId == null) return;

    setSaving(true);

    try {
      const payload = {
        respectRiSpCommitments: settings.respectRiSpCommitments,
        riSpUtilizationThreshold: settings.riSpUtilizationThreshold,
      };
      if (v2AccountId != null) await saveCommitmentSettings(v2AccountId, payload);
      else await apiClient.put(legacyRispRespectPath(accountId as string), payload);
      gooeyToast.success('Settings saved successfully!');
    } catch (error: any) {
      gooeyToast.error('Failed to save settings', {
        description: error.detail || error.response?.data?.error || 'Unknown error',
      });
    } finally {
      setSaving(false);
    }
  };

  if (!accountId && v2AccountId == null) return null;

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-[rgba(0,0,0,0.12)] p-8 mt-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h3 className="text-xl font-bold text-[rgba(0,0,0,0.88)] flex items-center gap-3">
            <Icon icon="mdi:cog-outline" className="text-gray-700" width={22} />
            RI/SP Respect Settings
          </h3>
          <p className="text-sm text-[rgba(0,0,0,0.5)] mt-1">
            Configure how AutoSpotting handles Reserved Instance and Savings Plan commitments
          </p>
        </div>
      </div>

      {loading ? (
        <div className="text-center py-8">
          <Icon icon="mdi:loading" className="animate-spin text-3xl text-gray-700" width={32} />
          <p className="text-[rgba(0,0,0,0.6)] mt-2">Loading settings...</p>
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
          <div className="p-4 bg-[rgba(0,0,0,0.02)] rounded-lg border border-[rgba(0,0,0,0.08)]">
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
            <div className="mt-3 text-xs text-[rgba(0,0,0,0.6)] bg-gray-100 border border-gray-300 rounded p-3">
              <Icon icon="mdi:information-outline" className="text-gray-700 inline mr-2" width={14} />
              <strong>Example:</strong> If set to 90%, AutoSpotting will only enable Spot when your RI/SP average utilization is ≥ 90%. This prevents wasting unused commitments.
            </div>
          </div>

          {/* Save Button */}
          <div className="flex items-center justify-between pt-4 border-t border-[rgba(0,0,0,0.12)]">
            <div className="text-xs text-[rgba(0,0,0,0.5)]">
              {settings.respectRiSpCommitments ? (
                <>
                  <Icon icon="mdi:shield-outline" className="text-gray-700 inline mr-1" width={14} />
                  <strong>Protected:</strong> Instances will stay On-Demand when RI/SP are underutilized
                </>
              ) : (
                <>
                  <Icon icon="mdi:rocket-launch-outline" className="text-gray-700 inline mr-1" width={14} />
                  <strong>Standard:</strong> AutoSpotting will convert to Spot immediately (ignores RI/SP)
                </>
              )}
            </div>

            <button
              onClick={handleSave}
              disabled={saving}
              className="px-6 py-2.5 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-md disabled:opacity-50 disabled:cursor-not-allowed transition-all font-semibold text-sm flex items-center shadow-lg hover:shadow-xl"
            >
              {saving ? (
                <>
                  <Icon icon="mdi:loading" className="animate-spin mr-2" width={16} />
                  Saving...
                </>
              ) : (
                <>
                  <Icon icon="mdi:content-save-outline" className="mr-2" width={16} />
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
