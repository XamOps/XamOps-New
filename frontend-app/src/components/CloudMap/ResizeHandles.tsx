import React from 'react';

const HANDLES = [
  { id: 'nw', wx: -1, wy: -1, cursor: 'nw-resize' },
  { id: 'n',  wx:  0, wy: -1, cursor: 'n-resize'  },
  { id: 'ne', wx:  1, wy: -1, cursor: 'ne-resize'  },
  { id: 'w',  wx: -1, wy:  0, cursor: 'w-resize'   },
  { id: 'e',  wx:  1, wy:  0, cursor: 'e-resize'   },
  { id: 'sw', wx: -1, wy:  1, cursor: 'sw-resize'  },
  { id: 's',  wx:  0, wy:  1, cursor: 's-resize'   },
  { id: 'se', wx:  1, wy:  1, cursor: 'se-resize'  },
] as const;

interface Props {
  x1: number; y1: number; x2: number; y2: number;
  onHandleMouseDown: (wx: number, wy: number, e: React.MouseEvent) => void;
}

const ResizeHandles: React.FC<Props> = ({ x1, y1, x2, y2, onHandleMouseDown }) => {
  const cx = (x1 + x2) / 2;
  const cy = (y1 + y2) / 2;
  const w  = x2 - x1;
  const h  = y2 - y1;

  return (
    <svg
      className="absolute inset-0 pointer-events-none"
      style={{ width: '100%', height: '100%', zIndex: 90, overflow: 'visible' }}
    >
      {/* Dashed selection border */}
      <rect
        x={x1} y={y1} width={w} height={h}
        fill="none" stroke="#6366f1" strokeWidth={1.5} strokeDasharray="5 3" strokeLinecap="round"
      />
      {/* Eight resize handles */}
      {HANDLES.map(({ id, wx, wy, cursor }) => {
        const hx = cx + wx * (w / 2);
        const hy = cy + wy * (h / 2);
        return (
          <g key={id} style={{ pointerEvents: 'all' }}>
            {/* Invisible larger hit-area for easier grabbing */}
            <rect
              x={hx - 7} y={hy - 7} width={14} height={14}
              fill="transparent"
              style={{ cursor }}
              onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); onHandleMouseDown(wx, wy, e); }}
            />
            {/* Visible square */}
            <rect
              x={hx - 4} y={hy - 4} width={8} height={8}
              fill="white" stroke="#6366f1" strokeWidth={1.5} rx={1.5}
              style={{ pointerEvents: 'none' }}
            />
          </g>
        );
      })}
    </svg>
  );
};

export default ResizeHandles;
