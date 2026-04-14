import React, { useMemo, useState } from 'react';
import type { HeatmapCell } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

interface TokenHeatmapProps {
  data: HeatmapCell[];
  loading: boolean;
}

const DAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const HOUR_LABELS = Array.from({ length: 24 }, (_, i) =>
  i === 0 ? '12a' : i < 12 ? `${i}a` : i === 12 ? '12p' : `${i - 12}p`
);

function formatNumber(value: number): string {
  if (value >= 1_000_000) return (value / 1_000_000).toFixed(1) + 'M';
  if (value >= 1_000) return (value / 1_000).toFixed(1) + 'k';
  return value.toLocaleString();
}

function getHeatColor(value: number, max: number): string {
  if (max === 0 || value === 0) return '#f8fafc'; // slate-50
  const ratio = value / max;
  if (ratio < 0.2) return '#e0e7ff';   // indigo-100
  if (ratio < 0.4) return '#c7d2fe';   // indigo-200
  if (ratio < 0.6) return '#a5b4fc';   // indigo-300
  if (ratio < 0.8) return '#818cf8';   // indigo-400
  return '#6366f1';                     // indigo-500
}

const TokenHeatmap: React.FC<TokenHeatmapProps> = ({ data, loading }) => {
  const [hoveredCell, setHoveredCell] = useState<{ dow: number; hr: number } | null>(null);

  const { grid, maxTokens } = useMemo(() => {
    // Build 7x24 grid initialized to 0
    const g: number[][] = Array.from({ length: 7 }, () => Array(24).fill(0));
    let max = 0;
    for (const cell of data) {
      g[cell.dayOfWeek][cell.hour] = cell.totalTokens;
      if (cell.totalTokens > max) max = cell.totalTokens;
    }
    return { grid: g, maxTokens: max };
  }, [data]);

  if (loading) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm p-12 text-center">
        <i className="fas fa-circle-notch fa-spin text-indigo-400 text-xl mb-2" />
        <p className="text-sm text-gray-400">Loading token heatmap...</p>
      </div>
    );
  }

  if (data.length === 0) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm p-8 text-center text-gray-400 text-sm">
        No token usage data for heatmap
      </div>
    );
  }

  const hoveredValue = hoveredCell ? grid[hoveredCell.dow][hoveredCell.hr] : null;

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-6 py-4 border-b border-gray-100 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-gradient-to-br from-amber-500 to-orange-600 rounded-lg flex items-center justify-center">
            <i className="fas fa-th text-white text-xs" />
          </div>
          <h3 className="text-sm font-bold text-gray-800">Token Usage Heatmap</h3>
          <InfoTooltip placement="right">Token consumption by day of week and hour. Darker cells = more tokens used.</InfoTooltip>
        </div>
        {hoveredCell !== null && hoveredValue !== null && (
          <span className="text-xs text-gray-500 bg-gray-100 px-3 py-1 rounded-full">
            {DAY_LABELS[hoveredCell.dow]} {HOUR_LABELS[hoveredCell.hr]}: <strong>{formatNumber(hoveredValue)} tokens</strong>
          </span>
        )}
      </div>

      {/* Heatmap Grid */}
      <div className="p-6 overflow-x-auto">
        <div className="min-w-[700px]">
          {/* Hour labels */}
          <div className="flex ml-12 mb-1">
            {HOUR_LABELS.map((label, i) => (
              <div key={i} className="flex-1 text-center text-[10px] text-gray-400">
                {i % 3 === 0 ? label : ''}
              </div>
            ))}
          </div>

          {/* Rows */}
          {DAY_LABELS.map((day, dow) => (
            <div key={dow} className="flex items-center gap-1 mb-1">
              <div className="w-10 text-right text-xs text-gray-500 font-medium pr-2">
                {day}
              </div>
              <div className="flex flex-1 gap-[2px]">
                {Array.from({ length: 24 }, (_, hr) => {
                  const tokens = grid[dow][hr];
                  const isHovered = hoveredCell?.dow === dow && hoveredCell?.hr === hr;
                  return (
                    <div
                      key={hr}
                      className="flex-1 rounded-sm transition-all cursor-pointer"
                      style={{
                        height: 28,
                        backgroundColor: getHeatColor(tokens, maxTokens),
                        outline: isHovered ? '2px solid #4f46e5' : 'none',
                        outlineOffset: -1,
                        transform: isHovered ? 'scale(1.1)' : 'scale(1)',
                        zIndex: isHovered ? 10 : 1,
                        position: 'relative',
                      }}
                      onMouseEnter={() => setHoveredCell({ dow, hr })}
                      onMouseLeave={() => setHoveredCell(null)}
                      title={`${day} ${HOUR_LABELS[hr]}: ${formatNumber(tokens)} tokens`}
                    />
                  );
                })}
              </div>
            </div>
          ))}

          {/* Legend */}
          <div className="flex items-center justify-end gap-2 mt-4 mr-1">
            <span className="text-[10px] text-gray-400">Less</span>
            {['#f8fafc', '#e0e7ff', '#c7d2fe', '#a5b4fc', '#818cf8', '#6366f1'].map((color, i) => (
              <div
                key={i}
                className="w-4 h-4 rounded-sm"
                style={{ backgroundColor: color }}
              />
            ))}
            <span className="text-[10px] text-gray-400">More</span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default TokenHeatmap;
