import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export type WatermarkType = 'image' | 'text';
export type WatermarkPosition = 'TOP_LEFT' | 'TOP_RIGHT' | 'CENTER' | 'BOTTOM_LEFT' | 'BOTTOM_RIGHT';

export interface WatermarkConfigProps {
  watermarkType: WatermarkType | null;
  watermarkImage: File | null;
  watermarkText: string;
  position: WatermarkPosition;
  opacity: number;
  onTypeChange: (type: WatermarkType | null) => void;
  onImageChange: (file: File | null) => void;
  onTextChange: (text: string) => void;
  onPositionChange: (position: WatermarkPosition) => void;
  onOpacityChange: (opacity: number) => void;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const MAX_IMAGE_SIZE_MB = 5;
const MAX_IMAGE_SIZE_BYTES = MAX_IMAGE_SIZE_MB * 1024 * 1024;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatBytes(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function WatermarkConfig({
  watermarkType, watermarkImage, watermarkText, position, opacity,
  onTypeChange, onImageChange, onTextChange, onPositionChange, onOpacityChange,
}: WatermarkConfigProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const handleImageSelect = (file: File | null) => {
    if (file && file.size > MAX_IMAGE_SIZE_BYTES) return;
    onImageChange(file);
  };

  return (
    <div className="space-y-3">
      {/* Type toggle: Image vs Text */}
      <div className={`flex items-center gap-1 rounded-lg p-0.5 w-fit ${dark ? 'bg-[rgba(255,255,255,0.05)]' : 'bg-[rgba(0,0,0,0.05)]'}`}>
        <button
          onClick={() => onTypeChange(watermarkType === 'image' ? null : 'image')}
          className={`px-3 py-1 rounded-md text-[11px] font-bold transition-all ${
            watermarkType === 'image'
              ? (dark ? 'bg-[#2a2a2a] shadow text-[rgba(255,255,255,0.88)]' : 'bg-white shadow text-[rgba(0,0,0,0.8)]')
              : (dark ? 'text-[rgba(255,255,255,0.45)] hover:text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]')
          }`}
        >
          <Icon icon="mdi:image-outline" className="inline mr-1 text-sm align-text-bottom" />
          Image
        </button>
        <button
          onClick={() => onTypeChange(watermarkType === 'text' ? null : 'text')}
          className={`px-3 py-1 rounded-md text-[11px] font-bold transition-all ${
            watermarkType === 'text'
              ? (dark ? 'bg-[#2a2a2a] shadow text-[rgba(255,255,255,0.88)]' : 'bg-white shadow text-[rgba(0,0,0,0.8)]')
              : (dark ? 'text-[rgba(255,255,255,0.45)] hover:text-[rgba(255,255,255,0.65)]' : 'text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.65)]')
          }`}
        >
          <Icon icon="mdi:format-text" className="inline mr-1 text-sm align-text-bottom" />
          Text
        </button>
      </div>

      {/* Image upload */}
      {watermarkType === 'image' && (
        <label className={`flex flex-col items-center justify-center gap-1.5 w-full border-2 border-dashed rounded-xl cursor-pointer transition-colors px-4 py-3 ${
          watermarkImage
            ? 'border-pink-300 bg-pink-50'
            : dark ? 'border-[rgba(255,255,255,0.12)] hover:border-pink-300 hover:bg-pink-50/10' : 'border-[rgba(0,0,0,0.12)] hover:border-pink-300 hover:bg-pink-50/40'
        }`}>
          <input type="file" accept=".png,.jpg,.jpeg" className="hidden"
            onChange={(e) => handleImageSelect(e.target.files?.[0] ?? null)} />
          {watermarkImage ? (
            <div className="flex items-center gap-3 w-full">
              <div className="w-9 h-9 bg-pink-100 rounded-lg flex items-center justify-center flex-shrink-0">
                <Icon icon="mdi:image-outline" className="text-pink-500 text-lg" />
              </div>
              <div className="flex-1 min-w-0">
                <p className={`text-[12px] font-bold truncate ${dark ? 'text-[rgba(255,255,255,0.88)]' : 'text-[rgba(0,0,0,0.88)]'}`}>{watermarkImage.name}</p>
                <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>{formatBytes(watermarkImage.size)}</p>
              </div>
              <button type="button" onClick={(e) => { e.preventDefault(); onImageChange(null); }}
                className="p-1.5 rounded-lg hover:bg-pink-100 transition-colors flex-shrink-0">
                <Icon icon="mdi:close" className={`text-sm ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`} />
              </button>
            </div>
          ) : (
            <>
              <Icon icon="mdi:image-plus-outline" className={`text-xl ${dark ? 'text-[rgba(255,255,255,0.2)]' : 'text-[rgba(0,0,0,0.2)]'}`} />
              <p className={`text-[11px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.55)]' : 'text-[rgba(0,0,0,0.55)]'}`}>Upload watermark image</p>
              <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]'}`}>PNG or JPG (max {MAX_IMAGE_SIZE_MB}MB)</p>
            </>
          )}
        </label>
      )}

      {/* Text input */}
      {watermarkType === 'text' && (
        <input value={watermarkText} onChange={(e) => onTextChange(e.target.value)}
          placeholder="Enter watermark text…"
          className={`w-full px-3 py-2 border rounded-xl text-[12px] focus:ring-2 focus:ring-pink-400 focus:border-pink-400 outline-none ${
            dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white placeholder-[rgba(255,255,255,0.25)]' : 'border-[rgba(0,0,0,0.12)] placeholder-[rgba(0,0,0,0.3)]'
          }`} />
      )}

      {/* Position selector + Opacity — only visible when a type is selected */}
      {watermarkType && (
        <div className="space-y-3">
          {/* Position grid */}
          <div>
            <p className={`text-[10px] font-semibold uppercase tracking-wide mb-1.5 ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>Position</p>
            <div className="grid grid-cols-3 gap-1 w-fit">
              {(['TOP_LEFT', null, 'TOP_RIGHT', null, 'CENTER', null, 'BOTTOM_LEFT', null, 'BOTTOM_RIGHT'] as const).map((pos, i) => {
                if (pos === null) return <div key={i} className="w-8 h-8" />;
                const symbols: Record<string, string> = { TOP_LEFT: '↖', TOP_RIGHT: '↗', CENTER: '·', BOTTOM_LEFT: '↙', BOTTOM_RIGHT: '↘' };
                return (
                  <button key={pos} onClick={() => onPositionChange(pos)} title={pos.replace('_', ' ')}
                    className={`w-8 h-8 rounded-md text-[10px] font-bold transition-all ${
                      position === pos
                        ? 'bg-pink-500 text-white shadow'
                        : dark ? 'bg-[rgba(255,255,255,0.05)] text-[rgba(255,255,255,0.5)] hover:bg-[rgba(255,255,255,0.1)]' : 'bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.1)]'
                    }`}>{symbols[pos]}</button>
                );
              })}
            </div>
          </div>

          {/* Opacity slider */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <p className={`text-[10px] font-semibold uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>Opacity</p>
              <span className={`text-[11px] font-bold ${dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.6)]'}`}>{opacity}%</span>
            </div>
            <input type="range" min={0} max={100} value={opacity}
              onChange={(e) => onOpacityChange(Number(e.target.value))}
              className={`w-full h-1.5 rounded-full appearance-none cursor-pointer accent-pink-500 ${dark ? 'bg-[rgba(255,255,255,0.08)]' : 'bg-[rgba(0,0,0,0.08)]'}`} />
            <div className="flex justify-between mt-0.5">
              <span className={`text-[9px] ${dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]'}`}>0%</span>
              <span className={`text-[9px] ${dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]'}`}>100%</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
