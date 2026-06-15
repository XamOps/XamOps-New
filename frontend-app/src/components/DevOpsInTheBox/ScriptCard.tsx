import React, { useCallback } from 'react';
import { Icon } from '@iconify/react';
import { DevOpsScript, CloudProvider, DifficultyLevel } from '../../types/devopsInTheBox';

/**
 * ScriptCard — a reusable, presentational card for a single DevOps script.
 *
 * It renders script metadata (icon, name, description, category, cloud
 * providers), engagement metrics (downloads, rating, difficulty) and a favorite
 * toggle. It is purely presentational: it receives a script plus callbacks and
 * never fetches data itself.
 *
 * Requirements: REQ-2.5.1 (Script Cards)
 */

// ─── Display constants ──────────────────────────────────────────────────────────

const NAME_MAX_CHARS = 60;
const DESCRIPTION_MAX_CHARS = 120;
const MAX_VISIBLE_PROVIDERS = 3;

// ─── Category presentation ───────────────────────────────────────────────────────

interface CategoryMeta {
  icon: string;
  iconColor: string;
  bg: string;
  text: string;
  border: string;
}

const CATEGORY_META: Record<string, CategoryMeta> = {
  terraform: { icon: 'simple-icons:terraform', iconColor: '#7C3AED', bg: 'bg-purple-50', text: 'text-purple-700', border: 'border-purple-200' },
  bash: { icon: 'mdi:console', iconColor: '#16A34A', bg: 'bg-emerald-50', text: 'text-emerald-700', border: 'border-emerald-200' },
  python: { icon: 'mdi:language-python', iconColor: '#2563EB', bg: 'bg-blue-50', text: 'text-blue-700', border: 'border-blue-200' },
  ansible: { icon: 'simple-icons:ansible', iconColor: '#DC2626', bg: 'bg-red-50', text: 'text-red-700', border: 'border-red-200' },
};

const DEFAULT_META: CategoryMeta = {
  icon: 'mdi:file-code-outline',
  iconColor: 'rgba(0,0,0,0.4)',
  bg: 'bg-[rgba(0,0,0,0.03)]',
  text: 'text-[rgba(0,0,0,0.55)]',
  border: 'border-[rgba(0,0,0,0.08)]',
};

function catMeta(category: string): CategoryMeta {
  return CATEGORY_META[category?.toLowerCase()] || DEFAULT_META;
}

// ─── Cloud provider presentation ──────────────────────────────────────────────────

const PROVIDER_LABEL: Record<CloudProvider, string> = {
  aws: 'AWS',
  azure: 'Azure',
  gcp: 'GCP',
  'multi-cloud': 'Multi-Cloud',
};

function providerLabel(provider: CloudProvider): string {
  return PROVIDER_LABEL[provider] ?? provider;
}

// ─── Difficulty presentation ───────────────────────────────────────────────────────

const DIFFICULTY_META: Record<DifficultyLevel, { bg: string; text: string; border: string }> = {
  beginner: { bg: 'bg-emerald-50', text: 'text-emerald-700', border: 'border-emerald-200' },
  intermediate: { bg: 'bg-amber-50', text: 'text-amber-700', border: 'border-amber-200' },
  advanced: { bg: 'bg-rose-50', text: 'text-rose-700', border: 'border-rose-200' },
};

// ─── Helpers ────────────────────────────────────────────────────────────────────

/** Truncate `text` to `maxLen` characters, appending an ellipsis when cut. */
export function truncate(text: string, maxLen: number): string {
  if (!text) return '';
  return text.length > maxLen ? `${text.slice(0, maxLen).trimEnd()}…` : text;
}

/** Compact a download count to a short human-readable form (e.g. 1.2k). */
function formatCount(count: number): string {
  const n = count ?? 0;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(n % 1_000_000 === 0 ? 0 : 1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(n % 1_000 === 0 ? 0 : 1)}k`;
  return `${n}`;
}

// ─── Props ──────────────────────────────────────────────────────────────────────

export interface ScriptCardProps {
  /** The script to display. */
  script: DevOpsScript;
  /** Invoked when the card is activated (click / Enter / Space) to open detail. */
  onOpenDetail: (script: DevOpsScript) => void;
  /** Invoked when the favorite (heart) button is toggled. */
  onToggleFavorite: (script: DevOpsScript) => void;
  /** Optional index used for a subtle staggered entrance animation. */
  index?: number;
}

// ─── Component ────────────────────────────────────────────────────────────────────

const ScriptCard: React.FC<ScriptCardProps> = ({ script, onOpenDetail, onToggleFavorite, index = 0 }) => {
  const m = catMeta(script.category);
  const providers = script.cloudProviders ?? [];
  const visibleProviders = providers.slice(0, MAX_VISIBLE_PROVIDERS);
  const extraProviders = Math.max(0, providers.length - MAX_VISIBLE_PROVIDERS);
  const difficultyMeta = DIFFICULTY_META[script.difficulty] ?? DEFAULT_META;
  const isFavorited = !!script.isFavorited;
  const downloads = script.downloadCount ?? script.downloads ?? 0;
  const rating = script.averageRating ?? 0;
  const ratingCount = script.ratingCount ?? 0;

  const handleOpen = useCallback(() => onOpenDetail(script), [onOpenDetail, script]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        onOpenDetail(script);
      }
    },
    [onOpenDetail, script]
  );

  const handleFavorite = useCallback(
    (e: React.MouseEvent) => {
      // Don't bubble to the card click handler / detail modal.
      e.stopPropagation();
      onToggleFavorite(script);
    },
    [onToggleFavorite, script]
  );

  return (
    <div
      role="button"
      tabIndex={0}
      aria-label={`Open details for ${script.name}`}
      onClick={handleOpen}
      onKeyDown={handleKeyDown}
      style={{ animation: `fadeInUp 0.3s ease ${Math.min(index * 0.04, 0.4)}s both` }}
      className={`group relative flex flex-col bg-white border ${m.border} rounded-xl p-5 cursor-pointer outline-none transition-all duration-200 hover:shadow-lg hover:-translate-y-0.5 hover:scale-[1.01] focus-visible:ring-2 focus-visible:ring-indigo-400`}
    >
      {/* Header: category icon + favorite + category badge */}
      <div className="flex items-start justify-between mb-3">
        <div className={`w-10 h-10 ${m.bg} border ${m.border} rounded-lg flex items-center justify-center flex-shrink-0`}>
          <Icon icon={m.icon} width={18} color={m.iconColor} />
        </div>

        <div className="flex items-center gap-2">
          <span className={`inline-flex items-center px-2.5 py-1 ${m.bg} border ${m.border} rounded-full text-[11px] font-semibold ${m.text} capitalize`}>
            {script.category}
          </span>
          <button
            type="button"
            onClick={handleFavorite}
            aria-pressed={isFavorited}
            aria-label={isFavorited ? `Remove ${script.name} from favorites` : `Add ${script.name} to favorites`}
            title={isFavorited ? 'Remove from favorites' : 'Add to favorites'}
            className={`w-8 h-8 flex items-center justify-center rounded-lg border transition-colors ${
              isFavorited
                ? 'bg-rose-50 border-rose-200 text-rose-500'
                : 'bg-white border-[rgba(0,0,0,0.10)] text-[rgba(0,0,0,0.35)] hover:bg-rose-50 hover:text-rose-400'
            }`}
          >
            <Icon icon={isFavorited ? 'mdi:heart' : 'mdi:heart-outline'} width={15} />
          </button>
        </div>
      </div>

      {/* Name + description */}
      <h3 className="font-bold text-[rgba(0,0,0,0.88)] text-[14px] mb-1.5 line-clamp-1" title={script.name}>
        {truncate(script.name, NAME_MAX_CHARS)}
      </h3>
      <p className="text-[12px] text-[rgba(0,0,0,0.45)] line-clamp-2 mb-3 leading-relaxed">
        {truncate(script.description, DESCRIPTION_MAX_CHARS)}
      </p>

      {/* Cloud provider tags + difficulty */}
      <div className="flex flex-wrap items-center gap-1.5 mb-4">
        {visibleProviders.map((p) => (
          <span
            key={p}
            className="inline-flex items-center px-2 py-0.5 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-md text-[10px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-[0.04em]"
          >
            {providerLabel(p)}
          </span>
        ))}
        {extraProviders > 0 && (
          <span
            className="inline-flex items-center px-2 py-0.5 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-md text-[10px] font-semibold text-[rgba(0,0,0,0.5)]"
            title={providers.slice(MAX_VISIBLE_PROVIDERS).map(providerLabel).join(', ')}
          >
            +{extraProviders} more
          </span>
        )}
        {script.difficulty && (
          <span className={`inline-flex items-center px-2 py-0.5 ${difficultyMeta.bg} border ${difficultyMeta.border} rounded-md text-[10px] font-semibold ${difficultyMeta.text} capitalize ml-auto`}>
            {script.difficulty}
          </span>
        )}
      </div>

      {/* Metrics footer */}
      <div className="flex items-center gap-3 text-[11px] text-[rgba(0,0,0,0.4)] mt-auto pt-3 border-t border-[rgba(0,0,0,0.06)]">
        <span className="flex items-center gap-1" title={`${downloads} downloads`}>
          <Icon icon="mdi:download-outline" width={12} />
          {formatCount(downloads)}
        </span>
        <span className="flex items-center gap-1" title={`Average rating ${rating.toFixed(1)} from ${ratingCount} ratings`}>
          <Icon icon="mdi:star" width={12} color="#F59E0B" />
          <span className="font-semibold text-[rgba(0,0,0,0.6)]">{rating.toFixed(1)}</span>
          <span className="text-[rgba(0,0,0,0.35)]">({formatCount(ratingCount)})</span>
        </span>
        <span className="ml-auto flex items-center gap-1 text-[11px] font-semibold text-indigo-600 opacity-0 group-hover:opacity-100 transition-opacity">
          View <Icon icon="mdi:arrow-right" width={12} />
        </span>
      </div>
    </div>
  );
};

export default ScriptCard;
