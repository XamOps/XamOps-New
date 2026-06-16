import React, { useCallback, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface BatchUploadProps {
  files: File[];
  sharedOutputFormat: string;
  sharedResolution: string;
  onFilesChange: (files: File[]) => void;
  onConfigChange: (updates: { sharedOutputFormat?: string; sharedResolution?: string }) => void;
  onSubmit: () => void;
  submitLoading: boolean;
}

const OUTPUT_FORMATS = ['HLS', 'DASH', 'MP4', 'WEBM', 'MKV', 'MP3', 'AAC', 'FLAC', 'OGG'];
const RESOLUTIONS = [
  { value: '4K', label: '4K (2160p)' }, { value: '1080p', label: '1080p' },
  { value: '720p', label: '720p' }, { value: '480p', label: '480p' },
  { value: '360p', label: '360p' }, { value: 'Original', label: 'Original' },
];
const ACCEPTED_TYPES = 'video/*,audio/*,.mkv,.avi,.mov,.mp4,.webm,.m4v,.flv,.mp3,.aac,.flac,.ogg,.wav';

function formatBytes(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function BatchUpload({ files, sharedOutputFormat, sharedResolution, onFilesChange, onConfigChange, onSubmit, submitLoading }: BatchUploadProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';
  const inputRef = useRef<HTMLInputElement>(null);

  const handleDrop = useCallback((e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    const dropped = Array.from(e.dataTransfer.files).filter(f => f.type.startsWith('video/') || f.type.startsWith('audio/'));
    if (dropped.length > 0) onFilesChange([...files, ...dropped]);
  }, [files, onFilesChange]);

  const handleDragOver = useCallback((e: React.DragEvent<HTMLDivElement>) => { e.preventDefault(); }, []);

  const handleFileSelect = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files ?? []);
    if (selected.length > 0) onFilesChange([...files, ...selected]);
    if (inputRef.current) inputRef.current.value = '';
  }, [files, onFilesChange]);

  const removeFile = useCallback((index: number) => { onFilesChange(files.filter((_, i) => i !== index)); }, [files, onFilesChange]);
  const totalSize = files.reduce((sum, f) => sum + f.size, 0);

  return (
    <div className="space-y-4">
      {/* Drop Zone */}
      <div onDrop={handleDrop} onDragOver={handleDragOver} onClick={() => inputRef.current?.click()}
        className={`flex flex-col items-center justify-center gap-2 w-full border-2 border-dashed rounded-xl cursor-pointer transition-colors px-4 py-8 ${
          files.length > 0 ? 'border-pink-300 bg-pink-50/40'
            : dark ? 'border-[rgba(255,255,255,0.12)] hover:border-pink-300 hover:bg-pink-50/10' : 'border-[rgba(0,0,0,0.12)] hover:border-pink-300 hover:bg-pink-50/40'
        }`}>
        <input ref={inputRef} type="file" multiple accept={ACCEPTED_TYPES} className="hidden" onChange={handleFileSelect} />
        <Icon icon="mdi:file-multiple-outline" className={`text-3xl ${dark ? 'text-[rgba(255,255,255,0.2)]' : 'text-[rgba(0,0,0,0.2)]'}`} />
        <p className={`text-[13px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.55)]' : 'text-[rgba(0,0,0,0.55)]'}`}>Drop files here or click to browse</p>
        <p className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.35)]' : 'text-[rgba(0,0,0,0.35)]'}`}>Select multiple video/audio files for batch processing</p>
      </div>

      {/* File List */}
      <AnimatePresence>
        {files.length > 0 && (
          <motion.div initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} className="space-y-2">
            <div className="flex items-center justify-between px-1">
              <span className={`text-[11px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>{files.length} file{files.length !== 1 ? 's' : ''} selected</span>
              <span className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>Total: {formatBytes(totalSize)}</span>
            </div>
            <div className="max-h-48 overflow-y-auto space-y-1.5 pr-1">
              {files.map((file, idx) => (
                <motion.div key={`${file.name}-${idx}`} initial={{ opacity: 0, x: -10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: 10 }} transition={{ duration: 0.15 }}
                  className={`flex items-center gap-3 px-3 py-2 rounded-lg border transition-colors ${
                    dark ? 'border-[rgba(255,255,255,0.06)] bg-[#1e1e1e] hover:border-[rgba(255,255,255,0.12)]' : 'border-[rgba(0,0,0,0.06)] bg-white hover:border-[rgba(0,0,0,0.12)]'
                  }`}>
                  <div className="w-8 h-8 bg-pink-50 rounded-lg flex items-center justify-center flex-shrink-0">
                    <Icon icon={file.type.startsWith('audio/') ? 'mdi:music-note-outline' : 'mdi:file-video-outline'} className="text-pink-500 text-base" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className={`text-[12px] font-semibold truncate ${dark ? 'text-[rgba(255,255,255,0.8)]' : 'text-[rgba(0,0,0,0.8)]'}`}>{file.name}</p>
                    <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>{formatBytes(file.size)}</p>
                  </div>
                  <button type="button" onClick={e => { e.stopPropagation(); removeFile(idx); }}
                    className="p-1.5 rounded-lg hover:bg-red-50 transition-colors flex-shrink-0" aria-label={`Remove ${file.name}`}>
                    <Icon icon="mdi:close" className={`text-sm ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'} hover:text-red-500`} />
                  </button>
                </motion.div>
              ))}
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Shared Config */}
      {files.length > 0 && (
        <div className={`border rounded-xl p-4 ${dark ? 'border-[rgba(255,255,255,0.06)] bg-[rgba(255,255,255,0.01)]' : 'border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.01)]'}`}>
          <div className="flex items-center gap-2 mb-3">
            <Icon icon="mdi:cog-sync-outline" className="text-pink-500 text-sm" />
            <span className={`text-[11px] font-bold uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.6)]'}`}>Shared Config (applied to all files)</span>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={`block text-[11px] font-semibold mb-1.5 uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>Output Format</label>
              <select value={sharedOutputFormat} onChange={e => onConfigChange({ sharedOutputFormat: e.target.value })}
                className={`w-full px-3 py-2.5 border rounded-xl text-[13px] focus:ring-2 focus:ring-pink-400 outline-none ${
                  dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white' : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.8)]'
                }`}>
                {OUTPUT_FORMATS.map(fmt => <option key={fmt} value={fmt}>{fmt}</option>)}
              </select>
            </div>
            <div>
              <label className={`block text-[11px] font-semibold mb-1.5 uppercase tracking-wide ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>Resolution</label>
              <select value={sharedResolution} onChange={e => onConfigChange({ sharedResolution: e.target.value })}
                className={`w-full px-3 py-2.5 border rounded-xl text-[13px] focus:ring-2 focus:ring-pink-400 outline-none ${
                  dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.12)] text-white' : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.8)]'
                }`}>
                {RESOLUTIONS.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
              </select>
            </div>
          </div>
        </div>
      )}

      {/* Submit Button */}
      {files.length > 0 && (
        <div className="flex items-center gap-3 pt-1">
          <button onClick={onSubmit} disabled={files.length === 0 || submitLoading}
            className="flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-pink-500 to-rose-600 text-white text-[12px] font-bold rounded-xl shadow disabled:opacity-50 transition-all hover:scale-[1.02]">
            {submitLoading ? <Icon icon="mdi:loading" className="animate-spin" /> : <Icon icon="mdi:playlist-play" />}
            {submitLoading ? 'Submitting Batch…' : `Submit Batch (${files.length} file${files.length !== 1 ? 's' : ''})`}
          </button>
          <button onClick={() => onFilesChange([])}
            className={`px-4 py-2.5 text-[12px] font-semibold transition-colors ${dark ? 'text-[rgba(255,255,255,0.5)] hover:text-[rgba(255,255,255,0.88)]' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)]'}`}>Clear All</button>
        </div>
      )}
    </div>
  );
}
