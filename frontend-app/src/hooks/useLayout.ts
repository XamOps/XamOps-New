/**
 * useLayout Hook
 *
 * Converted from: dashboard.html (widget layout management with SortableJS)
 *
 * Manages draggable widget layout and persistence.
 * Preserves exact same SortableJS configuration as original.
 */

import { useEffect, useRef, useState, useCallback } from 'react';
import { fetchLayout, saveLayout as saveLayoutApi } from '../services/dashboardApi';

// SortableJS type (loaded via CDN)
declare global {
  interface Window {
    Sortable: any;
  }
}

interface UseLayoutReturn {
  layoutChanged: boolean;
  saveLayout: () => Promise<void>;
  initializeSortable: (containerId: string) => void;
}

export const useLayout = (): UseLayoutReturn => {
  const [layoutChanged, setLayoutChanged] = useState(false);
  const [originalOrder, setOriginalOrder] = useState<string[]>([]);
  const sortableInstanceRef = useRef<any>(null);

  /**
   * Load saved layout from server
   */
  const loadLayout = useCallback(async () => {
    try {
      const layout = await fetchLayout();
      if (layout && layout.widgetOrder) {
        console.log('📐 Loaded widget layout:', layout.widgetOrder);
        setOriginalOrder(layout.widgetOrder);
        return layout.widgetOrder;
      }
    } catch (err) {
      console.warn('⚠️ Could not load layout, using default order');
    }
    return null;
  }, []);

  /**
   * Apply layout order to DOM
   */
  const applyLayoutOrder = useCallback((order: string[], containerId: string) => {
    const container = document.getElementById(containerId);
    if (!container) return;

    order.forEach(widgetId => {
      const widget = document.getElementById(widgetId);
      if (widget) {
        container.appendChild(widget);
      }
    });
  }, []);

  /**
   * Initialize SortableJS on the widgets container
   * Mimics the original initializeDashboardLayout() function
   */
  const initializeSortable = useCallback(
    async (containerId: string) => {
      if (!window.Sortable) {
        console.warn('⚠️ Sortable.js not loaded from CDN');
        return;
      }

      const container = document.getElementById(containerId);
      if (!container) {
        console.warn(`⚠️ Container ${containerId} not found`);
        return;
      }

      // Load and apply saved layout
      const savedOrder = await loadLayout();
      if (savedOrder && savedOrder.length > 0) {
        applyLayoutOrder(savedOrder, containerId);
      }

      // Initialize SortableJS
      const sortable = window.Sortable.create(container, {
        animation: 300,
        handle: '.widget-handle', // Drag handle selector
        ghostClass: 'sortable-ghost',
        chosenClass: 'sortable-chosen',
        dragClass: 'sortable-drag',
        easing: 'cubic-bezier(0.4, 0, 0.2, 1)',
        onEnd: (evt: any) => {
          console.log('🔀 Widget moved:', evt.oldIndex, '→', evt.newIndex);
          setLayoutChanged(true);
        },
      });

      sortableInstanceRef.current = sortable;
      console.log('✅ Sortable initialized on', containerId);
    },
    [loadLayout, applyLayoutOrder]
  );

  /**
   * Save current layout to server
   */
  const saveLayout = useCallback(async () => {
    const container = document.getElementById('widgets-container');
    if (!container) return;

    try {
      // Get current widget order from DOM
      const widgets = Array.from(container.children);
      const widgetOrder = widgets.map(widget => widget.id).filter(id => id);

      console.log('💾 Saving widget layout:', widgetOrder);
      await saveLayoutApi(widgetOrder);

      setOriginalOrder(widgetOrder);
      setLayoutChanged(false);
      console.log('✅ Layout saved successfully');

      // Show success notification (optional)
      showNotification('success', 'Layout saved successfully');
    } catch (err) {
      console.error('❌ Failed to save layout:', err);
      showNotification('error', 'Failed to save layout');
    }
  }, []);

  /**
   * Show notification helper (matches original showNotification)
   */
  const showNotification = (type: 'success' | 'error', message: string) => {
    // This can be enhanced with a proper toast library later
    // For now, use native alert as fallback
    console.log(`[${type.toUpperCase()}] ${message}`);
  };

  /**
   * Cleanup on unmount
   */
  useEffect(() => {
    return () => {
      if (sortableInstanceRef.current) {
        sortableInstanceRef.current.destroy();
        sortableInstanceRef.current = null;
      }
    };
  }, []);

  return {
    layoutChanged,
    saveLayout,
    initializeSortable,
  };
};
