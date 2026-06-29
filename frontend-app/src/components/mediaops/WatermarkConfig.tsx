import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export type WatermarkType = 'image' | 'text';

export interface WatermarkConfigProps {
  watermarkType: WatermarkType | null;
  watermarkImage: File | null;
  watermarkText: string;
  watermarkX: number;   // center %, 0-100
  watermarkY: number;   // center %, 0-100
  opacity: number;      // 0-100
  scale: number;        // % of video width, 5-50
  onTypeChange: (type: WatermarkType | null) => void;
  onImageChange: (file: File | null) => void;
  onTextChange: (text: string) => void;
  onPositionChange: (x: number, y: number) => void;
  onOpacityChange: (opacity: number) => void;
  onScaleChange: (scale: number) => void;
}

// ─── Position presets ─────────────────────────────────────────────────────────

const PRESETS = [
  { label: 'Top Left',      icon: 'mdi:arrow-top-left',    x: 10, y: 10 },
  { label: 'Top Center',    icon: 'mdi:arrow-up',          x: 50, y: 10 },
  { label: 'Top Right',     icon: 'mdi:arrow-top-right',   x: 90, y: 10 },
  { label: 'Middle Left',   icon: 'mdi:arrow-left',        x: 10, y: 50 },
  { label: 'Center',        icon: 'mdi:circle-medium',     x: 50, y: 50 },
  { label: 'Middle Right',  icon: 'mdi:arrow-right',       x: 90, y: 50 },
  { label: 'Bottom Left',   icon: 'mdi:arrow-bottom-left', x: 10, y: 90 },
  { label: 'Bottom Center', icon: 'mdi:arrow-down',        x: 50, y: 90 },
  { label: 'Bottom Right',  icon: 'mdi:arrow-bottom-right',x: 90, y: 90 },
];

const MAX_IMAGE_SIZE_MB = 5;
const MAX_IMAGE_SIZE_BYTES = MAX_IMAGE_SIZE_MB * 1024 * 1024;

function formatBytes(bytes: number): string {
  if (!bytes) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function WatermarkConfig({
  watermarkType, watermarkImage, watermarkText, watermarkX, watermarkY, opacity, scale,
  onTypeChange, onImageChange, onTextChange, onPositionChange, onOpacityChange, onScaleChange,
}: WatermarkConfigProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const handleImageSelect = (file: File | null) => {
    if (file && file.size > MAX_IMAGE_SIZE_BYTES) return;
    onImageChange(file);
  };

  const activePreset = PRESETS.find(p => Math.abs(p.x - watermarkX) <= 5 && Math.abs(p.y - watermarkY) <= 5);

  return (
    <div className="space-y-3">
      {/* Type toggle */}
      <div className={`flex items-center gap-1 rounded-lg p-0.5 w-fit ${dark ? 'bg-[rgba(255,255,255,0.05)]' : 'bg-[rgba(0,0,0,0.05)]'}`}>
        {([['image', 'mdi:image-outline', 'Image'], ['text', 'mdi:format-text', 'Text']] as const).map(([type, icon, label]) => (
          <button key={type}
            onClick={() => onTypeChange(watermarkType === type ? null : type)}
            className={`px-3 py-1 rounded-md text-[11px] font-bold transition-all flex items-center gap-1 ${
              watermarkType === type
                ? dark ? 'bg-[#2a2a2a] shadow text-[rgba(255,255,255,0.88)]' : 'bg-white shadow text-[rgba(0,0,0,0.8)]'
                : dark ? 'text-[rgba(255,255,255,0.45)] hover:text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]'
            }`}
          >
            <Icon icon={icon} className="text-sm" />
            {label}
          </button>
        ))}
      </div>

      {/* Image upload */}
      {watermarkType === 'image' && (
        <label className={`flex flex-col items-center justify-center gap-1.5 w-full border-2 border-dashed rounded-xl cursor-pointer transition-colors px-4 py-3 ${
          watermarkImage
            ? 'border-blue-300 bg-blue-50/80'
            : dark ? 'border-[rgba(255,255,255,0.12)] hover:border-blue-400 hover:bg-blue-500/5' : 'border-[rgba(0,0,0,0.12)] hover:border-blue-300 hover:bg-blue-50/50'
        }`}>
          <input type="file" accept=".png,.jpg,.jpeg,.svg" className="hidden"
            onChange={(e) => handleImageSelect(e.target.files?.[0] ?? null)} />
          {watermarkImage ? (
            <div className="flex items-center gap-3 w-full">
              <div className="w-9 h-9 bg-blue-100 rounded-lg flex items-center justify-center flex-shrink-0">
                <Icon icon="mdi:image-outline" className="text-blue-500 text-lg" />
              </div>
              <div className="flex-1 min-w-0">
                <p className={`text-[12px] font-bold truncate ${dark ? 'text-[rgba(255,255,255,0.88)]' : 'text-[rgba(0,0,0,0.88)]'}`}>{watermarkImage.name}</p>
                <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>{formatBytes(watermarkImage.size)}</p>
              </div>
              <button type="button" onClick={(e) => { e.preventDefault(); onImageChange(null); }}
                className="p-1.5 rounded-lg hover:bg-blue-100 transition-colors flex-shrink-0">
                <Icon icon="mdi:close" className={`text-sm ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`} />
              </button>
            </div>
          ) : (
            <>
              <Icon icon="mdi:image-plus-outline" className={`text-2xl ${dark ? 'text-[rgba(255,255,255,0.2)]' : 'text-[rgba(0,0,0,0.2)]'}`} />
              <p className={`text-[11px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.55)]' : 'text-[rgba(0,0,0,0.55)]'}`}>Upload watermark image</p>
              <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]'}`}>PNG, JPG or SVG · max {MAX_IMAGE_SIZE_MB}MB</p>
            </>
          )}
        </label>
      )}

      {/* Text input */}
      {watermarkType === 'text' && (
        <input value={watermarkText} onChange={(e) => onTextChange(e.target.value)}
          placeholder="Enter watermark text…"
          className={`w-full px-3 py-2 border rounded-xl text-[12px] focus:ring-2 focus:ring-blue-500/25 focus:border-blue-500 outline-none transition-shadow ${
            dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white placeholder-[rgba(255,255,255,0.25)]' : 'border-[rgba(0,0,0,0.12)] placeholder-[rgba(0,0,0,0.3)]'
          }`} />
      )}

      {/* Position + Size + Opacity */}
      {watermarkType && (
        <div className="space-y-3">
          {/* Position snap grid */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <p className={`text-[10px] font-semibold uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>Position</p>
              <span className={`text-[9px] font-medium px-1.5 py-0.5 rounded ${
                activePreset
                  ? dark ? 'text-blue-300 bg-blue-500/15' : 'text-blue-600 bg-blue-50'
                  : dark ? 'text-[rgba(255,255,255,0.3)] bg-[rgba(255,255,255,0.05)]' : 'text-[rgba(0,0,0,0.35)] bg-[rgba(0,0,0,0.04)]'
              }`}>
                {activePreset?.label ?? 'Custom (drag in preview)'}
              </span>
            </div>
            <div className={`grid grid-cols-3 gap-1 p-1.5 rounded-xl w-fit ${dark ? 'bg-[rgba(255,255,255,0.04)]' : 'bg-[rgba(0,0,0,0.04)]'}`}>
              {PRESETS.map((preset) => {
                const isActive = Math.abs(preset.x - watermarkX) <= 5 && Math.abs(preset.y - watermarkY) <= 5;
                return (
                  <button
                    key={preset.label}
                    onClick={() => onPositionChange(preset.x, preset.y)}
                    title={preset.label}
                    className={`w-9 h-9 rounded-lg flex items-center justify-center transition-all ${
                      isActive
                        ? 'bg-blue-500 text-white shadow-md scale-105'
                        : dark
                          ? 'text-[rgba(255,255,255,0.4)] hover:bg-[rgba(255,255,255,0.08)] hover:text-[rgba(255,255,255,0.8)]'
                          : 'text-[rgba(0,0,0,0.4)] hover:bg-[rgba(0,0,0,0.08)] hover:text-[rgba(0,0,0,0.7)]'
                    }`}
                  >
                    <Icon icon={preset.icon} className="text-base" />
                  </button>
                );
              })}
            </div>
            <p className={`text-[9px] mt-1.5 flex items-center gap-1 ${dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]'}`}>
              <Icon icon="mdi:cursor-move" className="text-[10px]" />
              Drag the watermark on the preview above to set an exact position
            </p>
          </div>

          {/* Size + Opacity sliders — compact two-row layout */}
          <div className="grid grid-cols-2 gap-x-5 gap-y-2.5">
            {/* Size */}
            <div>
              <div className="flex items-center justify-between mb-1">
                <span className={`text-[10px] font-semibold uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.45)]' : 'text-[rgba(0,0,0,0.45)]'}`}>Size</span>
                <span className={`text-[11px] font-bold tabular-nums ${dark ? 'text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.65)]'}`}>{scale}%</span>
              </div>
              <input type="range" min={5} max={50} step={1} value={scale}
                onChange={(e) => onScaleChange(Number(e.target.value))}
                className={`w-full h-1.5 rounded-full appearance-none cursor-pointer accent-blue-500 ${dark ? 'bg-[rgba(255,255,255,0.08)]' : 'bg-[rgba(0,0,0,0.08)]'}`} />
            </div>
            {/* Opacity */}
            <div>
              <div className="flex items-center justify-between mb-1">
                <span className={`text-[10px] font-semibold uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.45)]' : 'text-[rgba(0,0,0,0.45)]'}`}>Opacity</span>
                <span className={`text-[11px] font-bold tabular-nums ${dark ? 'text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.65)]'}`}>{opacity}%</span>
              </div>
              <input type="range" min={0} max={100} step={1} value={opacity}
                onChange={(e) => onOpacityChange(Number(e.target.value))}
                className={`w-full h-1.5 rounded-full appearance-none cursor-pointer accent-blue-500 ${dark ? 'bg-[rgba(255,255,255,0.08)]' : 'bg-[rgba(0,0,0,0.08)]'}`} />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
