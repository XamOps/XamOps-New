import { useState } from 'react';
import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface ThumbnailGridProps {
  thumbnailPaths: string[];
  jobId: number;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function getFilename(path: string): string {
  const parts = path.replace(/\\/g, '/').split('/');
  return parts[parts.length - 1] || path;
}

function buildThumbnailUrl(jobId: number, path: string): string {
  if (path.startsWith('http://') || path.startsWith('https://') || path.startsWith('/')) return path;
  const filename = getFilename(path);
  return `/media/jobs/${jobId}/thumbnails/${filename}`;
}

// ─── Component ────────────────────────────────────────────────────────────────

export function ThumbnailGrid({ thumbnailPaths, jobId }: ThumbnailGridProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const [lightboxUrl, setLightboxUrl] = useState<string | null>(null);
  const [lightboxIndex, setLightboxIndex] = useState<number>(0);

  if (!thumbnailPaths || thumbnailPaths.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-10 px-4">
        <div className={`w-12 h-12 rounded-2xl flex items-center justify-center mb-3 ${dark ? 'bg-[rgba(255,255,255,0.04)]' : 'bg-[rgba(0,0,0,0.04)]'}`}>
          <Icon icon="mdi:image-off-outline" className={`text-2xl ${dark ? 'text-[rgba(255,255,255,0.2)]' : 'text-[rgba(0,0,0,0.2)]'}`} />
        </div>
        <p className={`text-[12px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>No thumbnails generated</p>
        <p className={`text-[10px] mt-0.5 ${dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.3)]'}`}>
          Enable thumbnail generation in job settings to extract preview images.
        </p>
      </div>
    );
  }

  const openLightbox = (url: string, index: number) => { setLightboxUrl(url); setLightboxIndex(index); };
  const closeLightbox = () => setLightboxUrl(null);
  const navigateLightbox = (direction: 'prev' | 'next') => {
    const newIndex = direction === 'next'
      ? (lightboxIndex + 1) % thumbnailPaths.length
      : (lightboxIndex - 1 + thumbnailPaths.length) % thumbnailPaths.length;
    setLightboxIndex(newIndex);
    setLightboxUrl(buildThumbnailUrl(jobId, thumbnailPaths[newIndex]));
  };

  return (
    <>
      <div className="flex items-center gap-2 mb-3">
        <div className="flex items-center gap-1.5 px-2.5 py-1 bg-blue-50 rounded-lg">
          <Icon icon="mdi:image-multiple-outline" className="text-blue-500 text-sm" />
          <span className="text-[11px] font-bold text-blue-600">{thumbnailPaths.length} {thumbnailPaths.length === 1 ? 'image' : 'images'}</span>
        </div>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-2">
        {thumbnailPaths.map((path, idx) => {
          const url = buildThumbnailUrl(jobId, path);
          return (
            <button key={`${path}-${idx}`} type="button" onClick={() => openLightbox(url, idx)}
              className={`group relative aspect-video rounded-xl overflow-hidden shadow-sm border hover:shadow-md transition-all focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 ${
                dark ? 'border-[rgba(255,255,255,0.06)] hover:border-blue-200' : 'border-[rgba(0,0,0,0.06)] hover:border-blue-200'
              }`} aria-label={`View thumbnail ${idx + 1}`}>
              <img src={url} alt={`Thumbnail ${idx + 1}`} loading="lazy" className="w-full h-full object-cover transition-transform duration-200 group-hover:scale-110" />
              <div className="absolute inset-0 bg-black/0 group-hover:bg-black/20 transition-colors flex items-center justify-center">
                <Icon icon="mdi:magnify-plus-outline" className="text-white text-lg opacity-0 group-hover:opacity-100 transition-opacity drop-shadow" />
              </div>
              <span className="absolute bottom-1 right-1 bg-black/50 text-white text-[9px] font-semibold px-1.5 py-0.5 rounded">{idx + 1}</span>
            </button>
          );
        })}
      </div>

      {lightboxUrl && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm"
          onClick={closeLightbox}
          onKeyDown={(e) => { if (e.key === 'Escape') closeLightbox(); if (e.key === 'ArrowLeft') navigateLightbox('prev'); if (e.key === 'ArrowRight') navigateLightbox('next'); }}
          role="dialog" aria-modal="true" aria-label="Thumbnail preview">
          <div className="relative max-w-4xl max-h-[85vh] mx-4" onClick={(e) => e.stopPropagation()}>
            <img src={lightboxUrl} alt={`Thumbnail ${lightboxIndex + 1} of ${thumbnailPaths.length}`} className="rounded-xl shadow-2xl max-h-[85vh] w-auto object-contain" />
            <button type="button" onClick={closeLightbox} className="absolute -top-3 -right-3 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center hover:bg-gray-100 transition-colors" aria-label="Close preview">
              <Icon icon="mdi:close" className="text-[rgba(0,0,0,0.6)] text-base" />
            </button>
            {thumbnailPaths.length > 1 && (
              <>
                <button type="button" onClick={() => navigateLightbox('prev')} className="absolute left-0 top-1/2 -translate-y-1/2 -translate-x-12 w-9 h-9 bg-white/90 rounded-full shadow-lg flex items-center justify-center hover:bg-white transition-colors" aria-label="Previous thumbnail">
                  <Icon icon="mdi:chevron-left" className="text-[rgba(0,0,0,0.7)] text-xl" />
                </button>
                <button type="button" onClick={() => navigateLightbox('next')} className="absolute right-0 top-1/2 -translate-y-1/2 translate-x-12 w-9 h-9 bg-white/90 rounded-full shadow-lg flex items-center justify-center hover:bg-white transition-colors" aria-label="Next thumbnail">
                  <Icon icon="mdi:chevron-right" className="text-[rgba(0,0,0,0.7)] text-xl" />
                </button>
              </>
            )}
            <div className="absolute -bottom-3 left-0 right-0 flex items-center justify-between px-1">
              <span className="px-2.5 py-1 bg-white rounded-full shadow-lg text-[11px] font-semibold text-[rgba(0,0,0,0.6)]">{lightboxIndex + 1} / {thumbnailPaths.length}</span>
              <a href={lightboxUrl} target="_blank" rel="noopener noreferrer" className="flex items-center gap-1 px-3 py-1.5 bg-white rounded-full shadow-lg text-[11px] font-semibold text-[rgba(0,0,0,0.6)] hover:text-[rgba(0,0,0,0.9)] transition-colors">
                <Icon icon="mdi:open-in-new" className="text-sm" />Open full size
              </a>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
