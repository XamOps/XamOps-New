/**
 * Enhanced Design Palette Component
 *
 * Combines AWS resources with a comprehensive icon library
 * Features tabbed interface for different icon categories
 */

import React, { useState } from 'react';
import IconPicker, { IconData } from './IconPicker';
import { NODE_TYPE_DATA, TAILWIND_COLORS } from '../../types/cloudmap';

interface EnhancedDesignPaletteProps {
  mode: 'live' | 'design';
  onDragStart: (e: React.DragEvent<HTMLDivElement>, type: string) => void;
  onIconSelect?: (icon: IconData) => void;
}

type TabType = 'aws' | 'icons';

const EnhancedDesignPalette: React.FC<EnhancedDesignPaletteProps> = ({
  mode,
  onDragStart,
  onIconSelect,
}) => {
  const [activeTab, setActiveTab] = useState<TabType>('aws');

  if (mode !== 'design') {
    return null;
  }

  const handleIconDragStart = (e: React.DragEvent<HTMLDivElement>, icon: IconData) => {
    e.dataTransfer.setData('iconData', JSON.stringify(icon));
    e.dataTransfer.setData('type', 'custom-icon');
  };

  return (
    <div className="w-72 bg-white border-r border-gray-200 overflow-hidden flex flex-col h-full max-h-full shrink-0 z-10">
      {/* Tabs */}
      <div className="flex border-b border-gray-200 bg-gray-50">
        <button
          onClick={() => setActiveTab('aws')}
          className={`flex-1 px-4 py-3 text-sm font-semibold transition-all relative ${
            activeTab === 'aws'
              ? 'text-indigo-600 bg-white'
              : 'text-gray-600 hover:text-gray-800 hover:bg-gray-100'
          }`}
        >
          <i className="fas fa-aws mr-2" />
          AWS Resources
          {activeTab === 'aws' && (
            <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-indigo-600" />
          )}
        </button>
        <button
          onClick={() => setActiveTab('icons')}
          className={`flex-1 px-4 py-3 text-sm font-semibold transition-all relative ${
            activeTab === 'icons'
              ? 'text-indigo-600 bg-white'
              : 'text-gray-600 hover:text-gray-800 hover:bg-gray-100'
          }`}
        >
          <i className="fas fa-shapes mr-2" />
          Icon Library
          {activeTab === 'icons' && (
            <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-indigo-600" />
          )}
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 min-h-0 overflow-hidden">
        {activeTab === 'aws' && (
          <div className="h-full overflow-y-auto">
            {/* AWS Resources Palette */}
            <div className="p-4">
              <div className="text-xs text-gray-500 mb-3 bg-blue-50 p-2 rounded border border-blue-100 flex items-start gap-2">
                <i className="fas fa-info-circle text-blue-500 mt-0.5" />
                <span>Drag AWS resources to canvas. Click & drag from a node's handle to connect.</span>
              </div>

              <div className="grid grid-cols-2 gap-2">
                {Object.entries(NODE_TYPE_DATA).map(([type, data]) => {
                  const color = TAILWIND_COLORS[data.color as keyof typeof TAILWIND_COLORS]?.[500] || '#6b7280';

                  return (
                    <div
                      key={type}
                      className="palette-item bg-white p-2 rounded-lg border border-gray-200 cursor-grab active:cursor-grabbing hover:shadow-md hover:border-indigo-300 transition-all"
                      draggable
                      onDragStart={(e) => onDragStart(e, type)}
                    >
                      <div
                        className="w-10 h-10 rounded-lg flex items-center justify-center mb-2 mx-auto"
                        style={{ backgroundColor: `${color}20`, color: color }}
                      >
                        <i className={`fas fa-${data.icon} text-lg`} />
                      </div>
                      <span className="text-[10px] font-semibold text-gray-700 leading-tight text-center block">
                        {type}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        )}

        {activeTab === 'icons' && (
          <IconPicker
            onIconSelect={(icon) => onIconSelect?.(icon)}
            onDragStart={handleIconDragStart}
          />
        )}
      </div>
    </div>
  );
};

export default EnhancedDesignPalette;
