/**
 * CloudShell Page
 *
 * Interactive cloud terminal access with WebSocket connection
 *
 * Features:
 * - Real-time terminal interaction via WebSocket
 * - Multiple terminal tabs (like AWS/GCP CloudShell)
 * - Account selection support
 * - Connection status indicator
 * - Keyboard shortcuts (Ctrl+Shift+C/V for copy/paste, Ctrl+L for clear)
 * - Auto-reconnect on account change
 * - Professional GitHub-inspired dark theme
 */

import React, { useEffect, useLayoutEffect, useRef, useState, useCallback } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import '@xterm/xterm/css/xterm.css';

interface TerminalTab {
  id: string;
  name: string;
  accountId: string;
  status: 'initializing' | 'connecting' | 'connected' | 'disconnected' | 'error';
}

const CloudShellPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();

  const terminalContainerRef = useRef<HTMLDivElement>(null);
  const [tabs, setTabs] = useState<TerminalTab[]>([]);
  const [activeTabId, setActiveTabId] = useState<string | null>(null);
  const [currentAccountId, setCurrentAccountId] = useState<string | null>(null);

  // Store terminal instances in refs to allow proper cleanup on unmount
  const terminalInstancesRef = useRef<Map<string, Terminal>>(new Map());
  const socketInstancesRef = useRef<Map<string, WebSocket>>(new Map());
  const fitAddonInstancesRef = useRef<Map<string, FitAddon>>(new Map());

  // Refs for lifecycle management
  const mountedRef = useRef<boolean>(false);
  const tabCounterRef = useRef<number>(0);
  const initializedRef = useRef<boolean>(false);

  /**
   * Cleanup all terminal resources - must be synchronous and not throw
   */
  const cleanupAllResources = useCallback(() => {
    console.log('CloudShell: Cleaning up all resources');

    // Close all WebSocket connections first
    socketInstancesRef.current.forEach((socket) => {
      try {
        socket.onopen = null;
        socket.onmessage = null;
        socket.onclose = null;
        socket.onerror = null;
        if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
          socket.close(1000, 'Cleanup');
        }
      } catch (e) {
        // Ignore errors during cleanup
      }
    });
    socketInstancesRef.current.clear();

    // Dispose all terminal instances
    terminalInstancesRef.current.forEach((term) => {
      try {
        term.dispose();
      } catch (e) {
        // Ignore errors during cleanup
      }
    });
    terminalInstancesRef.current.clear();

    // Clear fit addons
    fitAddonInstancesRef.current.clear();

    // Clear container safely
    if (terminalContainerRef.current) {
      while (terminalContainerRef.current.firstChild) {
        terminalContainerRef.current.removeChild(terminalContainerRef.current.firstChild);
      }
    }

    console.log('CloudShell: Cleanup complete');
  }, []);

  /**
   * Get account ID from URL params or session storage
   */
  const getAccountId = useCallback((): string | null => {
    const urlParams = new URLSearchParams(window.location.search);
    const urlAccountId = urlParams.get('accountId');
    if (urlAccountId) return urlAccountId;

    if (selectedAccountIds.length > 0) {
      return selectedAccountIds[0];
    }

    const saved = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
    if (saved.length > 0) return saved[0];

    return null;
  }, [selectedAccountIds]);

  /**
   * Update tab status
   */
  const updateTabStatus = useCallback((tabId: string, status: TerminalTab['status']) => {
    if (!mountedRef.current) return;
    setTabs((prev) =>
      prev.map((tab) => (tab.id === tabId ? { ...tab, status } : tab))
    );
  }, []);

  /**
   * Initialize WebSocket connection for a tab
   */
  const initWebSocketForTab = useCallback(
    (tabId: string, accountId: string, terminal: Terminal) => {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      // @ts-ignore - Vite env variable
      const isDev = import.meta.env?.MODE === 'development' || window.location.port === '5173';
      const host = isDev ? 'localhost:8080' : window.location.host;
      const tenantId = sessionStorage.getItem('selectedTenantId') || 'customer1';
      const wsUrl = `${protocol}//${host}/terminal?accountId=${accountId}&tenantId=${tenantId}`;

      const socket = new WebSocket(wsUrl);
      socketInstancesRef.current.set(tabId, socket);

      socket.onopen = () => {
        console.log(`WebSocket Connected for tab ${tabId}`);
        if (!mountedRef.current) {
          socket.close(1000, 'Component unmounted');
          return;
        }
        updateTabStatus(tabId, 'connected');
      };

      socket.onmessage = (event) => {
        if (!mountedRef.current) return;

        const data = event.data;

        // Filter out common bash non-TTY warnings
        if (
          data.includes('cannot set terminal process group') ||
          data.includes('no job control in this shell')
        ) {
          return;
        }

        // Write directly to the terminal instance
        terminal.write(data);
      };

      socket.onclose = (event) => {
        console.warn(`WebSocket Closed for tab ${tabId}`, event.code, event.reason);
        if (mountedRef.current) {
          updateTabStatus(tabId, 'disconnected');
        }
      };

      socket.onerror = (error) => {
        console.error(`WebSocket Error for tab ${tabId}:`, error);
        if (mountedRef.current) {
          updateTabStatus(tabId, 'error');
        }
      };

      return socket;
    },
    [updateTabStatus]
  );

  /**
   * Create and initialize a new terminal tab
   */
  const createAndInitTab = useCallback(
    (accountId: string) => {
      if (!terminalContainerRef.current || !mountedRef.current) return null;

      // Prevent duplicate tabs for the same account
      const existingTab = tabs.find(t => t.accountId === accountId);
      if (existingTab) {
        setActiveTabId(existingTab.id);
        return existingTab.id;
      }

      tabCounterRef.current += 1;
      const tabId = `tab-${Date.now()}-${tabCounterRef.current}`;

      // Create container for this terminal
      const container = document.createElement('div');
      container.id = `terminal-${tabId}`;
      container.className = 'absolute inset-0';
      container.style.padding = '8px';

      // Hide other terminal containers
      const existingContainers = terminalContainerRef.current.querySelectorAll('[id^="terminal-"]');
      existingContainers.forEach((el) => {
        (el as HTMLElement).style.display = 'none';
      });

      terminalContainerRef.current.appendChild(container);

      // Create terminal
      const term = new Terminal({
        cursorBlink: true,
        cursorStyle: 'bar',
        fontSize: 13,
        lineHeight: 1.0,
        letterSpacing: 0,
        fontFamily: 'Menlo, Monaco, "Courier New", monospace',
        convertEol: true,
        theme: {
          background: '#0d1117',
          foreground: '#e6edf3',
          cursor: '#2f81f7',
          selectionBackground: '#264f78',
          black: '#484f58',
          red: '#ff7b72',
          green: '#3fb950',
          yellow: '#d29922',
          blue: '#58a6ff',
          magenta: '#bc8cff',
          cyan: '#39c5cf',
          white: '#b1bac4',
          brightBlack: '#6e7681',
          brightRed: '#ffa198',
          brightGreen: '#56d364',
          brightYellow: '#e3b341',
          brightBlue: '#79c0ff',
          brightMagenta: '#d2a8ff',
          brightCyan: '#56d4dd',
          brightWhite: '#f0f6fc',
        },
        scrollback: 10000,
      });

      const fitAddon = new FitAddon();
      term.loadAddon(fitAddon);
      term.open(container);

      // Store instances in refs
      terminalInstancesRef.current.set(tabId, term);
      fitAddonInstancesRef.current.set(tabId, fitAddon);

      // Fit terminal after it's visible
      setTimeout(() => {
        if (mountedRef.current) {
          fitAddon.fit();
          term.focus();
        }
      }, 100);

      // Initialize WebSocket (stores in socketInstancesRef)
      initWebSocketForTab(tabId, accountId, term);

      // Handle terminal input
      term.onData((data) => {
        const currentSocket = socketInstancesRef.current.get(tabId);
        if (currentSocket && currentSocket.readyState === WebSocket.OPEN) {
          currentSocket.send(data);
        }
      });

      // Handle keyboard shortcuts
      term.attachCustomKeyEventHandler((e) => {
        if (e.type === 'keydown') {
          if (e.ctrlKey && e.shiftKey && e.key === 'C') {
            const selection = term.getSelection();
            if (selection) {
              navigator.clipboard.writeText(selection);
              return false;
            }
          }
          if (e.ctrlKey && e.shiftKey && e.key === 'V') {
            navigator.clipboard.readText().then((text) => {
              const currentSocket = socketInstancesRef.current.get(tabId);
              if (currentSocket && currentSocket.readyState === WebSocket.OPEN) {
                currentSocket.send(text);
              }
            });
            return false;
          }
          if (e.ctrlKey && e.key === 'l') {
            term.clear();
            return false;
          }
        }
        return true;
      });

      // Add tab to state
      const newTab: TerminalTab = {
        id: tabId,
        name: `bash ${tabCounterRef.current}`,
        accountId,
        status: 'connecting',
      };

      setTabs((prev) => [...prev, newTab]);
      setActiveTabId(tabId);

      return tabId;
    },
    [initWebSocketForTab, tabs]
  );

  /**
   * Close a tab
   */
  const closeTab = useCallback(
    (tabId: string) => {
      // Cleanup instances for this tab
      const socket = socketInstancesRef.current.get(tabId);
      if (socket) {
        try {
          socket.close(1000, 'Tab closed');
        } catch (e) {
          console.warn('Error closing socket:', e);
        }
        socketInstancesRef.current.delete(tabId);
      }

      const terminal = terminalInstancesRef.current.get(tabId);
      if (terminal) {
        try {
          terminal.dispose();
        } catch (e) {
          console.warn('Error disposing terminal:', e);
        }
        terminalInstancesRef.current.delete(tabId);
      }

      fitAddonInstancesRef.current.delete(tabId);

      // Remove container
      const container = document.getElementById(`terminal-${tabId}`);
      if (container) {
        container.remove();
      }

      setTabs((prev) => {
        const remaining = prev.filter((t) => t.id !== tabId);

        // Switch to another tab if needed
        if (activeTabId === tabId && remaining.length > 0) {
          const newActiveId = remaining[remaining.length - 1].id;
          setActiveTabId(newActiveId);

          // Show the new active tab's container
          if (terminalContainerRef.current) {
            const containers = terminalContainerRef.current.querySelectorAll('[id^="terminal-"]');
            containers.forEach((el) => {
              (el as HTMLElement).style.display = el.id === `terminal-${newActiveId}` ? 'block' : 'none';
            });
          }

          // Fit the new active terminal
          setTimeout(() => {
            const fitAddon = fitAddonInstancesRef.current.get(newActiveId);
            const term = terminalInstancesRef.current.get(newActiveId);
            if (fitAddon && term) {
              fitAddon.fit();
              term.focus();
            }
          }, 50);
        } else if (remaining.length === 0) {
          setActiveTabId(null);
        }

        return remaining;
      });
    },
    [activeTabId]
  );

  /**
   * Switch active tab
   */
  const switchTab = useCallback((tabId: string) => {
    setActiveTabId(tabId);

    // Show the selected tab's terminal and hide others
    if (terminalContainerRef.current) {
      const containers = terminalContainerRef.current.querySelectorAll('[id^="terminal-"]');
      containers.forEach((el) => {
        (el as HTMLElement).style.display = el.id === `terminal-${tabId}` ? 'block' : 'none';
      });

      // Fit and focus the active terminal
      setTimeout(() => {
        const fitAddon = fitAddonInstancesRef.current.get(tabId);
        const term = terminalInstancesRef.current.get(tabId);
        if (fitAddon && term) {
          fitAddon.fit();
          term.focus();
        }
      }, 50);
    }
  }, []);

  /**
   * Handle window resize
   */
  useEffect(() => {
    const handleResize = () => {
      if (activeTabId) {
        const fitAddon = fitAddonInstancesRef.current.get(activeTabId);
        if (fitAddon) {
          fitAddon.fit();
        }
      }
    };

    window.addEventListener('resize', handleResize);

    let resizeObserver: ResizeObserver | null = null;
    if (terminalContainerRef.current) {
      resizeObserver = new ResizeObserver(() => {
        setTimeout(handleResize, 50);
      });
      resizeObserver.observe(terminalContainerRef.current);
    }

    return () => {
      window.removeEventListener('resize', handleResize);
      if (resizeObserver) {
        resizeObserver.disconnect();
      }
    };
  }, [activeTabId]);

  /**
   * Synchronous cleanup on unmount using useLayoutEffect
   * This ensures cleanup happens before React removes the DOM
   */
  useLayoutEffect(() => {
    return () => {
      console.log('CloudShell: useLayoutEffect cleanup triggered');
      mountedRef.current = false;
      initializedRef.current = false;

      // Blur any focused terminal elements to release keyboard capture
      if (document.activeElement instanceof HTMLElement) {
        document.activeElement.blur();
      }

      cleanupAllResources();
    };
  }, [cleanupAllResources]);

  /**
   * Handle visibility change - cleanup when tab becomes hidden
   * This helps with navigation issues
   */
  useEffect(() => {
    const handleBeforeUnload = () => {
      cleanupAllResources();
    };

    window.addEventListener('beforeunload', handleBeforeUnload);

    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload);
    };
  }, [cleanupAllResources]);

  /**
   * Initialize on mount
   */
  useEffect(() => {
    mountedRef.current = true;

    const accountId = getAccountId();
    if (accountId && !initializedRef.current) {
      setCurrentAccountId(accountId);
      initializedRef.current = true;

      // Create first tab after a small delay
      const initTimeout = setTimeout(() => {
        if (mountedRef.current) {
          createAndInitTab(accountId);
        }
      }, 150);

      return () => {
        clearTimeout(initTimeout);
      };
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * Handle account changes
   */
  useEffect(() => {
    const newAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
    if (newAccountId && newAccountId !== currentAccountId && initializedRef.current && mountedRef.current) {
      console.log('Account changed from', currentAccountId, 'to', newAccountId);
      setCurrentAccountId(newAccountId);

      // Cleanup existing resources
      cleanupAllResources();
      setTabs([]);
      tabCounterRef.current = 0;

      // Create new tab for the new account
      setTimeout(() => {
        if (mountedRef.current) {
          createAndInitTab(newAccountId);
        }
      }, 300);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedAccountIds]);

  /**
   * Get connection status for active tab
   */
  const getActiveTabStatus = () => {
    const activeTab = tabs.find((t) => t.id === activeTabId);
    return activeTab?.status || 'initializing';
  };

  const connectionStatus = getActiveTabStatus();

  /**
   * Get connection status display
   */
  const getConnectionStatusDisplay = () => {
    switch (connectionStatus) {
      case 'connected':
        return {
          className:
            'px-3 py-1.5 rounded-lg text-xs font-semibold bg-green-50 text-green-700 border border-green-200 flex items-center gap-2',
          icon: 'bg-green-500',
          text: `Connected: ${currentAccountId}`,
        };
      case 'connecting':
        return {
          className:
            'px-3 py-1.5 rounded-lg text-xs font-semibold bg-yellow-50 text-yellow-700 border border-yellow-200 flex items-center gap-2',
          icon: 'bg-yellow-500',
          text: 'Connecting...',
        };
      case 'disconnected':
        return {
          className:
            'px-3 py-1.5 rounded-lg text-xs font-semibold bg-red-50 text-red-700 border border-red-200 flex items-center gap-2',
          icon: 'bg-red-500',
          text: 'Disconnected',
        };
      case 'error':
        return {
          className:
            'px-3 py-1.5 rounded-lg text-xs font-semibold bg-red-50 text-red-700 border border-red-200 flex items-center gap-2',
          icon: 'bg-red-500',
          text: 'Connection Error',
        };
      default:
        return {
          className:
            'px-3 py-1.5 rounded-lg text-xs font-semibold bg-gray-100 text-gray-500 border border-gray-200 flex items-center gap-2',
          icon: 'bg-gray-400',
          text: 'Initializing...',
        };
    }
  };

  // Topbar Integration
  const { setActions, clearActions } = useTopbarActions();

  const statusDisplay = React.useMemo(() => getConnectionStatusDisplay(), [connectionStatus, currentAccountId]);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <div className={statusDisplay.className}>
          <div
            className={`w-1.5 h-1.5 rounded-full ${statusDisplay.icon} ${connectionStatus === 'connected' ? 'animate-pulse' : ''}`}
          ></div>
          <span>{statusDisplay.text}</span>
        </div>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, connectionStatus, statusDisplay]);

  return (
    <main className="flex-1 flex flex-col min-h-0 h-full relative bg-gray-50">
      {/* Terminal Section - Full height */}
      <div className="flex-1 flex flex-col min-h-0 h-full bg-gray-900">
        {/* Tab/Toolbar Area */}
        <div className="bg-[#161b22] px-2 py-1 flex items-center border-b border-gray-800 shrink-0 gap-1">
          {/* Terminal Tabs */}
          {tabs.map((tab) => (
            <div
              key={tab.id}
              onClick={() => switchTab(tab.id)}
              className={`flex items-center gap-2 px-3 py-2 text-sm font-medium rounded-t-md cursor-pointer transition-colors group ${activeTabId === tab.id
                ? 'bg-[#0d1117] border-t border-l border-r border-orange-500 text-white -mb-px z-10'
                : 'bg-[#21262d] text-gray-400 hover:bg-[#30363d] hover:text-white border border-transparent'
                }`}
            >
              <i className="fab fa-linux text-gray-400 text-xs"></i>
              <span>{tab.name}</span>
              {tab.status === 'connected' && (
                <span className="w-2 h-2 rounded-full bg-green-500 animate-pulse"></span>
              )}
              {tab.status === 'connecting' && (
                <span className="w-2 h-2 rounded-full bg-yellow-500 animate-pulse"></span>
              )}
              {(tab.status === 'disconnected' || tab.status === 'error') && (
                <span className="w-2 h-2 rounded-full bg-red-500"></span>
              )}
              {/* Close button */}
              {tabs.length > 1 && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    closeTab(tab.id);
                  }}
                  className="ml-1 p-0.5 rounded hover:bg-gray-600 opacity-0 group-hover:opacity-100 transition-opacity"
                >
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              )}
            </div>
          ))}

          {/* Add New Tab Button */}
          {currentAccountId && (
            <button
              onClick={() => createAndInitTab(currentAccountId)}
              className="flex items-center justify-center w-8 h-8 text-gray-400 hover:text-white hover:bg-[#30363d] rounded transition-colors"
              title="Open new terminal"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
              </svg>
            </button>
          )}
        </div>

        {/* Terminal Container */}
        <div className="flex-1 relative min-h-0">
          <div
            ref={terminalContainerRef}
            className={`absolute inset-0 bg-[#0d1117] overflow-hidden ${!currentAccountId ? 'hidden' : ''}`}
          ></div>
          {/* No Account Message */}
          {!currentAccountId && (
            <div className="absolute inset-0 flex items-center justify-center bg-[#0d1117]">
              <div className="text-center">
                <div className="w-16 h-16 bg-gradient-to-br from-orange-500 to-amber-600 rounded-full flex items-center justify-center mx-auto mb-4">
                  <i className="fas fa-exclamation-triangle text-white text-2xl"></i>
                </div>
                <h2 className="text-xl font-bold text-gray-300 mb-2">No Account Selected</h2>
                <p className="text-gray-500">Please select an account from the sidebar to start using CloudShell.</p>
              </div>
            </div>
          )}
        </div>
      </div>
    </main>
  );
};

export default CloudShellPage;
