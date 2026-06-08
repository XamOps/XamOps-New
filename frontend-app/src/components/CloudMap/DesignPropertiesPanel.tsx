import React, { useState, useEffect } from 'react';

const PRESET_COLORS = [
  '#111827', '#374151', '#6b7280', '#9ca3af', '#d1d5db', '#f3f4f6',
  '#1e40af', '#0ea5e9', '#10b981', '#22c55e', '#f59e0b', '#ef4444',
  '#1f2937', '#ffffff', '#f8fafc', '#000000',
];

const NODE_SHAPES = [
  { id: 'rectangle',              label: 'Rect' },
  { id: 'roundrectangle',         label: 'Rounded' },
  { id: 'bottom-round-rectangle', label: 'Tab' },
  { id: 'cut-rectangle',          label: 'Process' },
  { id: 'ellipse',                label: 'Ellipse' },
  { id: 'triangle',               label: 'Triangle' },
  { id: 'round-triangle',         label: 'Rnd Tri' },
  { id: 'straight-triangle',      label: 'Rt Tri' },
  { id: 'diamond',                label: 'Diamond' },
  { id: 'round-diamond',          label: 'Rnd Dia' },
  { id: 'pentagon',               label: 'Penta' },
  { id: 'round-pentagon',         label: 'Rnd Pen' },
  { id: 'hexagon',                label: 'Hex' },
  { id: 'round-hexagon',          label: 'Rnd Hex' },
  { id: 'concave-hexagon',        label: 'Concave' },
  { id: 'heptagon',               label: 'Hepta' },
  { id: 'round-heptagon',         label: 'Rnd Hep' },
  { id: 'octagon',                label: 'Octa' },
  { id: 'round-octagon',          label: 'Rnd Oct' },
  { id: 'star',                   label: 'Star' },
  { id: 'vee',                    label: 'Vee' },
  { id: 'barrel',                 label: 'Cylinder' },
  { id: 'tag',                    label: 'Tag' },
  { id: 'round-tag',              label: 'Rnd Tag' },
];

const EDGE_STYLES = [
  { id: 'bezier', label: 'Curved' },
  { id: 'taxi', label: 'Elbow' },
  { id: 'straight', label: 'Straight' },
  { id: 'unbundled-bezier', label: 'Smooth' },
  { id: 'haystack', label: 'Bundled' },
];

const ARROW_TYPES = [
  { id: 'triangle', label: 'Arrow' },
  { id: 'open', label: 'Open' },
  { id: 'circle', label: 'Circle' },
  { id: 'none', label: 'None' },
];

const DASH_OPTS = [
  { id: 'solid',  label: '——'  },
  { id: 'dashed', label: '- -' },
  { id: 'dotted', label: '····' },
] as const;

const GRADIENT_DIRS = [
  { id: 'to-bottom',       label: '↓' },
  { id: 'to-right',        label: '→' },
  { id: 'to-bottom-right', label: '↘' },
] as const;

export interface ElementProperties {
  id: string;
  elementType: 'node' | 'edge';
  label: string;
  fillColor: string;
  fillColor2: string;
  gradientDir: string;
  nodeShadow: boolean;
  shadowBlur: number;
  shadowOffsetX: number;
  shadowOffsetY: number;
  shadowColor: string;
  borderColor: string;
  borderWidth: number;
  borderDash: string;
  nodeShape: string;
  edgeStyle: string;
  arrowType: string;
  fontSize: number;
  fontBold: boolean;
  fontItalic: boolean;
  fontFamily: string;
  fontColor: string;
  textValign: string;
  textHalign: string;
  width: number;
  height: number;
  opacity: number;
  edgeWidth: number;
  edgeDash: string;
  locked: boolean;
  tooltip: string;
  pulse: boolean;
  flowAnimation: boolean;
  flowSpeed: number;
  sourceLabel: string;
  targetLabel: string;
  midArrowShape: string;
  sourcePort: string;
  targetPort: string;
  taxiDir: string;
  rotation: number;
  flipH: boolean;
  flipV: boolean;
  hyperlink: string;
  metadata: Array<{ key: string; value: string }>;
  comments: string;
}

interface Props {
  selected: ElementProperties | null;
  onLabelChange: (id: string, label: string) => void;
  onFillColorChange: (id: string, color: string) => void;
  onFillColor2Change: (id: string, color: string) => void;
  onGradientDirChange: (id: string, dir: string) => void;
  onNodeShadowChange: (id: string, v: boolean) => void;
  onShadowDetailChange: (id: string, blur: number, ox: number, oy: number, color: string) => void;
  onBorderColorChange: (id: string, color: string) => void;
  onBorderWidthChange: (id: string, v: number) => void;
  onBorderDashChange: (id: string, v: string) => void;
  onShapeChange: (id: string, shape: string) => void;
  onEdgeStyleChange: (id: string, style: string) => void;
  onArrowTypeChange: (id: string, arrowType: string) => void;
  onFontSizeChange: (id: string, size: number) => void;
  onFontBoldChange: (id: string, v: boolean) => void;
  onFontItalicChange: (id: string, v: boolean) => void;
  onFontColorChange: (id: string, v: string) => void;
  onFontFamilyChange: (id: string, v: string) => void;
  onTextValignChange: (id: string, v: string) => void;
  onTextHalignChange: (id: string, v: string) => void;
  onSizeChange: (id: string, width: number, height: number) => void;
  onOpacityChange: (id: string, v: number) => void;
  onEdgeWidthChange: (id: string, v: number) => void;
  onEdgeDashChange: (id: string, v: string) => void;
  onLockToggle: (id: string) => void;
  onTooltipChange: (id: string, v: string) => void;
  onPulseChange: (id: string, v: boolean) => void;
  onFlowAnimChange: (id: string, v: boolean) => void;
  onFlowSpeedChange: (id: string, v: number) => void;
  onDelete: () => void;
  onDuplicate: () => void;
  onSourceLabelChange: (id: string, v: string) => void;
  onTargetLabelChange: (id: string, v: string) => void;
  onMidArrowShapeChange: (id: string, v: string) => void;
  onSourcePortChange: (id: string, v: string) => void;
  onTargetPortChange: (id: string, v: string) => void;
  onEdgeReverseDirection: (id: string) => void;
  onTaxiDirChange: (id: string, v: string) => void;
  onRotationChange: (id: string, v: number) => void;
  onFlipChange: (id: string, flipH: boolean, flipV: boolean) => void;
  onHyperlinkChange: (id: string, v: string) => void;
  onAddTableRow: (parentId: string) => void;
  onRawStyleApply: (id: string, style: Record<string, unknown>) => void;
  onMetadataChange: (id: string, metadata: Array<{ key: string; value: string }>) => void;
  onCommentsChange: (id: string, v: string) => void;
}

const ColorSwatch: React.FC<{ color: string; active: boolean; onClick: () => void }> = ({ color, active, onClick }) => (
  <button
    onClick={onClick}
    title={color}
    className={`w-5 h-5 rounded border-2 transition-transform hover:scale-110 flex-shrink-0 ${active ? 'border-gray-800 scale-110' : 'border-transparent'}`}
    style={{
      backgroundColor: color,
      boxShadow: (color === '#ffffff' || color === '#f8fafc') ? 'inset 0 0 0 1px #d1d5db' : undefined,
    }}
  />
);

const FONT_FAMILIES = [
  { id: 'sans-serif',           label: 'Sans Serif' },
  { id: 'serif',                label: 'Serif' },
  { id: 'monospace',            label: 'Mono' },
  { id: 'Arial, sans-serif',    label: 'Arial' },
  { id: 'Georgia, serif',       label: 'Georgia' },
  { id: '"Courier New", monospace', label: 'Courier' },
] as const;

const TEXT_VALIGN_OPTS = [
  { id: 'top',    label: 'Above' },
  { id: 'center', label: 'Inside' },
  { id: 'bottom', label: 'Below' },
] as const;

const TEXT_HALIGN_OPTS = [
  { id: 'left',   icon: 'fa-align-left'   },
  { id: 'center', icon: 'fa-align-center' },
  { id: 'right',  icon: 'fa-align-right'  },
] as const;

const DesignPropertiesPanel: React.FC<Props> = ({
  selected,
  onLabelChange,
  onFillColorChange,
  onFillColor2Change,
  onGradientDirChange,
  onNodeShadowChange,
  onShadowDetailChange,
  onBorderColorChange,
  onBorderWidthChange,
  onBorderDashChange,
  onShapeChange,
  onEdgeStyleChange,
  onArrowTypeChange,
  onFontSizeChange,
  onFontBoldChange,
  onFontItalicChange,
  onFontColorChange,
  onFontFamilyChange,
  onTextValignChange,
  onTextHalignChange,
  onSizeChange,
  onOpacityChange,
  onEdgeWidthChange,
  onEdgeDashChange,
  onLockToggle,
  onTooltipChange,
  onPulseChange,
  onFlowAnimChange,
  onFlowSpeedChange,
  onDelete,
  onDuplicate,
  onSourceLabelChange,
  onTargetLabelChange,
  onMidArrowShapeChange,
  onSourcePortChange,
  onTargetPortChange,
  onEdgeReverseDirection,
  onTaxiDirChange,
  onRotationChange,
  onFlipChange,
  onHyperlinkChange,
  onAddTableRow,
  onRawStyleApply,
  onMetadataChange,
  onCommentsChange,
}) => {
  const [localLabel,       setLocalLabel]       = useState('');
  const [localWidth,       setLocalWidth]        = useState('');
  const [localHeight,      setLocalHeight]       = useState('');
  const [localTooltip,     setLocalTooltip]      = useState('');
  const [localSourceLabel, setLocalSourceLabel]  = useState('');
  const [localTargetLabel, setLocalTargetLabel]  = useState('');
  const [localHyperlink,   setLocalHyperlink]    = useState('');
  const [aspectLock,       setAspectLock]        = useState(false);
  const [showRawStyle,     setShowRawStyle]       = useState(false);
  const [rawStyle,         setRawStyle]           = useState('');

  useEffect(() => {
    setLocalLabel(selected?.label ?? '');
    setLocalWidth(String(selected?.width  ?? ''));
    setLocalHeight(String(selected?.height ?? ''));
    setLocalTooltip(selected?.tooltip ?? '');
    setLocalSourceLabel(selected?.sourceLabel ?? '');
    setLocalTargetLabel(selected?.targetLabel ?? '');
    setLocalHyperlink(selected?.hyperlink ?? '');
    setAspectLock(false);
  }, [selected?.id, selected?.label, selected?.width, selected?.height, selected?.tooltip, selected?.sourceLabel, selected?.targetLabel, selected?.hyperlink]);

  // Compute raw style JSON when selection changes
  useEffect(() => {
    if (!selected) return;
    const style: Record<string, unknown> = {
      fillColor: selected.fillColor,
      fillColor2: selected.fillColor2,
      borderColor: selected.borderColor,
      borderWidth: selected.borderWidth,
      borderDash: selected.borderDash,
      opacity: selected.opacity,
      fontSize: selected.fontSize,
      fontBold: selected.fontBold,
      fontItalic: selected.fontItalic,
      fontColor: selected.fontColor,
    };
    if (selected.elementType === 'node') {
      Object.assign(style, { nodeShape: selected.nodeShape, nodeShadow: selected.nodeShadow, rotation: selected.rotation });
    } else {
      Object.assign(style, { edgeStyle: selected.edgeStyle, edgeWidth: selected.edgeWidth, edgeDash: selected.edgeDash, arrowType: selected.arrowType });
    }
    setRawStyle(JSON.stringify(style, null, 2));
  }, [selected?.id]);

  const opacityPct = Math.round((selected?.opacity ?? 1) * 100);

  return (
    <div className="w-56 bg-white border-l border-gray-200 flex flex-col h-full shrink-0 z-10 design-props-panel">
      {/* Header */}
      <div className="px-3 py-2.5 border-b border-gray-100 flex items-center justify-between shrink-0">
        <span className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">
          {selected
            ? selected.elementType === 'node'
              ? 'Shape Properties'
              : 'Connection Properties'
            : 'Properties'}
        </span>
        {selected && (
          <div className="flex gap-1">
            {/* Lock toggle */}
            <button
              onClick={() => onLockToggle(selected.id)}
              title={selected.locked ? 'Unlock (Ctrl+L)' : 'Lock (Ctrl+L)'}
              className={`w-6 h-6 flex items-center justify-center rounded transition-colors ${
                selected.locked
                  ? 'bg-amber-50 text-amber-500 hover:bg-amber-100'
                  : 'hover:bg-gray-100 text-gray-400 hover:text-gray-600'
              }`}
            >
              <i className={`fas ${selected.locked ? 'fa-lock' : 'fa-unlock'} text-[10px]`} />
            </button>
            {selected.elementType === 'node' && (
              <button
                onClick={onDuplicate}
                title="Duplicate (Ctrl+D)"
                className="w-6 h-6 flex items-center justify-center rounded hover:bg-gray-100 text-gray-400 hover:text-gray-600 transition-colors"
              >
                <i className="fas fa-copy text-[10px]" />
              </button>
            )}
            <button
              onClick={onDelete}
              title="Delete (Del)"
              className="w-6 h-6 flex items-center justify-center rounded hover:bg-red-50 text-gray-400 hover:text-red-500 transition-colors"
            >
              <i className="fas fa-trash text-[10px]" />
            </button>
          </div>
        )}
      </div>

      {!selected ? (
        /* Empty state */
        <div className="flex-1 overflow-y-auto p-4">
          <div className="flex flex-col items-center text-center mb-4">
            <div className="w-9 h-9 bg-gray-100 rounded-xl flex items-center justify-center mb-2.5">
              <i className="fas fa-mouse-pointer text-gray-400 text-sm" />
            </div>
            <p className="text-[11px] text-gray-400 font-medium leading-relaxed">
              Click a shape or connection to edit
            </p>
            <p className="text-[10px] text-gray-500 mt-1.5">
              Press <kbd className="px-1 py-0.5 bg-gray-100 border border-gray-300 rounded font-mono text-[9px]">?</kbd> for full help
            </p>
          </div>

          <div className="w-full space-y-1">
            <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-2">Essential Shortcuts</p>
            {[
              ['Del',        'Delete selected'],
              ['Ctrl+Z',     'Undo'],
              ['Ctrl+Y',     'Redo'],
              ['Ctrl+C/V',   'Copy / Paste'],
              ['Ctrl+D',     'Duplicate'],
              ['Ctrl+A',     'Select all'],
              ['Ctrl+G',     'Group nodes'],
              ['Ctrl+L',     'Lock / Unlock'],
              ['Ctrl+F',     'Find by label'],
              ['↑↓←→',       'Nudge selected 5px'],
              ['Shift+↑↓←→', 'Nudge 20px'],
              ['Space+drag', 'Pan canvas'],
              ['Dbl-click',  'Edit label'],
              ['Right-click','Context menu'],
            ].map(([key, desc]) => (
              <div key={key} className="flex items-center justify-between gap-1 py-0.5">
                <span className="text-[10px] text-gray-500 leading-tight">{desc}</span>
                <kbd className="px-1.5 py-0.5 bg-gray-100 border border-gray-200 rounded text-gray-600 font-mono text-[9px] leading-none shrink-0">
                  {key}
                </kbd>
              </div>
            ))}
          </div>

          <div className="mt-4 w-full space-y-1">
            <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-2">Quick Tips</p>
            {[
              ['Hover node',    'Connection arrows appear'],
              ['Drag palette',  'Drop shape at position'],
              ['Drag ruler',    'Create guide line'],
              ['Select 2+',     'Align toolbar appears'],
              ['Format Painter','Copy style between shapes'],
              ['Bend edge',     'Drag waypoint on selected edge'],
            ].map(([action, tip]) => (
              <div key={action} className="flex items-start gap-1.5 py-0.5">
                <span className="text-[9px] font-semibold text-gray-500 shrink-0 mt-0.5 w-20 leading-tight">{action}</span>
                <span className="text-[10px] text-gray-500 leading-tight">{tip}</span>
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto p-3 space-y-4 design-props-scroll">

          {/* Label — nodes only; edges use the Labels section inside edge properties */}
          {selected.elementType === 'node' && (
            <section>
              <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Label</label>
              <input
                type="text"
                value={localLabel}
                onChange={(e) => setLocalLabel(e.target.value)}
                onBlur={() => onLabelChange(selected.id, localLabel)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') onLabelChange(selected.id, localLabel);
                  e.stopPropagation();
                }}
                className="w-full px-2.5 py-1.5 text-xs border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-gray-400 focus:border-transparent"
                placeholder="Enter label..."
              />
            </section>
          )}

          {/* Tooltip */}
          <section>
            <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Tooltip (hover)</label>
            <textarea
              value={localTooltip}
              onChange={(e) => setLocalTooltip(e.target.value)}
              onBlur={() => onTooltipChange(selected.id, localTooltip)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onTooltipChange(selected.id, localTooltip); }
                e.stopPropagation();
              }}
              rows={2}
              className="w-full px-2.5 py-1.5 text-xs border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-gray-400 focus:border-transparent resize-none"
              placeholder="Tooltip text (supports HTML: <b>bold</b>)"
            />
          </section>

          {/* Hyperlink */}
          <section>
            <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Hyperlink</label>
            <div className="flex items-center gap-1.5">
              <i className="fas fa-link text-[10px] text-gray-400 shrink-0" />
              <input type="url" value={localHyperlink} placeholder="https://example.com"
                onChange={(e) => setLocalHyperlink(e.target.value)}
                onBlur={() => onHyperlinkChange(selected.id, localHyperlink)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') onHyperlinkChange(selected.id, localHyperlink);
                  e.stopPropagation();
                }}
                className="flex-1 min-w-0 px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400" />
            </div>
            {localHyperlink && (
              <p className="text-[9px] text-gray-400 mt-1">Ctrl+click node to open link</p>
            )}
          </section>

          {/* ── NODE PROPERTIES ── */}
          {selected.elementType === 'node' && (
            <>
              {/* Shape */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Shape</label>
                <div className="grid grid-cols-4 gap-1">
                  {NODE_SHAPES.map((shape) => (
                    <button
                      key={shape.id}
                      onClick={() => onShapeChange(selected.id, shape.id)}
                      title={shape.label}
                      className={`py-1 px-0.5 rounded-md border text-[9px] font-semibold transition-all leading-tight ${
                        selected.nodeShape === shape.id
                          ? 'border-gray-700 bg-gray-100 text-gray-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {shape.label}
                    </button>
                  ))}
                </div>
              </section>

              {/* Fill Color */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Fill Color</label>
                <div className="grid grid-cols-8 gap-1 mb-2">
                  {PRESET_COLORS.map((c) => (
                    <ColorSwatch
                      key={c}
                      color={c}
                      active={selected.fillColor === c}
                      onClick={() => onFillColorChange(selected.id, c)}
                    />
                  ))}
                </div>
                {/* Fill color row */}
                <div className="flex items-center gap-1.5 mb-1.5">
                  <input
                    type="color"
                    value={selected.fillColor?.startsWith('#') ? selected.fillColor : '#6366f1'}
                    onChange={(e) => onFillColorChange(selected.id, e.target.value)}
                    className="w-7 h-6 rounded border border-gray-200 cursor-pointer p-0 shrink-0"
                  />
                  <input
                    type="text"
                    value={selected.fillColor || ''}
                    onChange={(e) => onFillColorChange(selected.id, e.target.value)}
                    className="flex-1 min-w-0 px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 font-mono"
                    placeholder="#6366f1"
                  />
                </div>
                {/* Gradient second color */}
                <div className="flex items-center gap-1.5 mb-1">
                  <div className="text-[9px] text-gray-400 font-semibold w-7 text-center shrink-0">G2</div>
                  <input
                    type="color"
                    value={selected.fillColor2?.startsWith('#') ? selected.fillColor2 : '#a855f7'}
                    onChange={(e) => onFillColor2Change(selected.id, e.target.value)}
                    title="Gradient end color"
                    className="w-7 h-6 rounded border border-gray-200 cursor-pointer p-0 shrink-0"
                  />
                  <input
                    type="text"
                    value={selected.fillColor2 || ''}
                    onChange={(e) => onFillColor2Change(selected.id, e.target.value)}
                    placeholder="none (solid)"
                    className="flex-1 min-w-0 px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 font-mono"
                  />
                  {selected.fillColor2 && (
                    <button
                      onClick={() => onFillColor2Change(selected.id, '')}
                      title="Remove gradient"
                      className="text-gray-400 hover:text-red-500 text-[10px] shrink-0"
                    >✕</button>
                  )}
                </div>
                {/* Gradient direction */}
                {selected.fillColor2 && (
                  <div className="flex gap-1">
                    {GRADIENT_DIRS.map(({ id, label }) => (
                      <button
                        key={id}
                        onClick={() => onGradientDirChange(selected.id, id)}
                        title={id}
                        className={`flex-1 py-1 rounded-md border text-[12px] font-semibold transition-all ${
                          (selected.gradientDir || 'to-bottom') === id
                            ? 'border-gray-700 bg-gray-100 text-gray-700'
                            : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                        }`}
                      >
                        {label}
                      </button>
                    ))}
                  </div>
                )}
              </section>

              {/* Border (color + width + dash) */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Border</label>
                <div className="flex items-center gap-1.5 mb-1.5">
                  <input
                    type="color"
                    value={selected.borderColor?.startsWith('#') ? selected.borderColor : '#e5e7eb'}
                    onChange={(e) => onBorderColorChange(selected.id, e.target.value)}
                    className="w-7 h-6 rounded border border-gray-200 cursor-pointer p-0 shrink-0"
                  />
                  <input
                    type="text"
                    value={selected.borderColor || ''}
                    onChange={(e) => onBorderColorChange(selected.id, e.target.value)}
                    className="flex-1 min-w-0 px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 font-mono"
                    placeholder="#e5e7eb"
                  />
                  <input
                    type="number"
                    min="0" max="8"
                    value={selected.borderWidth ?? 2}
                    onChange={(e) => onBorderWidthChange(selected.id, parseInt(e.target.value) || 0)}
                    onKeyDown={(e) => e.stopPropagation()}
                    title="Border width (px)"
                    className="w-10 px-1.5 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 font-mono text-center"
                  />
                </div>
                <div className="flex gap-1">
                  {DASH_OPTS.map(({ id, label }) => (
                    <button
                      key={id}
                      onClick={() => onBorderDashChange(selected.id, id)}
                      className={`flex-1 py-1 rounded-md border text-[9px] font-semibold transition-all ${
                        (selected.borderDash || 'solid') === id
                          ? 'border-gray-700 bg-gray-100 text-gray-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </section>

              {/* Font */}
              <section>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Font</label>
                  <span className="text-[10px] font-semibold text-gray-600">{selected.fontSize || 11}px</span>
                </div>
                {/* Font family */}
                <select
                  value={selected.fontFamily || 'sans-serif'}
                  onChange={(e) => onFontFamilyChange(selected.id, e.target.value)}
                  className="w-full px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 mb-1.5"
                  style={{ fontFamily: selected.fontFamily || 'sans-serif' }}
                >
                  {FONT_FAMILIES.map(f => (
                    <option key={f.id} value={f.id} style={{ fontFamily: f.id }}>{f.label}</option>
                  ))}
                </select>
                {/* Size slider */}
                <input
                  type="range" min="8" max="36"
                  value={selected.fontSize || 11}
                  onChange={(e) => onFontSizeChange(selected.id, parseInt(e.target.value))}
                  className="w-full h-1.5 accent-gray-700 cursor-pointer mb-1.5"
                />
                {/* B / I / color */}
                <div className="flex gap-1 mb-1.5">
                  <button
                    onClick={() => onFontBoldChange(selected.id, !selected.fontBold)}
                    title="Bold"
                    className={`flex-1 py-1 rounded-md border text-[11px] font-black transition-all ${
                      selected.fontBold
                        ? 'border-gray-700 bg-gray-100 text-gray-700'
                        : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                    }`}
                  >B</button>
                  <button
                    onClick={() => onFontItalicChange(selected.id, !selected.fontItalic)}
                    title="Italic"
                    className={`flex-1 py-1 rounded-md border text-[11px] italic font-semibold transition-all ${
                      selected.fontItalic
                        ? 'border-gray-700 bg-gray-100 text-gray-700'
                        : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                    }`}
                  >I</button>
                  {/* H-align */}
                  {TEXT_HALIGN_OPTS.map(({ id, icon }) => (
                    <button
                      key={id}
                      onClick={() => onTextHalignChange(selected.id, id)}
                      title={`Align ${id}`}
                      className={`flex-1 py-1 rounded-md border text-[10px] transition-all flex items-center justify-center ${
                        (selected.textHalign || 'center') === id
                          ? 'border-gray-700 bg-gray-100 text-gray-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-500 hover:bg-gray-50'
                      }`}
                    >
                      <i className={`fas ${icon}`} />
                    </button>
                  ))}
                  {/* Font color */}
                  <input
                    type="color"
                    value={selected.fontColor?.startsWith('#') ? selected.fontColor : '#374151'}
                    onChange={(e) => onFontColorChange(selected.id, e.target.value)}
                    title="Font color"
                    className="w-8 h-7 rounded border border-gray-200 cursor-pointer p-0 shrink-0"
                  />
                </div>
              </section>

              {/* Text Position */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Text Position</label>
                <div className="flex gap-1">
                  {TEXT_VALIGN_OPTS.map(({ id, label }) => (
                    <button
                      key={id}
                      onClick={() => onTextValignChange(selected.id, id)}
                      className={`flex-1 py-1 rounded-md border text-[9px] font-semibold transition-all ${
                        (selected.textValign || 'bottom') === id
                          ? 'border-gray-700 bg-gray-100 text-gray-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </section>

              {/* Effects: shadow + opacity */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Effects</label>
                {/* Shadow toggle */}
                <button
                  onClick={() => onNodeShadowChange(selected.id, !selected.nodeShadow)}
                  className={`w-full py-1.5 mb-2 rounded-md border text-[10px] font-semibold flex items-center justify-center gap-1.5 transition-all ${
                    selected.nodeShadow
                      ? 'border-gray-700 bg-gray-100 text-gray-700'
                      : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                  }`}
                >
                  <i className="fas fa-square text-[8px]" style={{ textShadow: selected.nodeShadow ? '2px 3px 4px rgba(0,0,0,0.35)' : 'none' }} />
                  Drop Shadow {selected.nodeShadow ? 'On' : 'Off'}
                </button>
                {/* Shadow detail controls — visible when shadow is on */}
                {selected.nodeShadow && (
                  <div className="mb-2 p-2 bg-gray-50 rounded-lg border border-gray-200 space-y-2">
                    <div className="flex items-center gap-1.5">
                      <span className="text-[9px] text-gray-400 w-10 shrink-0">Color</span>
                      <input
                        type="color"
                        value={selected.shadowColor || '#000000'}
                        onChange={(e) => onShadowDetailChange(selected.id, selected.shadowBlur ?? 10, selected.shadowOffsetX ?? 2, selected.shadowOffsetY ?? 4, e.target.value)}
                        className="w-6 h-5 rounded border border-gray-200 cursor-pointer p-0 shrink-0"
                      />
                      <input
                        type="text"
                        value={selected.shadowColor || '#000000'}
                        onChange={(e) => onShadowDetailChange(selected.id, selected.shadowBlur ?? 10, selected.shadowOffsetX ?? 2, selected.shadowOffsetY ?? 4, e.target.value)}
                        className="flex-1 min-w-0 px-1.5 py-0.5 text-[10px] border border-gray-200 rounded font-mono focus:outline-none focus:ring-1 focus:ring-gray-400"
                      />
                    </div>
                    <div>
                      <div className="flex justify-between mb-0.5">
                        <span className="text-[9px] text-gray-400">Blur</span>
                        <span className="text-[9px] font-semibold text-gray-500">{selected.shadowBlur ?? 10}px</span>
                      </div>
                      <input type="range" min="0" max="40" value={selected.shadowBlur ?? 10}
                        onChange={(e) => onShadowDetailChange(selected.id, +e.target.value, selected.shadowOffsetX ?? 2, selected.shadowOffsetY ?? 4, selected.shadowColor || '#000000')}
                        className="w-full h-1.5 accent-gray-700 cursor-pointer" />
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <div>
                        <div className="flex justify-between mb-0.5">
                          <span className="text-[9px] text-gray-400">X offset</span>
                          <span className="text-[9px] font-semibold text-gray-500">{selected.shadowOffsetX ?? 2}</span>
                        </div>
                        <input type="range" min="-20" max="20" value={selected.shadowOffsetX ?? 2}
                          onChange={(e) => onShadowDetailChange(selected.id, selected.shadowBlur ?? 10, +e.target.value, selected.shadowOffsetY ?? 4, selected.shadowColor || '#000000')}
                          className="w-full h-1.5 accent-gray-700 cursor-pointer" />
                      </div>
                      <div>
                        <div className="flex justify-between mb-0.5">
                          <span className="text-[9px] text-gray-400">Y offset</span>
                          <span className="text-[9px] font-semibold text-gray-500">{selected.shadowOffsetY ?? 4}</span>
                        </div>
                        <input type="range" min="-20" max="20" value={selected.shadowOffsetY ?? 4}
                          onChange={(e) => onShadowDetailChange(selected.id, selected.shadowBlur ?? 10, selected.shadowOffsetX ?? 2, +e.target.value, selected.shadowColor || '#000000')}
                          className="w-full h-1.5 accent-gray-700 cursor-pointer" />
                      </div>
                    </div>
                  </div>
                )}
                {/* Flip H / Flip V */}
                <div className="flex gap-1 mb-2">
                  <button onClick={() => onFlipChange(selected.id, !selected.flipH, selected.flipV ?? false)}
                    className={`flex-1 py-1.5 rounded-md border text-[10px] font-semibold flex items-center justify-center gap-1 transition-all ${selected.flipH ? 'border-gray-700 bg-gray-100 text-gray-700' : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'}`}>
                    <i className="fas fa-arrows-alt-h text-[9px]" /> Flip H
                  </button>
                  <button onClick={() => onFlipChange(selected.id, selected.flipH ?? false, !selected.flipV)}
                    className={`flex-1 py-1.5 rounded-md border text-[10px] font-semibold flex items-center justify-center gap-1 transition-all ${selected.flipV ? 'border-gray-700 bg-gray-100 text-gray-700' : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'}`}>
                    <i className="fas fa-arrows-alt-v text-[9px]" /> Flip V
                  </button>
                </div>

                {/* Opacity slider */}
                <div className="flex items-center justify-between mb-1">
                  <span className="text-[10px] text-gray-500 font-semibold">Opacity</span>
                  <span className="text-[10px] font-semibold text-gray-600">{opacityPct}%</span>
                </div>
                <input
                  type="range" min="0.1" max="1" step="0.05"
                  value={selected.opacity ?? 1}
                  onChange={(e) => onOpacityChange(selected.id, parseFloat(e.target.value))}
                  className="w-full h-1.5 accent-gray-700 cursor-pointer"
                />
                <div className="flex justify-between text-[9px] text-gray-400 mt-0.5">
                  <span>10%</span><span>100%</span>
                </div>
              </section>

              {/* Animation: pulse */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Animation</label>
                <button
                  onClick={() => onPulseChange(selected.id, !selected.pulse)}
                  className={`w-full py-1.5 rounded-md border text-[10px] font-semibold flex items-center justify-center gap-1.5 transition-all ${
                    selected.pulse
                      ? 'border-gray-700 bg-gray-100 text-gray-700'
                      : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                  }`}
                >
                  <i className={`fas fa-circle-notch text-[9px] ${selected.pulse ? 'animate-spin' : ''}`} />
                  Pulse {selected.pulse ? 'On' : 'Off'}
                </button>
              </section>

              {/* Size */}
              <section>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Size</label>
                  <button onClick={() => setAspectLock(v => !v)} title={aspectLock ? 'Aspect ratio locked' : 'Lock aspect ratio'}
                    className={`w-5 h-5 flex items-center justify-center rounded border text-[9px] transition-all ${aspectLock ? 'border-gray-700 bg-gray-100 text-gray-700' : 'border-gray-200 text-gray-400 hover:border-gray-300'}`}>
                    <i className={`fas ${aspectLock ? 'fa-lock' : 'fa-unlock'}`} />
                  </button>
                </div>
                <div className="flex gap-1.5">
                  <div className="flex-1">
                    <div className="text-[9px] text-gray-400 mb-0.5">W</div>
                    <input
                      type="number"
                      value={localWidth}
                      min="20"
                      onChange={(e) => setLocalWidth(e.target.value)}
                      onBlur={() => {
                        const w = parseInt(localWidth);
                        if (!isNaN(w) && w >= 20) {
                          if (aspectLock && selected.width > 0) {
                            const newH = Math.round(w * (selected.height / selected.width));
                            setLocalHeight(String(newH));
                            onSizeChange(selected.id, w, newH);
                          } else {
                            const h = parseInt(localHeight);
                            onSizeChange(selected.id, w, isNaN(h) ? selected.height : h);
                          }
                        }
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          const w = parseInt(localWidth);
                          if (!isNaN(w) && w >= 20) {
                            if (aspectLock && selected.width > 0) {
                              const newH = Math.round(w * (selected.height / selected.width));
                              setLocalHeight(String(newH));
                              onSizeChange(selected.id, w, newH);
                            } else {
                              const h = parseInt(localHeight);
                              onSizeChange(selected.id, w, isNaN(h) ? selected.height : h);
                            }
                          }
                        }
                        e.stopPropagation();
                      }}
                      className="w-full px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 font-mono"
                      placeholder="W"
                    />
                  </div>
                  <div className="flex-1">
                    <div className="text-[9px] text-gray-400 mb-0.5">H</div>
                    <input
                      type="number"
                      value={localHeight}
                      min="20"
                      onChange={(e) => setLocalHeight(e.target.value)}
                      onBlur={() => {
                        const h = parseInt(localHeight);
                        if (!isNaN(h) && h >= 20) {
                          if (aspectLock && selected.height > 0) {
                            const newW = Math.round(h * (selected.width / selected.height));
                            setLocalWidth(String(newW));
                            onSizeChange(selected.id, newW, h);
                          } else {
                            const w = parseInt(localWidth);
                            onSizeChange(selected.id, isNaN(w) ? selected.width : w, h);
                          }
                        }
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          const h = parseInt(localHeight);
                          if (!isNaN(h) && h >= 20) {
                            if (aspectLock && selected.height > 0) {
                              const newW = Math.round(h * (selected.width / selected.height));
                              setLocalWidth(String(newW));
                              onSizeChange(selected.id, newW, h);
                            } else {
                              const w = parseInt(localWidth);
                              onSizeChange(selected.id, isNaN(w) ? selected.width : w, h);
                            }
                          }
                        }
                        e.stopPropagation();
                      }}
                      className="w-full px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 font-mono"
                      placeholder="H"
                    />
                  </div>
                </div>
              </section>

              {/* Rotation */}
              <section>
                <div className="flex items-center justify-between mb-1">
                  <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Rotation</label>
                  <span className="text-[10px] font-semibold text-gray-600">{selected.rotation ?? 0}°</span>
                </div>
                <input type="range" min="0" max="359" value={selected.rotation ?? 0}
                  onChange={(e) => onRotationChange(selected.id, +e.target.value)}
                  className="w-full h-1.5 accent-gray-700 cursor-pointer" />
                <div className="flex justify-between text-[9px] text-gray-400 mt-0.5"><span>0°</span><span>359°</span></div>
              </section>

              {/* Table Rows */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Table Rows</label>
                <button onClick={() => onAddTableRow(selected.id)}
                  className="w-full py-1.5 rounded-md border text-[10px] font-semibold flex items-center justify-center gap-1.5 border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50 transition-all">
                  <i className="fas fa-plus text-[8px]" /> Add Row
                </button>
              </section>
            </>
          )}

          {/* ── METADATA / TAGS (both nodes and edges) ── */}
          <section>
            <div className="flex items-center justify-between mb-1.5">
              <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Metadata</label>
              <button
                onClick={() => onMetadataChange(selected.id, [...(selected.metadata || []), { key: '', value: '' }])}
                className="w-5 h-5 flex items-center justify-center rounded border border-gray-200 hover:bg-gray-50 text-gray-400 hover:text-gray-600"
              >
                <i className="fas fa-plus text-[8px]" />
              </button>
            </div>
            {(selected.metadata || []).map((m, i) => (
              <div key={i} className="flex items-center gap-1 mb-1">
                <input
                  value={m.key}
                  placeholder="key"
                  onChange={(e) => {
                    const next = [...(selected.metadata || [])];
                    next[i] = { ...next[i], key: e.target.value };
                    onMetadataChange(selected.id, next);
                  }}
                  onKeyDown={(e) => e.stopPropagation()}
                  className="w-16 px-1.5 py-0.5 text-[10px] border border-gray-200 rounded font-mono focus:outline-none focus:ring-1 focus:ring-gray-400"
                />
                <span className="text-[9px] text-gray-400">=</span>
                <input
                  value={m.value}
                  placeholder="value"
                  onChange={(e) => {
                    const next = [...(selected.metadata || [])];
                    next[i] = { ...next[i], value: e.target.value };
                    onMetadataChange(selected.id, next);
                  }}
                  onKeyDown={(e) => e.stopPropagation()}
                  className="flex-1 min-w-0 px-1.5 py-0.5 text-[10px] border border-gray-200 rounded font-mono focus:outline-none focus:ring-1 focus:ring-gray-400"
                />
                <button
                  onClick={() => {
                    const next = (selected.metadata || []).filter((_, j) => j !== i);
                    onMetadataChange(selected.id, next);
                  }}
                  className="w-4 h-4 flex items-center justify-center text-red-400 hover:text-red-600"
                >
                  <i className="fas fa-times text-[8px]" />
                </button>
              </div>
            ))}
          </section>

          {/* ── COMMENTS ── */}
          <section>
            <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">
              <i className="fas fa-comment-alt text-[9px] mr-1" /> Comments
            </label>
            <textarea
              value={selected.comments || ''}
              rows={3}
              onChange={(e) => onCommentsChange(selected.id, e.target.value)}
              onKeyDown={(e) => e.stopPropagation()}
              placeholder="Add notes or comments…"
              className="w-full px-2 py-1.5 text-[11px] border border-gray-200 rounded-md resize-none focus:outline-none focus:ring-1 focus:ring-gray-400"
            />
          </section>

          {/* ── RAW STYLE EDITOR ── */}
          <section>
            <button
              onClick={() => setShowRawStyle(v => !v)}
              className="w-full flex items-center justify-between text-[10px] font-bold text-gray-500 uppercase tracking-wider py-1 hover:text-gray-700"
            >
              <span>Raw Style</span>
              <i className={`fas fa-chevron-${showRawStyle ? 'up' : 'down'} text-[9px]`} />
            </button>
            {showRawStyle && (
              <div>
                <textarea
                  value={rawStyle}
                  onChange={(e) => setRawStyle(e.target.value)}
                  onKeyDown={(e) => e.stopPropagation()}
                  rows={8}
                  className="w-full px-2 py-1.5 text-[10px] font-mono border border-gray-200 rounded-md resize-none focus:outline-none focus:ring-1 focus:ring-gray-400"
                />
                <button
                  onClick={() => {
                    try {
                      const parsed = JSON.parse(rawStyle);
                      onRawStyleApply(selected.id, parsed);
                    } catch {}
                  }}
                  className="mt-1 w-full py-1 bg-gray-900 text-white text-[10px] font-semibold rounded-md hover:bg-gray-800"
                >
                  Apply
                </button>
              </div>
            )}
          </section>

          {/* ── EDGE PROPERTIES ── */}
          {selected.elementType === 'edge' && (
            <>
              {/* Reverse Direction */}
              <button
                onClick={() => onEdgeReverseDirection(selected.id)}
                className="w-full py-1.5 mb-2 rounded-md border text-[10px] font-semibold flex items-center justify-center gap-1.5 border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50 transition-all"
              >
                <i className="fas fa-exchange-alt text-[8px]" /> Reverse Direction
              </button>

              {/* Line Style */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Line Style</label>
                <div className="grid grid-cols-2 gap-1">
                  {EDGE_STYLES.map((style) => (
                    <button
                      key={style.id}
                      onClick={() => onEdgeStyleChange(selected.id, style.id)}
                      className={`py-1.5 rounded-md border text-[10px] font-semibold transition-all ${
                        selected.edgeStyle === style.id
                          ? 'border-gray-700 bg-gray-100 text-gray-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {style.label}
                    </button>
                  ))}
                </div>
              </section>

              {/* Labels section */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Labels</label>
                <div className="mb-1.5">
                  <span className="text-[9px] text-gray-400 block mb-0.5">Source</span>
                  <input
                    type="text"
                    value={localSourceLabel}
                    onChange={(e) => setLocalSourceLabel(e.target.value)}
                    onBlur={() => onSourceLabelChange(selected.id, localSourceLabel)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') onSourceLabelChange(selected.id, localSourceLabel);
                      e.stopPropagation();
                    }}
                    className="w-full px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400"
                    placeholder="Source label…"
                  />
                </div>
                <div className="mb-1.5">
                  <span className="text-[9px] text-gray-400 block mb-0.5">Middle</span>
                  <input
                    type="text"
                    value={localLabel}
                    onChange={(e) => setLocalLabel(e.target.value)}
                    onBlur={() => onLabelChange(selected.id, localLabel)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') onLabelChange(selected.id, localLabel);
                      e.stopPropagation();
                    }}
                    className="w-full px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400"
                    placeholder="Middle label…"
                  />
                </div>
                <div>
                  <span className="text-[9px] text-gray-400 block mb-0.5">Target</span>
                  <input
                    type="text"
                    value={localTargetLabel}
                    onChange={(e) => setLocalTargetLabel(e.target.value)}
                    onBlur={() => onTargetLabelChange(selected.id, localTargetLabel)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') onTargetLabelChange(selected.id, localTargetLabel);
                      e.stopPropagation();
                    }}
                    className="w-full px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400"
                    placeholder="Target label…"
                  />
                </div>
              </section>

              {/* Waypoint Shape */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Waypoint Shape</label>
                <div className="flex gap-1">
                  {[
                    { id: 'none',     symbol: '—' },
                    { id: 'circle',   symbol: '●' },
                    { id: 'diamond',  symbol: '◆' },
                    { id: 'triangle', symbol: '▲' },
                  ].map(({ id, symbol }) => (
                    <button
                      key={id}
                      onClick={() => onMidArrowShapeChange(selected.id, id)}
                      className={`flex-1 py-1.5 rounded-md border text-[11px] font-semibold transition-all ${
                        (selected.midArrowShape || 'none') === id
                          ? 'border-gray-700 bg-gray-100 text-gray-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {symbol}
                    </button>
                  ))}
                </div>
              </section>

              {/* Arrow Type */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Arrow Type</label>
                <div className="grid grid-cols-2 gap-1">
                  {ARROW_TYPES.map((arrow) => (
                    <button
                      key={arrow.id}
                      onClick={() => onArrowTypeChange(selected.id, arrow.id)}
                      className={`py-1.5 rounded-md border text-[10px] font-semibold transition-all ${
                        selected.arrowType === arrow.id
                          ? 'border-gray-700 bg-gray-100 text-gray-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {arrow.label}
                    </button>
                  ))}
                </div>
              </section>

              {/* Line Color */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Line Color</label>
                <div className="grid grid-cols-8 gap-1 mb-2">
                  {PRESET_COLORS.map((c) => (
                    <ColorSwatch
                      key={c}
                      color={c}
                      active={selected.fillColor === c}
                      onClick={() => onFillColorChange(selected.id, c)}
                    />
                  ))}
                </div>
                <div className="flex items-center gap-1.5">
                  <input
                    type="color"
                    value={selected.fillColor?.startsWith('#') ? selected.fillColor : '#9ca3af'}
                    onChange={(e) => onFillColorChange(selected.id, e.target.value)}
                    className="w-7 h-6 rounded border border-gray-200 cursor-pointer p-0 shrink-0"
                  />
                  <input
                    type="text"
                    value={selected.fillColor || ''}
                    onChange={(e) => onFillColorChange(selected.id, e.target.value)}
                    className="flex-1 min-w-0 px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 font-mono"
                    placeholder="#9ca3af"
                  />
                </div>
              </section>

              {/* Line Width */}
              <section>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Line Width</label>
                  <span className="text-[10px] font-semibold text-gray-600">{selected.edgeWidth ?? 2}px</span>
                </div>
                <input
                  type="range" min="1" max="8"
                  value={selected.edgeWidth ?? 2}
                  onChange={(e) => onEdgeWidthChange(selected.id, parseInt(e.target.value))}
                  className="w-full h-1.5 accent-gray-700 cursor-pointer"
                />
                <div className="flex justify-between text-[9px] text-gray-400 mt-0.5">
                  <span>1</span><span>8</span>
                </div>
              </section>

              {/* Line Dash */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Line Dash</label>
                <div className="flex gap-1">
                  {DASH_OPTS.map(({ id, label }) => (
                    <button
                      key={id}
                      onClick={() => onEdgeDashChange(selected.id, id)}
                      className={`flex-1 py-1 rounded-md border text-[9px] font-semibold transition-all ${
                        (selected.edgeDash || 'solid') === id
                          ? 'border-gray-700 bg-gray-100 text-gray-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </section>

              {/* Routing direction (taxi only) */}
              {selected.edgeStyle === 'taxi' && (
                <section>
                  <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Routing Direction</label>
                  <div className="flex gap-1">
                    {['auto', 'vertical', 'horizontal'].map(d => (
                      <button
                        key={d}
                        onClick={() => onTaxiDirChange(selected.id, d)}
                        className={`flex-1 py-1 rounded-md border text-[9px] font-semibold transition-all capitalize ${
                          (selected.taxiDir || 'auto') === d
                            ? 'border-gray-700 bg-gray-100 text-gray-700'
                            : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                        }`}
                      >
                        {d}
                      </button>
                    ))}
                  </div>
                </section>
              )}

              {/* Connection Ports */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Connection Ports</label>
                <div className="grid grid-cols-2 gap-1.5">
                  <div>
                    <span className="text-[9px] text-gray-400 block mb-0.5">Source</span>
                    <select
                      value={selected.sourcePort || 'auto'}
                      onChange={(e) => onSourcePortChange(selected.id, e.target.value)}
                      className="w-full px-2 py-1 text-[10px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 bg-white"
                    >
                      {['auto', 'top', 'bottom', 'left', 'right'].map(p => (
                        <option key={p} value={p} className="capitalize">{p}</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <span className="text-[9px] text-gray-400 block mb-0.5">Target</span>
                    <select
                      value={selected.targetPort || 'auto'}
                      onChange={(e) => onTargetPortChange(selected.id, e.target.value)}
                      className="w-full px-2 py-1 text-[10px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-gray-400 bg-white"
                    >
                      {['auto', 'top', 'bottom', 'left', 'right'].map(p => (
                        <option key={p} value={p} className="capitalize">{p}</option>
                      ))}
                    </select>
                  </div>
                </div>
              </section>

              {/* Opacity */}
              <section>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Opacity</label>
                  <span className="text-[10px] font-semibold text-gray-600">{opacityPct}%</span>
                </div>
                <input
                  type="range" min="0.1" max="1" step="0.05"
                  value={selected.opacity ?? 1}
                  onChange={(e) => onOpacityChange(selected.id, parseFloat(e.target.value))}
                  className="w-full h-1.5 accent-gray-700 cursor-pointer"
                />
                <div className="flex justify-between text-[9px] text-gray-400 mt-0.5">
                  <span>10%</span><span>100%</span>
                </div>
              </section>

              {/* Animation: edge flow */}
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Animation</label>
                <button
                  onClick={() => onFlowAnimChange(selected.id, !selected.flowAnimation)}
                  className={`w-full py-1.5 mb-2 rounded-md border text-[10px] font-semibold flex items-center justify-center gap-1.5 transition-all ${
                    selected.flowAnimation
                      ? 'border-gray-700 bg-gray-100 text-gray-700'
                      : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                  }`}
                >
                  <i className="fas fa-play text-[8px]" />
                  Flow Animation {selected.flowAnimation ? 'On' : 'Off'}
                </button>
                {selected.flowAnimation && (
                  <div>
                    <div className="flex justify-between mb-0.5">
                      <span className="text-[9px] text-gray-400">Speed</span>
                      <span className="text-[9px] font-semibold text-gray-500">
                        {selected.flowSpeed <= 500 ? 'Fast' : selected.flowSpeed >= 2000 ? 'Slow' : 'Medium'}
                      </span>
                    </div>
                    <input type="range" min="300" max="2500" step="100"
                      value={selected.flowSpeed ?? 1000}
                      onChange={(e) => onFlowSpeedChange(selected.id, +e.target.value)}
                      className="w-full h-1.5 accent-gray-700 cursor-pointer"
                    />
                    <div className="flex justify-between text-[9px] text-gray-400 mt-0.5">
                      <span>Fast</span><span>Slow</span>
                    </div>
                  </div>
                )}
              </section>
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default DesignPropertiesPanel;
