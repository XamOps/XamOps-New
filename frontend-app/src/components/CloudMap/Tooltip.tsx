import React, { useState, useRef, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';

interface Props {
  text: string;
  children: React.ReactElement;
  position?: 'top' | 'bottom';
  delay?: number;
}

const Tooltip: React.FC<Props> = ({ text, children, position = 'bottom', delay = 150 }) => {
  const [visible, setVisible] = useState(false);
  const [coords, setCoords] = useState({ x: 0, y: 0 });
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const wrapRef = useRef<HTMLDivElement>(null);

  const show = useCallback(() => {
    timerRef.current = setTimeout(() => {
      if (wrapRef.current) {
        const r = wrapRef.current.getBoundingClientRect();
        setCoords({
          x: r.left + r.width / 2,
          y: position === 'bottom' ? r.bottom + 6 : r.top - 6,
        });
      }
      setVisible(true);
    }, delay);
  }, [delay, position]);

  const hide = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    setVisible(false);
  }, []);

  // Clean up on unmount
  useEffect(() => () => { if (timerRef.current) clearTimeout(timerRef.current); }, []);

  return (
    <div ref={wrapRef} className="relative flex items-center justify-center" onMouseEnter={show} onMouseLeave={hide}>
      {children}
      {visible && createPortal(
        <div
          role="tooltip"
          style={{
            position: 'fixed',
            left: coords.x,
            top: position === 'bottom' ? coords.y : undefined,
            bottom: position === 'top' ? window.innerHeight - coords.y : undefined,
            transform: 'translateX(-50%)',
            zIndex: 9999,
          }}
          className="bg-gray-900 text-white text-[10px] font-medium px-2 py-1 rounded-md whitespace-nowrap pointer-events-none shadow-lg animate-in fade-in duration-100"
        >
          {text}
          <span
            className={`absolute left-1/2 -translate-x-1/2 w-0 h-0 border-4 border-transparent
              ${position === 'bottom' ? 'bottom-full border-b-gray-900' : 'top-full border-t-gray-900'}`}
          />
        </div>,
        document.body,
      )}
    </div>
  );
};

export default Tooltip;
