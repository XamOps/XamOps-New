import React, { useState, useRef, useCallback } from 'react';

interface Props {
  text: string;
  children: React.ReactElement;
  position?: 'top' | 'bottom';
  delay?: number;
}

const Tooltip: React.FC<Props> = ({ text, children, position = 'bottom', delay = 400 }) => {
  const [visible, setVisible] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const show = useCallback(() => {
    timerRef.current = setTimeout(() => setVisible(true), delay);
  }, [delay]);

  const hide = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    setVisible(false);
  }, []);

  return (
    <div className="relative flex items-center justify-center" onMouseEnter={show} onMouseLeave={hide}>
      {children}
      {visible && (
        <div
          className={`absolute ${position === 'bottom' ? 'top-full mt-2' : 'bottom-full mb-2'}
            left-1/2 -translate-x-1/2 bg-gray-900 text-white text-[10px] font-medium
            px-2 py-1 rounded-md whitespace-nowrap pointer-events-none z-[600] shadow-lg
            animate-in fade-in duration-100`}
          role="tooltip"
        >
          {text}
          <span
            className={`absolute left-1/2 -translate-x-1/2 w-0 h-0 border-4 border-transparent
              ${position === 'bottom'
                ? 'bottom-full border-b-gray-900'
                : 'top-full border-t-gray-900'}`}
          />
        </div>
      )}
    </div>
  );
};

export default Tooltip;
