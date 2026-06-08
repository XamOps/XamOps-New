import React, { useEffect, useRef } from 'react';

interface Props {
  onClose: () => void;
}

const SECTIONS = [
  {
    title: 'Keyboard Shortcuts',
    icon: 'fa-keyboard',
    color: 'gray',
    items: [
      { keys: ['Del', 'Backspace'], desc: 'Delete selected' },
      { keys: ['Ctrl+Z'],           desc: 'Undo' },
      { keys: ['Ctrl+Y'],           desc: 'Redo' },
      { keys: ['Ctrl+C'],           desc: 'Copy selected nodes' },
      { keys: ['Ctrl+V'],           desc: 'Paste copied nodes' },
      { keys: ['Ctrl+D'],           desc: 'Duplicate selected' },
      { keys: ['Ctrl+A'],           desc: 'Select all elements' },
      { keys: ['Ctrl+G'],           desc: 'Group selected nodes' },
      { keys: ['Ctrl+Shift+G'],     desc: 'Ungroup selected' },
      { keys: ['Ctrl+L'],           desc: 'Lock / Unlock selected' },
      { keys: ['Ctrl+F'],           desc: 'Find element by label' },
      { keys: ['Esc'],              desc: 'Deselect / cancel operation' },
      { keys: ['F'],                desc: 'Fit all to view' },
      { keys: ['↑ ↓ ← →'],         desc: 'Nudge selected node 5px' },
      { keys: ['Shift+↑↓←→'],      desc: 'Nudge selected node 20px' },
      { keys: ['Space'],            desc: 'Pan mode cursor (drag canvas)' },
      { keys: ['Dbl-click'],        desc: 'Edit label inline' },
      { keys: ['Right-click'],      desc: 'Context menu' },
    ],
  },
  {
    title: 'Canvas & View',
    icon: 'fa-expand-arrows-alt',
    color: 'sky',
    items: [
      { keys: ['Scroll wheel'],   desc: 'Zoom in / out' },
      { keys: ['Click + drag'],   desc: 'Pan canvas (empty area)' },
      { keys: ['Drag node'],      desc: 'Move element' },
      { keys: ['Drag handle'],    desc: 'Resize node (corner handles)' },
      { keys: ['Drag ruler'],     desc: 'Create guide line from ruler edge' },
      { keys: ['Dbl-click guide'],desc: 'Delete guide line' },
      { keys: ['Minimap'],        desc: 'Toggle minimap (toolbar button)' },
      { keys: ['Grid'],           desc: 'Show/hide dot grid background' },
      { keys: ['Snap'],           desc: 'Snap nodes to grid (toolbar)' },
      { keys: ['Canvas BG'],      desc: 'Change canvas background color' },
    ],
  },
  {
    title: 'Shapes & Connections',
    icon: 'fa-shapes',
    color: 'gray',
    items: [
      { keys: ['Click palette'],  desc: 'Add shape at canvas center' },
      { keys: ['Drag palette'],   desc: 'Drop shape at exact position' },
      { keys: ['Arrow buttons'],  desc: 'Connect nodes (hover a node)' },
      { keys: ['Draw mode'],      desc: 'Click-to-click edge drawing mode' },
      { keys: ['Drag waypoint'],  desc: 'Bend edge (handle on selected edge)' },
      { keys: ['Containers'],     desc: 'VPC / Subnet / AZ — drop nodes inside to parent' },
      { keys: ['Sticky Note'],    desc: 'Yellow note with auto-wrap text + shadow' },
      { keys: ['Swimlane'],       desc: 'Container lane for process flows' },
      { keys: ['Text shape'],     desc: 'Transparent label-only shape' },
    ],
  },
  {
    title: 'Style & Format',
    icon: 'fa-paint-brush',
    color: 'rose',
    items: [
      { keys: ['Properties'],     desc: 'Right panel — fill, border, font, size, opacity' },
      { keys: ['Gradient'],       desc: 'Fill Color 2 + direction for gradient nodes' },
      { keys: ['Shadow'],         desc: 'Drop shadow toggle in properties panel' },
      { keys: ['Format Painter'], desc: 'Copy style from one element to another' },
      { keys: ['Lock'],           desc: 'Prevent element from being moved or deleted' },
      { keys: ['Z-order'],        desc: 'Bring Forward / Send Backward via right-click menu' },
      { keys: ['Font family'],    desc: 'Sans, Serif, Mono, Arial, Georgia, Courier' },
      { keys: ['Text align'],     desc: 'Left / Center / Right text alignment per node' },
      { keys: ['Tooltip'],        desc: 'Set hover tooltip text in properties panel' },
    ],
  },
  {
    title: 'Pages & Search',
    icon: 'fa-file-alt',
    color: 'emerald',
    items: [
      { keys: ['+ button'],       desc: 'Add a new page' },
      { keys: ['Click tab'],      desc: 'Switch between pages' },
      { keys: ['Dbl-click tab'],  desc: 'Rename page' },
      { keys: ['× on tab'],       desc: 'Delete page' },
      { keys: ['Ctrl+F'],         desc: 'Open Find bar — search by label' },
      { keys: ['Enter / ↑↓'],     desc: 'Cycle through find results' },
    ],
  },
  {
    title: 'Export & Save',
    icon: 'fa-download',
    color: 'amber',
    items: [
      { keys: ['Save'],           desc: 'Save as named blueprint (Topbar)' },
      { keys: ['PNG'],            desc: 'Export canvas as high-res PNG' },
      { keys: ['SVG'],            desc: 'Export canvas as scalable SVG' },
      { keys: ['PDF'],            desc: 'Open print dialog — saves to PDF' },
      { keys: ['Blueprint'],      desc: 'Load / switch saved blueprints (Topbar)' },
      { keys: ['Clear'],          desc: 'Reset canvas — removes all elements' },
    ],
  },
  {
    title: 'Align & Distribute',
    icon: 'fa-align-left',
    color: 'teal',
    items: [
      { keys: ['Select 2+ nodes'],desc: 'Alignment toolbar appears automatically' },
      { keys: ['Align Left'],     desc: 'Align left edges of selected nodes' },
      { keys: ['Align Center'],   desc: 'Center horizontally' },
      { keys: ['Align Right'],    desc: 'Align right edges' },
      { keys: ['Align Top'],      desc: 'Align top edges' },
      { keys: ['Align Middle'],   desc: 'Center vertically' },
      { keys: ['Align Bottom'],   desc: 'Align bottom edges' },
      { keys: ['Distribute H/V'], desc: 'Even spacing between nodes' },
      { keys: ['Auto Layout'],    desc: 'Dagre / fCoSE / Cola — auto-arrange' },
    ],
  },
];

const COLOR_MAP: Record<string, { bg: string; text: string; icon: string }> = {
  gray:    { bg: 'bg-gray-50',    text: 'text-gray-700',    icon: 'text-gray-500'    },
  sky:     { bg: 'bg-gray-50',    text: 'text-gray-700',    icon: 'text-gray-500'    },
  rose:    { bg: 'bg-gray-50',    text: 'text-gray-700',    icon: 'text-gray-500'    },
  emerald: { bg: 'bg-gray-50',    text: 'text-gray-700',    icon: 'text-gray-500'    },
  amber:   { bg: 'bg-gray-50',    text: 'text-gray-700',    icon: 'text-gray-500'    },
  teal:    { bg: 'bg-gray-50',    text: 'text-gray-700',    icon: 'text-gray-500'    },
};

const DesignHelpModal: React.FC<Props> = ({ onClose }) => {
  const backdropRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      ref={backdropRef}
      className="fixed inset-0 z-[400] flex items-center justify-center bg-black/40 backdrop-blur-sm"
      onMouseDown={(e) => { if (e.target === backdropRef.current) onClose(); }}
    >
      <div className="relative bg-white rounded-2xl shadow-2xl border border-gray-100 w-[92vw] max-w-5xl max-h-[88vh] flex flex-col overflow-hidden">

        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gray-900 flex items-center justify-center shadow-md">
              <i className="fas fa-drafting-compass text-white text-base" />
            </div>
            <div>
              <h2 className="text-base font-bold text-gray-900">Design Studio — Help & Shortcuts</h2>
              <p className="text-[11px] text-gray-400 mt-0.5">Everything you can do in the canvas</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100 text-gray-400 hover:text-gray-700 transition-colors"
          >
            <i className="fas fa-times" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-6">
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
            {SECTIONS.map((sec) => {
              const c = COLOR_MAP[sec.color];
              return (
                <div key={sec.title} className={`rounded-xl border border-gray-100 overflow-hidden`}>
                  {/* Section header */}
                  <div className={`${c.bg} px-4 py-2.5 flex items-center gap-2 border-b border-gray-100`}>
                    <i className={`fas ${sec.icon} text-sm ${c.icon}`} />
                    <span className={`text-[11px] font-bold uppercase tracking-wider ${c.text}`}>{sec.title}</span>
                  </div>
                  {/* Items */}
                  <div className="divide-y divide-gray-50">
                    {sec.items.map((item) => (
                      <div key={item.desc} className="flex items-center gap-2 px-4 py-2 hover:bg-gray-50 transition-colors">
                        <div className="flex gap-1 shrink-0">
                          {item.keys.map((k) => (
                            <kbd
                              key={k}
                              className="px-1.5 py-0.5 bg-gray-100 border border-gray-200 rounded text-gray-700 font-mono text-[9px] leading-tight whitespace-nowrap"
                            >
                              {k}
                            </kbd>
                          ))}
                        </div>
                        <span className="text-[11px] text-gray-600 leading-tight">{item.desc}</span>
                      </div>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>

          {/* Quick tip footer */}
          <div className="mt-5 p-4 rounded-xl bg-gray-50 border border-gray-200 flex items-start gap-3">
            <div className="w-7 h-7 rounded-lg bg-gray-800 flex items-center justify-center shrink-0 mt-0.5">
              <i className="fas fa-lightbulb text-white text-xs" />
            </div>
            <div>
              <p className="text-[11px] font-bold text-gray-800 mb-1">Pro Tips</p>
              <ul className="text-[11px] text-gray-600 space-y-0.5 list-disc list-inside">
                <li>Hover over any node to see quick-connect arrows — drag them to create edges instantly.</li>
                <li>Select 2+ nodes to see the alignment & distribute toolbar appear automatically.</li>
                <li>Format Painter copies all style (fill, border, font, shape) from one element to another.</li>
                <li>Drag from the left or top ruler to place precise guide lines across the canvas.</li>
                <li>Right-click any element for Z-order, lock, duplicate, and more options.</li>
                <li>Multi-page support: use the + tab at the bottom to create multiple diagram pages.</li>
              </ul>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="px-6 py-3 border-t border-gray-100 shrink-0 flex items-center justify-between bg-gray-50/60">
          <span className="text-[10px] text-gray-400">Press <kbd className="px-1.5 py-0.5 bg-white border border-gray-200 rounded font-mono text-[9px] text-gray-600">Esc</kbd> to close</span>
          <button
            onClick={onClose}
            className="px-4 py-1.5 bg-gray-900 hover:bg-gray-800 text-white text-xs font-semibold rounded-lg transition-colors"
          >
            Got it
          </button>
        </div>
      </div>
    </div>
  );
};

export default DesignHelpModal;
