import React, { ReactNode, useState, useEffect, useRef } from 'react';

interface MetricCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  icon: string;
  borderColor?: string;
  isLoading?: boolean;
  children?: ReactNode;
  onClick?: () => void;
  showViewButton?: boolean;
  onViewClick?: () => void;
}

const parseValue = (v: string | number) => {
  const str = String(v);
  const m = str.match(/^([^0-9]*)([0-9][0-9,]*\.?[0-9]*)([^0-9]*)$/);
  if (!m) return { prefix: '', num: 0, suffix: str };
  return { prefix: m[1], num: parseFloat(m[2].replace(/,/g, '')), suffix: m[3] };
};

export const MetricCard: React.FC<MetricCardProps> = ({
  title,
  value,
  subtitle,
  icon,
  isLoading = false,
  children,
  onClick,
  showViewButton = false,
  onViewClick,
}) => {
  const { prefix, num, suffix } = parseValue(value);

  const animRef = useRef<number | undefined>(undefined);
  const prevNumRef = useRef(0);
  const [animCount, setAnimCount] = useState(num);

  useEffect(() => {
    if (num === prevNumRef.current) return;
    const from = prevNumRef.current;
    prevNumRef.current = num;

    if (animRef.current) cancelAnimationFrame(animRef.current);
    if (num === 0) { setAnimCount(0); return; }

    const duration = 1050;
    const startTime = performance.now();

    const step = (now: number) => {
      const t = Math.min((now - startTime) / duration, 1);
      const eased = 1 - Math.pow(1 - t, 3);
      setAnimCount(from + eased * (num - from));
      if (t < 1) {
        animRef.current = requestAnimationFrame(step);
      } else {
        setAnimCount(num);
      }
    };
    animRef.current = requestAnimationFrame(step);
    return () => { if (animRef.current) cancelAnimationFrame(animRef.current); };
  }, [num]);

  const display = `${prefix}${Math.round(animCount).toLocaleString('en-US')}${suffix}`;
  const mainPart = display.includes('.') ? display.slice(0, display.lastIndexOf('.')) : display;
  const decPart  = display.includes('.') ? display.slice(display.lastIndexOf('.')) : '';

  return (
    <div
      className={`bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden group card-hover-lift ${onClick ? 'cursor-pointer' : ''}`}
      onClick={onClick}
    >
      {/* Icons — absolutely positioned so they don't add to flow height */}
      <div className="absolute top-4 right-4 flex items-center gap-1.5">
        {showViewButton && onViewClick && (
          <button
            onClick={(e) => { e.stopPropagation(); onViewClick(); }}
            className="w-6 h-6 rounded-md bg-[rgba(0,0,0,0.03)] hover:bg-[rgba(0,0,0,0.07)] text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.6)] transition-all flex items-center justify-center"
            title="View Breakdown"
          >
            <i className="fas fa-eye text-[10px]"></i>
          </button>
        )}
        <div className="w-6 h-6 bg-[rgba(0,0,0,0.04)] rounded-md flex items-center justify-center">
          <i className={`${icon} text-[10px]`}></i>
        </div>
      </div>

      {isLoading ? (
        <div className="animate-pulse space-y-3 pt-0.5">
          <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] shimmer rounded"></div>
          <div className="h-8 w-32 bg-[rgba(0,0,0,0.06)] shimmer rounded"></div>
          <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] shimmer rounded"></div>
        </div>
      ) : (
        <div className="fade-in pr-16">
          <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">
            {title}
          </p>
          <p className="leading-8 mb-1 flex items-baseline gap-0.5">
            <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px', color: 'rgb(10, 10, 10)', fontVariantNumeric: 'tabular-nums' }}>
              {mainPart}
            </span>
            {decPart && (
              <span style={{ fontFamily: "'Inter', sans-serif", fontSize: '14px', fontWeight: 400, color: 'rgb(10, 10, 10)' }}>
                {decPart}
              </span>
            )}
          </p>
          {subtitle && <p className="text-[11px] text-[rgba(0,0,0,0.35)] font-medium">{subtitle}</p>}
          {children}
        </div>
      )}
    </div>
  );
};
