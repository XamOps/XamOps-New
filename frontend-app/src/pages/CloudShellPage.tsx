/**
 * CloudShell Page
 *
 * Converted from: cloudshell.html
 *
 * Interactive cloud terminal access with WebSocket connection
 *
 * Features:
 * - Real-time terminal interaction via WebSocket
 * - Account selection support
 * - Connection status indicator
 * - Keyboard shortcuts (Ctrl+Shift+C/V for copy/paste, Ctrl+L for clear)
 * - Auto-reconnect on account change
 * - Professional GitHub-inspired dark theme
 *
 * Changes from original:
 * - Converted from vanilla JS to React with TypeScript
 * - Uses React hooks for state management
 * - Integrated with React Router for navigation
 * - All backend connections and WebSocket logic preserved exactly
 * - Enhanced UI with smooth transitions and professional styling
 */

import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import '@xterm/xterm/css/xterm.css';

const CloudShellPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();

  const terminalRef = useRef<HTMLDivElement>(null);
  const terminalInstanceRef = useRef<Terminal | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const [connectionStatus, setConnectionStatus] = useState<'initializing' | 'connecting' | 'connected' | 'disconnected' | 'error'>('initializing');
  const [currentAccountId, setCurrentAccountId] = useState<string | null>(null);

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
   * Initialize WebSocket connection
   */
  const initWebSocket = useCallback((accountId: string) => {
    if (socketRef.current) {
      socketRef.current.close();
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    const tenantId = sessionStorage.getItem('selectedTenantId') || 'customer1';
    const wsUrl = `${protocol}//${host}/terminal?accountId=${accountId}&tenantId=${tenantId}`;

    setConnectionStatus('connecting');

    const socket = new WebSocket(wsUrl);
    socketRef.current = socket;

    socket.onopen = () => {
      console.log('WebSocket Connected');
      setConnectionStatus('connected');
      if (terminalInstanceRef.current) {
        terminalInstanceRef.current.focus();
      }
    };

    socket.onmessage = (event) => {
      let data = event.data;

      // Filter out common bash non-TTY warnings
      if (
        data.includes('cannot set terminal process group') ||
        data.includes('no job control in this shell')
      ) {
        return;
      }

      if (terminalInstanceRef.current) {
        terminalInstanceRef.current.write(data);
      }
    };

    socket.onclose = (event) => {
      console.warn('WebSocket Closed', event);
      setConnectionStatus('disconnected');
    };

    socket.onerror = (error) => {
      console.error('WebSocket Error:', error);
      setConnectionStatus('error');
    };
  }, []);

  /**
   * Initialize terminal
   */
  const initTerminal = useCallback(() => {
    const accountId = getAccountId();

    if (!accountId) {
      setCurrentAccountId(null);
      return;
    }

    setCurrentAccountId(accountId);

    // Clean up existing terminal
    if (terminalInstanceRef.current) {
      terminalInstanceRef.current.dispose();
      terminalInstanceRef.current = null;
    }

    // Create new terminal
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

    terminalInstanceRef.current = term;

    // Add fit addon
    const fitAddon = new FitAddon();
    fitAddonRef.current = fitAddon;
    term.loadAddon(fitAddon);

    // Open terminal in container
    if (terminalRef.current) {
      term.open(terminalRef.current);
      setTimeout(() => {
        fitAddon.fit();
        term.focus();
      }, 100);

      // Focus on click
      terminalRef.current.addEventListener('click', () => {
        if (terminalInstanceRef.current) {
          terminalInstanceRef.current.focus();
        }
      });
    }

    // Handle terminal input
    term.onData((data) => {
      if (socketRef.current && socketRef.current.readyState === WebSocket.OPEN) {
        socketRef.current.send(data);
      }
    });

    // Handle keyboard shortcuts
    term.attachCustomKeyEventHandler((e) => {
      if (e.type === 'keydown') {
        // Ctrl+Shift+C - Copy
        if (e.ctrlKey && e.shiftKey && e.key === 'C') {
          const selection = term.getSelection();
          if (selection) {
            navigator.clipboard.writeText(selection);
            return false;
          }
        }
        // Ctrl+Shift+V - Paste
        if (e.ctrlKey && e.shiftKey && e.key === 'V') {
          navigator.clipboard.readText().then((text) => {
            if (socketRef.current) socketRef.current.send(text);
          });
          return false;
        }
        // Ctrl+L - Clear
        if (e.ctrlKey && e.key === 'l') {
          term.clear();
          return false;
        }
      }
      return true;
    });

    // Initialize WebSocket
    initWebSocket(accountId);
  }, [getAccountId, initWebSocket]);

  /**
   * Handle window resize
   */
  useEffect(() => {
    const handleResize = () => {
      if (fitAddonRef.current) {
        fitAddonRef.current.fit();
      }
    };

    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  /**
   * Listen for account changes
   */
  useEffect(() => {
    const handleAccountChange = (e: Event) => {
      const customEvent = e as CustomEvent;
      console.log('CloudShell: Account change detected', customEvent.detail);

      if (terminalInstanceRef.current) {
        terminalInstanceRef.current.writeln('\r\n\x1b[33mSwitching accounts...\x1b[0m');
      }

      setTimeout(() => {
        initTerminal();
      }, 500);
    };

    window.addEventListener('account-changed', handleAccountChange);
    return () => window.removeEventListener('account-changed', handleAccountChange);
  }, [initTerminal]);

  /**
   * Initialize terminal on mount and when selectedAccountIds change
   */
  useEffect(() => {
    initTerminal();

    return () => {
      // Cleanup on unmount
      if (socketRef.current) {
        socketRef.current.close();
      }
      if (terminalInstanceRef.current) {
        terminalInstanceRef.current.dispose();
      }
    };
  }, [initTerminal, selectedAccountIds]);

  /**
   * Get connection status display
   */
  const getConnectionStatusDisplay = () => {
    switch (connectionStatus) {
      case 'connected':
        return {
          className: 'px-3 py-1.5 rounded-lg text-xs font-semibold bg-green-50 text-green-700 border border-green-200 flex items-center gap-2',
          icon: 'bg-green-500',
          text: `Connected: ${currentAccountId}`,
        };
      case 'connecting':
        return {
          className: 'px-3 py-1.5 rounded-lg text-xs font-semibold bg-yellow-50 text-yellow-700 border border-yellow-200 flex items-center gap-2',
          icon: 'bg-yellow-500',
          text: 'Connecting...',
        };
      case 'disconnected':
        return {
          className: 'px-3 py-1.5 rounded-lg text-xs font-semibold bg-red-50 text-red-700 border border-red-200 flex items-center gap-2',
          icon: 'bg-red-500',
          text: 'Disconnected',
        };
      case 'error':
        return {
          className: 'px-3 py-1.5 rounded-lg text-xs font-semibold bg-red-50 text-red-700 border border-red-200 flex items-center gap-2',
          icon: 'bg-red-500',
          text: 'Connection Error',
        };
      default:
        return {
          className: 'px-3 py-1.5 rounded-lg text-xs font-semibold bg-gray-100 text-gray-500 border border-gray-200 flex items-center gap-2',
          icon: 'bg-gray-400',
          text: 'Initializing...',
        };
    }
  };

  const statusDisplay = getConnectionStatusDisplay();

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <div className={statusDisplay.className}>
          <div className={`w-1.5 h-1.5 rounded-full ${statusDisplay.icon} ${connectionStatus === 'connected' ? 'animate-pulse' : ''}`}></div>
          <span>{statusDisplay.text}</span>
        </div>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, statusDisplay, connectionStatus]);

  return (
    <main className="flex-1 flex flex-col min-h-0 relative bg-gray-50">

      {/* Terminal Section */}
      <div className="flex-1 flex flex-col min-h-0 bg-gray-900">
        {/* Tab/Toolbar Area */}
        <div className="bg-[#161b22] px-4 py-1 flex items-center border-b border-gray-800 shrink-0">
          <div className="flex items-center bg-[#0d1117] border-t border-l border-r border-orange-500 text-white px-4 py-2 text-sm font-medium rounded-t-md relative -mb-1.5 z-10">
            <i className="fab fa-linux mr-2 text-gray-400"></i>
            <span>bash</span>
            {connectionStatus === 'connected' && (
              <span className="ml-2 w-2 h-2 rounded-full bg-green-500 animate-pulse"></span>
            )}
          </div>
        </div>

        {/* Terminal Container */}
        {currentAccountId ? (
          <div ref={terminalRef} className="flex-1 bg-black overflow-hidden relative"></div>
        ) : (
          <div className="flex-1 flex items-center justify-center bg-[#0d1117]">
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
    </main>
  );
};

export default CloudShellPage;
