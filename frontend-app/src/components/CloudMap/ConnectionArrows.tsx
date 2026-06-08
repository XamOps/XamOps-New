import React from 'react';

interface Props {
  x1: number; y1: number; x2: number; y2: number;
  onArrowMouseDown: (e: React.MouseEvent) => void;
  onArrowMouseEnter?: () => void;
}

const GAP  = 8;
const SIZE = 18;
const HALF = SIZE / 2;

const ConnectionArrows: React.FC<Props> = ({ x1, y1, x2, y2, onArrowMouseDown, onArrowMouseEnter }) => {
  const mx = (x1 + x2) / 2;
  const my = (y1 + y2) / 2;

  const arrows = [
    { key: 'n', pos: { left: mx - HALF, top: y1 - GAP - SIZE }, path: 'M9,13 L9,5 M6,8 L9,5 L12,8'  },
    { key: 's', pos: { left: mx - HALF, top: y2 + GAP         }, path: 'M9,5 L9,13 M6,10 L9,13 L12,10' },
    { key: 'w', pos: { left: x1 - GAP - SIZE, top: my - HALF  }, path: 'M13,9 L5,9 M8,6 L5,9 L8,12'  },
    { key: 'e', pos: { left: x2 + GAP,        top: my - HALF  }, path: 'M5,9 L13,9 M10,6 L13,9 L10,12' },
  ];

  return (
    <>
      {arrows.map(({ key, pos, path }) => (
        <div
          key={key}
          className="absolute flex items-center justify-center rounded
            bg-indigo-500 hover:bg-indigo-600 border border-indigo-400
            cursor-crosshair shadow-md transition-colors select-none"
          style={{ ...pos, width: SIZE, height: SIZE, pointerEvents: 'all', zIndex: 95 }}
          onMouseEnter={onArrowMouseEnter}
          onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); onArrowMouseDown(e); }}
          title="Drag to connect"
        >
          <svg width={SIZE} height={SIZE} viewBox="0 0 18 18" fill="none">
            <path d={path} stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </div>
      ))}
    </>
  );
};

export default ConnectionArrows;
