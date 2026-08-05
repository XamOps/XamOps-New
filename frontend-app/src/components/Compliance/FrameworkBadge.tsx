import React from 'react';
import { frameworkBadge } from './complianceTokens';

interface Props {
  framework: string;
  size?: number;
  shape?: 'circle' | 'rounded';
}

/**
 * The one framework mark renderer, used by the strip, the table, the picker and the
 * drawer so a framework looks identical everywhere on the page.
 *
 * Generated monograms rather than official logo files, on purpose: several standards
 * bodies restrict their certification marks to entities that hold the certification,
 * and others have no official mark to use at all.
 */
const FrameworkBadge: React.FC<Props> = ({ framework, size = 24, shape = 'circle' }) => {
  const badge = frameworkBadge(framework);
  const radius = shape === 'circle' ? '9999px' : `${Math.max(6, Math.round(size * 0.22))}px`;

  // ~40% of the badge for a 2-letter monogram, stepped down so 3- and 4-letter marks
  // stay inside the circle at the same optical weight.
  const len = badge.text.length;
  const small = size < 28;
  const fontRatio = len <= 2 ? 0.4 : len === 3 ? (small ? 0.34 : 0.32) : (small ? 0.275 : 0.255);

  return (
    <div
      role="img"
      aria-label={`${badge.name} compliance framework`}
      title={badge.name}
      className="flex items-center justify-center flex-shrink-0 overflow-hidden"
      style={{
        width: size,
        height: size,
        borderRadius: radius,
        backgroundImage: `linear-gradient(135deg, ${badge.bgLight} 0%, ${badge.bg} 72%)`,
        boxShadow:
          'inset 0 0 0 1px rgba(255,255,255,0.20), inset 0 1px 1px rgba(255,255,255,0.28), '
          + '0 1px 2px rgba(0,0,0,0.14)',
      }}
    >
      <span
        aria-hidden="true"
        className="select-none"
        style={{
          fontSize: Math.round(size * fontRatio * 100) / 100,
          lineHeight: 1,
          fontWeight: 650,
          letterSpacing: len >= 4 ? '-0.03em' : '0.01em',
          color: '#ffffff',
          transform: 'translateY(0.5px)',
          textShadow: '0 1px 1px rgba(0,0,0,0.18)',
        }}
      >
        {badge.text}
      </span>
    </div>
  );
};

export default FrameworkBadge;
