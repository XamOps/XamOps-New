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
}

const PROVIDERS: Record<DataProvider, Provider> = {
  upwind: { name: 'Upwind', wordmark: '/icons/upwind-logo.svg' },
  // No Matters wordmark is vendored. Drop the official SVG into /icons and set the path here.
  matters: { name: 'Matters.ai', wordmark: null },
};

interface Props {
  /** Defaults to Upwind, which is what XamSecure has always shown. */
  provider?: DataProvider;
  className?: string;
}

const PoweredByProvider: React.FC<Props> = ({ provider = 'upwind', className }) => {
  const [wordmarkFailed, setWordmarkFailed] = useState(false);
  const { theme } = useTheme();
  if (!SHOW_PROVIDER) return null;

  const { name, wordmark } = PROVIDERS[provider];
  const dark = theme === 'dark';

  return (
    <span className={`hidden md:inline-flex items-center gap-1.5 shrink-0 ${className ?? ''}`}>
      <span className={`text-[10px] leading-none ${
        dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.35)]'}`}>
        powered by
      </span>
      {wordmark && !wordmarkFailed ? (
        <img
          src={wordmark}
          alt={name}
          // Upwind's letterforms are near-black, so they are inverted on the dark topbar.
          className={dark
            ? 'h-3 w-auto object-contain opacity-80 invert'
            : 'h-3 w-auto object-contain opacity-60'}
          loading="lazy"
          decoding="async"
          onError={() => setWordmarkFailed(true)}
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
