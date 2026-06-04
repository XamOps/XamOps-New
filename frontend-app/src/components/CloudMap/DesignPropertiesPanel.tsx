import React, { useState, useEffect } from 'react';

const PRESET_COLORS = [
  '#6366f1', '#3b82f6', '#0ea5e9', '#22c55e', '#10b981', '#14b8a6',
  '#f59e0b', '#f97316', '#ef4444', '#a855f7', '#ec4899', '#64748b',
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
];

const ARROW_TYPES = [
  { id: 'triangle', label: 'Arrow' },
  { id: 'open', label: 'Open' },
  { id: 'circle', label: 'Circle' },
  { id: 'none', label: 'None' },
];

export interface ElementProperties {
  id: string;
  elementType: 'node' | 'edge';
  label: string;
  fillColor: string;
  borderColor: string;
  nodeShape: string;
  edgeStyle: string;
  arrowType: string;
  fontSize: number;
  width: number;
  height: number;
}

interface Props {
  selected: ElementProperties | null;
  onLabelChange: (id: string, label: string) => void;
  onFillColorChange: (id: string, color: string) => void;
  onBorderColorChange: (id: string, color: string) => void;
  onShapeChange: (id: string, shape: string) => void;
  onEdgeStyleChange: (id: string, style: string) => void;
  onArrowTypeChange: (id: string, arrowType: string) => void;
  onFontSizeChange: (id: string, size: number) => void;
  onSizeChange: (id: string, width: number, height: number) => void;
  onDelete: () => void;
  onDuplicate: () => void;
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

const DesignPropertiesPanel: React.FC<Props> = ({
  selected,
  onLabelChange,
  onFillColorChange,
  onBorderColorChange,
  onShapeChange,
  onEdgeStyleChange,
  onArrowTypeChange,
  onFontSizeChange,
  onSizeChange,
  onDelete,
  onDuplicate,
}) => {
  const [localLabel,  setLocalLabel]  = useState('');
  const [localWidth,  setLocalWidth]  = useState('');
  const [localHeight, setLocalHeight] = useState('');

  useEffect(() => {
    setLocalLabel(selected?.label ?? '');
    setLocalWidth(String(selected?.width  ?? ''));
    setLocalHeight(String(selected?.height ?? ''));
  }, [selected?.id, selected?.label, selected?.width, selected?.height]);

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
            {selected.elementType === 'node' && (
              <button
                onClick={onDuplicate}
                title="Duplicate (D)"
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
        <div className="flex-1 flex flex-col items-center justify-center p-4 text-center">
          <div className="w-9 h-9 bg-gray-100 rounded-xl flex items-center justify-center mb-2.5">
            <i className="fas fa-mouse-pointer text-gray-400 text-sm" />
          </div>
          <p className="text-[11px] text-gray-400 font-medium leading-relaxed mb-4">
            Click a shape or connection to edit
          </p>
          <div className="w-full space-y-1.5">
            <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-1.5">Keyboard Shortcuts</p>
            {[
              ['Del', 'Delete'],
              ['Ctrl+Z', 'Undo'],
              ['Ctrl+Y', 'Redo'],
              ['Ctrl+A', 'Select all'],
              ['Dbl-click', 'Edit label'],
              ['Right-click', 'More options'],
            ].map(([key, desc]) => (
              <div key={key} className="flex items-center justify-between">
                <span className="text-[10px] text-gray-500">{desc}</span>
                <kbd className="px-1.5 py-0.5 bg-gray-100 border border-gray-200 rounded text-gray-600 font-mono text-[9px] leading-none">
                  {key}
                </kbd>
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto p-3 space-y-4 design-props-scroll">
          {/* Label */}
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
              className="w-full px-2.5 py-1.5 text-xs border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:border-transparent"
              placeholder="Enter label..."
            />
          </section>

          {/* ── NODE PROPERTIES ── */}
          {selected.elementType === 'node' && (
            <>
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
                          ? 'border-indigo-500 bg-indigo-50 text-indigo-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {shape.label}
                    </button>
                  ))}
                </div>
              </section>

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
                <div className="flex items-center gap-1.5">
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
                    className="flex-1 min-w-0 px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-indigo-400 font-mono"
                    placeholder="#6366f1"
                  />
                </div>
              </section>

              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Border Color</label>
                <div className="flex items-center gap-1.5">
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
                    className="flex-1 min-w-0 px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-indigo-400 font-mono"
                    placeholder="#e5e7eb"
                  />
                </div>
              </section>

              <section>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Font Size</label>
                  <span className="text-[10px] font-semibold text-indigo-600">{selected.fontSize || 11}px</span>
                </div>
                <input
                  type="range"
                  min="8"
                  max="24"
                  value={selected.fontSize || 11}
                  onChange={(e) => onFontSizeChange(selected.id, parseInt(e.target.value))}
                  className="w-full h-1.5 accent-indigo-500 cursor-pointer"
                />
                <div className="flex justify-between text-[9px] text-gray-400 mt-0.5">
                  <span>8</span><span>24</span>
                </div>
              </section>

              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Size</label>
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
                        const h = parseInt(localHeight);
                        if (!isNaN(w) && w >= 20) onSizeChange(selected.id, w, isNaN(h) ? selected.height : h);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          const w = parseInt(localWidth);
                          const h = parseInt(localHeight);
                          if (!isNaN(w) && w >= 20) onSizeChange(selected.id, w, isNaN(h) ? selected.height : h);
                        }
                        e.stopPropagation();
                      }}
                      className="w-full px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-indigo-400 font-mono"
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
                        const w = parseInt(localWidth);
                        const h = parseInt(localHeight);
                        if (!isNaN(h) && h >= 20) onSizeChange(selected.id, isNaN(w) ? selected.width : w, h);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          const w = parseInt(localWidth);
                          const h = parseInt(localHeight);
                          if (!isNaN(h) && h >= 20) onSizeChange(selected.id, isNaN(w) ? selected.width : w, h);
                        }
                        e.stopPropagation();
                      }}
                      className="w-full px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-indigo-400 font-mono"
                      placeholder="H"
                    />
                  </div>
                </div>
              </section>
            </>
          )}

          {/* ── EDGE PROPERTIES ── */}
          {selected.elementType === 'edge' && (
            <>
              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Line Style</label>
                <div className="grid grid-cols-2 gap-1">
                  {EDGE_STYLES.map((style) => (
                    <button
                      key={style.id}
                      onClick={() => onEdgeStyleChange(selected.id, style.id)}
                      className={`py-1.5 rounded-md border text-[10px] font-semibold transition-all ${
                        selected.edgeStyle === style.id
                          ? 'border-indigo-500 bg-indigo-50 text-indigo-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {style.label}
                    </button>
                  ))}
                </div>
              </section>

              <section>
                <label className="text-[10px] font-bold text-gray-500 uppercase tracking-wider block mb-1.5">Arrow Type</label>
                <div className="grid grid-cols-2 gap-1">
                  {ARROW_TYPES.map((arrow) => (
                    <button
                      key={arrow.id}
                      onClick={() => onArrowTypeChange(selected.id, arrow.id)}
                      className={`py-1.5 rounded-md border text-[10px] font-semibold transition-all ${
                        selected.arrowType === arrow.id
                          ? 'border-indigo-500 bg-indigo-50 text-indigo-700'
                          : 'border-gray-200 hover:border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {arrow.label}
                    </button>
                  ))}
                </div>
              </section>

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
                    className="flex-1 min-w-0 px-2 py-1 text-[11px] border border-gray-200 rounded-md focus:outline-none focus:ring-1 focus:ring-indigo-400 font-mono"
                    placeholder="#9ca3af"
                  />
                </div>
              </section>
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default DesignPropertiesPanel;
