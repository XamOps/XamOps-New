import { useState } from 'react';
import { Icon } from '@iconify/react';
import { usePresets, type TranscodePreset, type PresetRequest } from '../../hooks/usePresets';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface PresetValues {
  id?: number;
  outputFormat?: string;
  resolution?: string;
  bitrate?: string;
  fps?: number;
  codec?: string;
  audioBitrate?: string;
  customFfmpegParams?: string;
}

interface PresetSelectorProps {
  onApplyPreset: (values: PresetValues) => void;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function PresetSelector({ onApplyPreset }: PresetSelectorProps) {
  const { presets, loading, error, createPreset, updatePreset, deletePreset } = usePresets();

  const [selectedPresetId, setSelectedPresetId] = useState<number | ''>('');
  const [showManage, setShowManage] = useState(false);
  const [editingPreset, setEditingPreset] = useState<TranscodePreset | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<PresetRequest>({ name: '' });
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // ─── Handlers ───────────────────────────────────────────────────────────────

  function handlePresetSelect(id: string) {
    const numId = id === '' ? '' : Number(id);
    setSelectedPresetId(numId);

    if (numId !== '') {
      const preset = presets.find(p => p.id === numId);
      if (preset) {
        const values: PresetValues = { id: preset.id };
        if (preset.outputFormat) values.outputFormat = preset.outputFormat;
        if (preset.resolution) values.resolution = preset.resolution;
        if (preset.bitrate) values.bitrate = preset.bitrate;
        if (preset.fps) values.fps = preset.fps;
        if (preset.codec) values.codec = preset.codec;
        if (preset.audioBitrate) values.audioBitrate = preset.audioBitrate;
        if (preset.customFfmpegParams) values.customFfmpegParams = preset.customFfmpegParams;
        onApplyPreset(values);
      }
    }
  }

  function openCreateForm() {
    setEditingPreset(null);
    setFormData({ name: '' });
    setFormError(null);
    setShowForm(true);
  }

  function openEditForm(preset: TranscodePreset) {
    setEditingPreset(preset);
    setFormData({
      name: preset.name,
      outputFormat: preset.outputFormat ?? undefined,
      resolution: preset.resolution ?? undefined,
      bitrate: preset.bitrate ?? undefined,
      fps: preset.fps ?? undefined,
      codec: preset.codec ?? undefined,
      audioBitrate: preset.audioBitrate ?? undefined,
      customFfmpegParams: preset.customFfmpegParams ?? undefined,
    });
    setFormError(null);
    setShowForm(true);
  }

  async function handleSave() {
    if (!formData.name.trim()) {
      setFormError('Name is required');
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      if (editingPreset) {
        await updatePreset(editingPreset.id, formData);
      } else {
        await createPreset(formData);
      }
      setShowForm(false);
      setEditingPreset(null);
    } catch (e: any) {
      setFormError(e.message || 'Failed to save preset');
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete(id: number) {
    try {
      await deletePreset(id);
      if (selectedPresetId === id) {
        setSelectedPresetId('');
      }
    } catch {
      // Error is surfaced via the hook's error state
    }
  }

  function handleFormField(field: keyof PresetRequest, value: string | number | undefined) {
    setFormData(prev => ({ ...prev, [field]: value || undefined }));
  }

  // ─── Render ─────────────────────────────────────────────────────────────────

  return (
    <div className="space-y-2">
      {/* Preset Dropdown */}
      <div className="flex items-center gap-2">
        <select
          value={selectedPresetId}
          onChange={e => handlePresetSelect(e.target.value)}
          disabled={loading}
          className="flex-1 px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-xl text-[12px] focus:ring-2 focus:ring-pink-400 outline-none bg-white disabled:opacity-50"
        >
          <option value="">
            {loading ? 'Loading presets…' : 'Select a preset'}
          </option>
          {presets.map(p => (
            <option key={p.id} value={p.id}>{p.name}</option>
          ))}
        </select>
        <button
          onClick={() => setShowManage(!showManage)}
          className="p-2 rounded-lg hover:bg-pink-50 transition-colors"
          title="Manage Presets"
        >
          <Icon icon="mdi:dots-vertical" className="text-[rgba(0,0,0,0.5)] text-base" />
        </button>
      </div>

      {/* Error display */}
      {error && (
        <p className="text-[11px] text-red-600 bg-red-50 rounded-lg px-2 py-1">{error}</p>
      )}

      {/* Manage Presets Panel */}
      {showManage && (
        <div className="border border-[rgba(0,0,0,0.08)] rounded-xl p-3 bg-white space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-[11px] font-bold text-[rgba(0,0,0,0.5)] uppercase tracking-wide">Manage Presets</span>
            <button
              onClick={openCreateForm}
              className="flex items-center gap-1 px-2 py-1 text-[11px] font-semibold text-pink-600 hover:bg-pink-50 rounded-lg transition-colors"
            >
              <Icon icon="mdi:plus" className="text-sm" />
              New
            </button>
          </div>

          {presets.length === 0 && !loading && (
            <p className="text-[11px] text-[rgba(0,0,0,0.35)] text-center py-2">No presets yet</p>
          )}

          {/* Preset List */}
          <div className="space-y-1 max-h-40 overflow-y-auto">
            {presets.map(preset => (
              <div key={preset.id} className="flex items-center justify-between px-2 py-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.03)] group">
                <span className="text-[12px] text-[rgba(0,0,0,0.7)] truncate flex-1">{preset.name}</span>
                <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    onClick={() => openEditForm(preset)}
                    className="p-1 rounded hover:bg-pink-50 transition-colors"
                    title="Edit preset"
                  >
                    <Icon icon="mdi:pencil-outline" className="text-[rgba(0,0,0,0.4)] text-sm" />
                  </button>
                  <button
                    onClick={() => handleDelete(preset.id)}
                    className="p-1 rounded hover:bg-red-50 transition-colors"
                    title="Delete preset"
                  >
                    <Icon icon="mdi:delete-outline" className="text-[rgba(0,0,0,0.4)] text-sm hover:text-red-500" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Create/Edit Form Modal */}
      {showForm && (
        <div className="border border-pink-200 rounded-xl p-3 bg-pink-50/30 space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-[11px] font-bold text-[rgba(0,0,0,0.6)] uppercase tracking-wide">
              {editingPreset ? 'Edit Preset' : 'New Preset'}
            </span>
            <button
              onClick={() => setShowForm(false)}
              className="p-1 rounded hover:bg-pink-100 transition-colors"
            >
              <Icon icon="mdi:close" className="text-sm text-[rgba(0,0,0,0.4)]" />
            </button>
          </div>

          {/* Name (required) */}
          <div>
            <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] mb-1 uppercase">Name *</label>
            <input
              value={formData.name}
              onChange={e => handleFormField('name', e.target.value)}
              placeholder="e.g. Web 1080p H.265"
              className="w-full px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] focus:ring-2 focus:ring-pink-400 outline-none"
            />
          </div>

          {/* Fields grid */}
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] mb-1 uppercase">Format</label>
              <select
                value={formData.outputFormat || ''}
                onChange={e => handleFormField('outputFormat', e.target.value)}
                className="w-full px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] focus:ring-2 focus:ring-pink-400 outline-none bg-white"
              >
                <option value="">—</option>
                {['HLS', 'DASH', 'MP4', 'WEBM', 'MKV', 'MP3', 'AAC', 'FLAC', 'OGG'].map(f => (
                  <option key={f} value={f}>{f}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] mb-1 uppercase">Resolution</label>
              <select
                value={formData.resolution || ''}
                onChange={e => handleFormField('resolution', e.target.value)}
                className="w-full px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] focus:ring-2 focus:ring-pink-400 outline-none bg-white"
              >
                <option value="">—</option>
                {['4K', '1080p', '720p', '480p', '360p', 'Original'].map(r => (
                  <option key={r} value={r}>{r}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] mb-1 uppercase">Bitrate</label>
              <input
                value={formData.bitrate || ''}
                onChange={e => handleFormField('bitrate', e.target.value)}
                placeholder="e.g. 5000k"
                className="w-full px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] focus:ring-2 focus:ring-pink-400 outline-none"
              />
            </div>
            <div>
              <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] mb-1 uppercase">FPS</label>
              <input
                type="number"
                value={formData.fps || ''}
                onChange={e => handleFormField('fps', e.target.value ? Number(e.target.value) : undefined)}
                placeholder="e.g. 30"
                className="w-full px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] focus:ring-2 focus:ring-pink-400 outline-none"
              />
            </div>
            <div>
              <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] mb-1 uppercase">Codec</label>
              <select
                value={formData.codec || ''}
                onChange={e => handleFormField('codec', e.target.value)}
                className="w-full px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] focus:ring-2 focus:ring-pink-400 outline-none bg-white"
              >
                <option value="">—</option>
                {['H264', 'H265', 'AV1', 'VP9'].map(c => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] mb-1 uppercase">Audio Bitrate</label>
              <input
                value={formData.audioBitrate || ''}
                onChange={e => handleFormField('audioBitrate', e.target.value)}
                placeholder="e.g. 128k"
                className="w-full px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] focus:ring-2 focus:ring-pink-400 outline-none"
              />
            </div>
          </div>

          {/* Custom encoding params */}
          <div>
            <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.45)] mb-1 uppercase">Custom Encoding Params</label>
            <input
              value={formData.customFfmpegParams || ''}
              onChange={e => handleFormField('customFfmpegParams', e.target.value)}
              placeholder="e.g. -preset slow -crf 18"
              className="w-full px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] focus:ring-2 focus:ring-pink-400 outline-none"
            />
          </div>

          {/* Form error */}
          {formError && (
            <p className="text-[11px] text-red-600 bg-red-50 rounded-lg px-2 py-1">{formError}</p>
          )}

          {/* Form actions */}
          <div className="flex items-center gap-2 pt-1">
            <button
              onClick={handleSave}
              disabled={saving}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-gradient-to-r from-pink-500 to-rose-600 text-white text-[11px] font-bold rounded-lg shadow disabled:opacity-50 transition-all hover:scale-[1.02]"
            >
              {saving ? <Icon icon="mdi:loading" className="animate-spin text-sm" /> : <Icon icon="mdi:check" className="text-sm" />}
              {saving ? 'Saving…' : (editingPreset ? 'Update' : 'Create')}
            </button>
            <button
              onClick={() => setShowForm(false)}
              className="px-3 py-1.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.8)] transition-colors"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
