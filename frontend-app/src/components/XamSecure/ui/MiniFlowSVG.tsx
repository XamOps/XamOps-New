import React, { useId } from 'react';

/**
 * Slim signature "data-flow" banner for the top of the Overview page.
 *
 * Decorative only (aria-hidden): a row of soft-shadowed nodes connected by edges, with a couple
 * of dashed lines whose stroke-dashoffset animates, and a few packets travelling along paths via
 * <animateMotion>. Light styling. All motion is CSS keyframes / SMIL that is disabled under
 * prefers-reduced-motion (see the scoped <style> block), so it renders fully static then.
 */
interface Props {
  /** When true the SVG stretches to fill its parent (used as the hero background accent). */
  fill?: boolean;
  className?: string;
}

const MiniFlowSVG: React.FC<Props> = ({ fill = false, className }) => {
  const uid = useId().replace(/[:]/g, '');
  const dash = `flow-dash-${uid}`;
  const nodeShadow = `flow-node-${uid}`;

  // Node layout across a 720×92 viewBox, left (noise) → right (signal).
  const nodes: { x: number; y: number; r: number; c: string }[] = [
    { x: 40, y: 46, r: 6, c: '#2563eb' },
    { x: 170, y: 28, r: 5, c: '#7c3aed' },
    { x: 170, y: 64, r: 5, c: '#0891b2' },
    { x: 320, y: 46, r: 6, c: '#7c3aed' },
    { x: 470, y: 30, r: 5, c: '#0891b2' },
    { x: 470, y: 62, r: 5, c: '#059669' },
    { x: 620, y: 46, r: 7, c: '#059669' },
  ];

  const edges: { x1: number; y1: number; x2: number; y2: number }[] = [
    { x1: 40, y1: 46, x2: 170, y2: 28 },
    { x1: 40, y1: 46, x2: 170, y2: 64 },
    { x1: 170, y1: 28, x2: 320, y2: 46 },
    { x1: 170, y1: 64, x2: 320, y2: 46 },
    { x1: 320, y1: 46, x2: 470, y2: 30 },
    { x1: 320, y1: 46, x2: 470, y2: 62 },
    { x1: 470, y1: 30, x2: 620, y2: 46 },
    { x1: 470, y1: 62, x2: 620, y2: 46 },
  ];

  return (
    <div
      aria-hidden="true"
      className={`w-full ${className ?? ''}`}
      style={fill ? { height: '100%' } : { height: 92 }}
    >
      <svg
        viewBox="0 0 720 92"
        width="100%"
        height="100%"
        preserveAspectRatio={fill ? 'xMidYMid slice' : 'xMidYMid meet'}
        className="block"
        style={fill ? { height: '100%' } : { height: 92 }}
      >
        <style>{`
          @keyframes ${dash} { to { stroke-dashoffset: -24; } }
          .${dash}-line { stroke-dasharray: 6 6; animation: ${dash} 1.2s linear infinite; }
          @media (prefers-reduced-motion: reduce) {
            .${dash}-line { animation: none; }
            .${dash}-packet { display: none; }
          }
        `}</style>
        <defs>
          <filter id={nodeShadow} x="-60%" y="-60%" width="220%" height="220%">
            <feDropShadow dx="0" dy="1" stdDeviation="2" floodColor="#2563eb" floodOpacity={0.25} />
          </filter>
        </defs>

        {/* static base edges */}
        {edges.map((e, i) => (
          <line
            key={`e${i}`}
            x1={e.x1} y1={e.y1} x2={e.x2} y2={e.y2}
            stroke="rgba(15,23,42,0.10)" strokeWidth={1.25}
          />
        ))}

        {/* two animated dashed data-flow lines along the spine */}
        <line className={`${dash}-line`} x1={40} y1={46} x2={320} y2={46} stroke="rgba(37,99,235,0.45)" strokeWidth={1.5} />
        <line className={`${dash}-line`} x1={320} y1={46} x2={620} y2={46} stroke="rgba(8,145,178,0.45)" strokeWidth={1.5} />

        {/* travelling packets */}
        <circle className={`${dash}-packet`} r={2.6} fill="#2563eb">
          <animateMotion dur="2.4s" repeatCount="indefinite" path="M40,46 L320,46" />
        </circle>
        <circle className={`${dash}-packet`} r={2.6} fill="#7c3aed">
          <animateMotion dur="2.8s" begin="0.6s" repeatCount="indefinite" path="M320,46 L620,46" />
        </circle>
        <circle className={`${dash}-packet`} r={2.2} fill="#0891b2">
          <animateMotion dur="3.2s" begin="1.1s" repeatCount="indefinite" path="M40,46 L170,28 L320,46 L470,62 L620,46" />
        </circle>

        {/* nodes */}
        {nodes.map((n, i) => (
          <circle key={`n${i}`} cx={n.x} cy={n.y} r={n.r} fill={n.c} filter={`url(#${nodeShadow})`} />
        ))}
      </svg>
    </div>
  );
};

export default MiniFlowSVG;
