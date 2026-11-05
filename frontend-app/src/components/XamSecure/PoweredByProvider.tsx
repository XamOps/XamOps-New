/**
 * "powered by <vendor>" beside a module title in the topbar.
 *
 * XamSecure is backed by Upwind and XamLens by Matters.ai, so each module passes its own provider.
 * Kept to this one opt-in place: set VITE_XAMSECURE_SHOW_PROVIDER=false to white-label both again.
 * No link, deliberately: linking out of a white-labelled product invites the customer to buy direct.
 */

import React, { useState } from 'react';
import { useTheme } from '../../contexts/ThemeContext';

// Guarded: this tsconfig does not include vite/client, so import.meta.env is untyped.
const SHOW_PROVIDER = (typeof import.meta !== 'undefined'
  && (import.meta as any).env?.VITE_XAMSECURE_SHOW_PROVIDER) !== 'false';

export type DataProvider = 'upwind' | 'matters';

interface Provider {
  name: string;
  /**
   * Official wordmark, or null to render the name as text. Wordmarks spell the name, so the text is
   * not repeated beside one.
   */
  wordmark: string | null;
  /** Filter for the dark topbar, where both wordmarks' black letters would disappear. */
  darkFilter: string;
}

const PROVIDERS: Record<DataProvider, Provider> = {
  upwind: { name: 'Upwind', wordmark: '/icons/upwind-logo.svg', darkFilter: 'invert' },
  // Official coloured logo with black letters, scaled from the 16650x2240 master to 64px tall (enough
  // for a 12px mark on 3x screens). On dark it becomes a white silhouette: plain inversion turns the
  // gradient orange and green.
  matters: { name: 'Matters.ai', wordmark: '/icons/matters-logo.png', darkFilter: 'brightness-0 invert' },
};

interface Props {
  /** Defaults to Upwind, which is what XamSecure has always shown. */
  provider?: DataProvider;
  className?: string;
}

const PoweredByProvider: React.FC<Props> = ({ provider = 'upwind', className }) => {
  // The source that failed, not a flag: React can reuse this instance when the topbar switches from
  // XamSecure to XamLens, and a flag would carry one vendor's failure over to the other.
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const { theme } = useTheme();
  if (!SHOW_PROVIDER) return null;

  const { name, wordmark, darkFilter } = PROVIDERS[provider];
  const dark = theme === 'dark';

  return (
    <span className={`hidden md:inline-flex items-center gap-1.5 shrink-0 ${className ?? ''}`}>
      <span className={`text-[10px] leading-none ${
        dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.35)]'}`}>
        powered by
      </span>
      {wordmark && failedSrc !== wordmark ? (
        <img
          // Keyed by source so a vendor switch remounts instead of showing the old logo until the new
          // one decodes.
          key={wordmark}
          src={wordmark}
          alt={name}
          // Same muted treatment for both vendors, so the two SecOps attributions read alike.
          className={dark
            ? `h-3 w-auto object-contain opacity-80 ${darkFilter}`
            : 'h-3 w-auto object-contain opacity-60'}
          loading="lazy"
          decoding="async"
          onError={() => setFailedSrc(wordmark)}
        />
      ) : (
        // Also the fallback when an asset fails to load: the name still has to appear, or the phrase
        // dangles.
        <span className={`text-[10px] font-semibold leading-none ${
          dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.5)]'}`}>
          {name}
        </span>
      )}
    </span>
  );
};

export default PoweredByProvider;
