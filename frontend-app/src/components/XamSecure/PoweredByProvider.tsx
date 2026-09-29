/**
 * "powered by <vendor>" beside the XamSecure title in the topbar.
 *
 * Follows the active tab's product group: CNAPP is Upwind, DSPM is Matters.ai. A fixed Upwind mark
 * credited Upwind for Matters' findings on every DSPM tab.
 *
 * Set VITE_XAMSECURE_SHOW_PROVIDER=false to white-label XamSecure again. No link, deliberately:
 * linking out of a white-labelled product invites the customer to buy direct.
 */

import React, { useState } from 'react';
import { useTheme } from '../../contexts/ThemeContext';
import type { XamSecureGroup } from '../../constants/xamsecureTabs';

// Guarded: this tsconfig does not include vite/client, so import.meta.env is untyped.
const SHOW_PROVIDER = (typeof import.meta !== 'undefined'
  && (import.meta as any).env?.VITE_XAMSECURE_SHOW_PROVIDER) !== 'false';

interface Provider {
  name: string;
  /**
   * Official wordmark, or null to render the name as text. Wordmarks spell the name, so the text is
   * not repeated beside one.
   */
  wordmark: string | null;
}

const PROVIDERS: Record<XamSecureGroup, Provider> = {
  CNAPP: { name: 'Upwind', wordmark: '/icons/upwind-logo.svg' },
  // No Matters wordmark is vendored. Drop the official SVG into /icons and set the path here.
  DSPM: { name: 'Matters.ai', wordmark: null },
};

interface Props {
  /** Null while the active tab is unresolved: an attribution is a claim, so none is guessed. */
  group: XamSecureGroup | null;
  className?: string;
}

const PoweredByProvider: React.FC<Props> = ({ group, className }) => {
  // The failed source rather than a boolean, so a broken asset for one vendor does not latch the
  // other vendor into text mode when the user switches groups.
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const { theme } = useTheme();
  if (!SHOW_PROVIDER || !group) return null;

  const { name, wordmark } = PROVIDERS[group];
  const dark = theme === 'dark';

  return (
    <span className={`hidden md:inline-flex items-center gap-1.5 shrink-0 ${className ?? ''}`}>
      <span className={`text-[10px] leading-none ${
        dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.35)]'}`}>
        powered by
      </span>
      {wordmark && failedSrc !== wordmark ? (
        <img
          key={wordmark}
          src={wordmark}
          alt={name}
          // Upwind's letterforms are near-black, so they are inverted on the dark topbar.
          className={dark
            ? 'h-3 w-auto object-contain opacity-80 invert'
            : 'h-3 w-auto object-contain opacity-60'}
          loading="lazy"
          decoding="async"
          onError={() => setFailedSrc(wordmark)}
        />
      ) : (
        <span className={`text-[10px] font-semibold leading-none ${
          dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.5)]'}`}>
          {name}
        </span>
      )}
    </span>
  );
};

export default PoweredByProvider;
