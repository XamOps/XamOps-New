/**
 * Data-provider attribution for XamSecure, rendered beside the page title in the topbar.
 *
 * XamSecure was specified as a white-label layer — the original design states the XamOps UI
 * "never mentions" the provider, with the name only surfacing at the infrastructure level
 * (CloudFormation stacks, Helm chart names, IAM role names). This component is the deliberate
 * reversal of that, kept to a single opt-in place rather than sprinkling the name through the
 * tabs: set VITE_XAMSECURE_SHOW_PROVIDER=false and XamSecure is white-label again.
 *
 * Sits in the topbar because the whole section is provider-backed, not any one tab — a mark at
 * the foot of the active tab implied it applied only to whatever was on screen.
 *
 * The asset is a WORDMARK (the logo spells the name), so the name is not repeated as text
 * alongside it — that would read "powered by upwind Upwind". The text version exists only as the
 * fallback for when the asset is absent, which is a real case: the logo is a third-party
 * trademark file supplied per deployment rather than vendored blindly.
 *
 * No link, deliberately. Linking out of a white-labelled product to the vendor invites the
 * customer to buy direct.
 */

import React, { useState } from 'react';
import { useTheme } from '../../contexts/ThemeContext';

/**
 * Whether to name the provider in customer-facing UI.
 *
 * Guarded exactly like the VITE_API_BASE_URL read in AIOpsPage: this tsconfig does not pull in
 * vite/client, so import.meta.env is untyped and a bare access does not compile.
 *
 * Defaults ON because attribution was explicitly requested. A flag rather than a code change
 * because the answer can differ per reseller agreement, or per customer deployment.
 */
const SHOW_PROVIDER = (typeof import.meta !== 'undefined'
  && (import.meta as any).env?.VITE_XAMSECURE_SHOW_PROVIDER) !== 'false';

/**
 * Official wordmark from the provider's brand kit.
 *
 * Roughly 3.3:1, dark letterforms with a gradient accent over the leading character. Height is
 * what gets constrained below, so a differently-proportioned revision of the file still sits on
 * the baseline correctly.
 */
const WORDMARK_SRC = '/icons/upwind-logo.svg';

interface Props {
  /** Extra classes for placement; the component brings no margins of its own. */
  className?: string;
}

const PoweredByProvider: React.FC<Props> = ({ className }) => {
  const [wordmarkFailed, setWordmarkFailed] = useState(false);
  const { theme } = useTheme();
  if (!SHOW_PROVIDER) return null;

  const dark = theme === 'dark';
  // The wordmark's letterforms are #141217, so on the dark topbar it would be very nearly
  // invisible. Inverting is safe for this asset: it is monochrome text plus a small gradient
  // accent, and inverting reads as a light-on-dark logo rather than a colour error.
  const wordmarkClass = dark
    ? 'h-3 w-auto object-contain opacity-80 invert'
    : 'h-3 w-auto object-contain opacity-60';

  return (
    <span className={`hidden md:inline-flex items-center gap-1.5 shrink-0 ${className ?? ''}`}>
      <span className={`text-[10px] leading-none ${
        dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.35)]'}`}>
        powered by
      </span>
      {wordmarkFailed ? (
        // Asset missing: the name still has to appear, or the phrase dangles.
        <span className={`text-[10px] font-semibold leading-none ${
          dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.5)]'}`}>
          Upwind
        </span>
      ) : (
        <img
          src={WORDMARK_SRC}
          alt="Upwind"
          className={wordmarkClass}
          loading="lazy"
          decoding="async"
          onError={() => setWordmarkFailed(true)}
        />
      )}
    </span>
  );
};

export default PoweredByProvider;
