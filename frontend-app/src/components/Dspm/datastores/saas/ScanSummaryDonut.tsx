import React from 'react';
import type { SaasScanSummaryPart } from '../../../../types/dspmDrawers';
import { abbreviate, formatBytes } from '../datastoreFormat';

const SIZE = 132;
const STROKE = 12;
const RADIUS = (SIZE - STROKE) / 2;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

const Arc: React.FC<{ length: number; offset: number; className?: string; stroke?: string }> = ({
  length, offset, className, stroke,
}) => (
  <circle
    cx={SIZE / 2}
    cy={SIZE / 2}
    r={RADIUS}
    fill="none"
    strokeWidth={STROKE}
    stroke={stroke}
    className={className}
    strokeDasharray={`${length} ${CIRCUMFERENCE - length}`}
    strokeDashoffset={-offset}
    transform={`rotate(-90 ${SIZE / 2} ${SIZE / 2})`}
  />
);

/**
 * The Scan Summary ring (FR-SAAS-7): supported files in sky blue, unsupported files in grey, starting at
 * the top; an empty grey ring when both counts are 0. The centre reads the discovered files (both counts)
 * and their size.
 */
const ScanSummaryDonut: React.FC<{ supported: SaasScanSummaryPart; unsupported: SaasScanSummaryPart }> = ({
  supported, unsupported,
}) => {
  const total = supported.count + unsupported.count;
  const supportedLength = total > 0 ? (supported.count / total) * CIRCUMFERENCE : 0;
  return (
    <div className="relative flex-shrink-0" style={{ width: SIZE, height: SIZE }}>
      <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true">
        {total === 0 ? (
          <Arc length={CIRCUMFERENCE} offset={0} stroke="rgba(0,0,0,0.08)" />
        ) : (
          <>
            <Arc length={CIRCUMFERENCE - supportedLength} offset={supportedLength} stroke="rgba(0,0,0,0.2)" />
            <Arc length={supportedLength} offset={0} className="stroke-sky-500" />
          </>
        )}
      </svg>
      <div data-donut-centre="" className="absolute inset-0 flex flex-col items-center justify-center text-center">
        <span className="text-lg font-bold tabular-nums text-[rgba(0,0,0,0.88)]">{abbreviate(total)}</span>
        <span className="text-[10px] text-[rgba(0,0,0,0.45)]">Discovered Files</span>
        <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.65)]">{formatBytes(supported.sizeBytes + unsupported.sizeBytes)}</span>
      </div>
    </div>
  );
};

export default ScanSummaryDonut;
