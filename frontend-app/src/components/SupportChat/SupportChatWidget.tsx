/**
 * Support Chat Widget Component
 *
 * A floating chat widget for live support, visible on all authenticated pages.
 * Converted from: _support-chat-widget.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Enhanced UI with smoother animations and better styling
 * - Used React hooks and contexts for state management
 * - Added proper accessibility attributes
 * - Professional modern design with glassmorphism effects
 *
 * What stayed the same:
 * - All API endpoints remain identical
 * - Same role-based visibility (ROLE_XAMOPS only)
 * - Same chat functionality and message flow
 * - Same auto-refresh behavior
 */

import React, { useCallback, KeyboardEvent } from 'react';
import useSupportChat from '../../hooks/useSupportChat';
import './SupportChatWidget.css';

const SupportChatWidget: React.FC = () => {
  const {
    isOpen,
    setIsOpen,
    messages,
    newMessage,
    setNewMessage,
    isLoading,
    isXamopsUser,
    sendMessage,
    fetchHistory,
    messagesEndRef
  } = useSupportChat();

  /**
   * Handle toggle open/close
   */
  const handleToggle = useCallback(() => {
    const newOpenState = !isOpen;
    setIsOpen(newOpenState);
    if (newOpenState) {
      fetchHistory();
    }
  }, [isOpen, setIsOpen, fetchHistory]);

  /**
   * Handle send on Enter key
   */
  const handleKeyDown = useCallback((e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  }, [sendMessage]);

  /**
   * Format timestamp for display
   */
  const formatTime = (timestamp?: string): string => {
    if (!timestamp) return '';
    try {
      const date = new Date(timestamp);
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch {
      return '';
    }
  };

  // Don't render if user doesn't have ROLE_XAMOPS
  if (!isXamopsUser) {
    return null;
  }

  return (
    <div className="support-chat-widget">
      {/* Floating Action Button */}
      <button
        onClick={handleToggle}
        className="support-chat-fab"
        title="Live Support"
        aria-label="Open support chat"
        aria-expanded={isOpen}
      >
        <i className={`fas ${isOpen ? 'fa-times' : 'fa-headset'}`} />
        {!isOpen && (
          <span className="support-chat-fab-pulse">
            <span className="support-chat-fab-pulse-ring" />
            <span className="support-chat-fab-pulse-dot" />
          </span>
        )}
      </button>

      {/* Chat Window */}
      <div className={`support-chat-window ${isOpen ? 'support-chat-window-open' : ''}`}>
        {/* Header */}
        <div className="support-chat-header">
          <div className="support-chat-header-info">
            <div className="support-chat-header-avatar">
              <i className="fas fa-robot" />
            </div>
            <div className="support-chat-header-text">
              <h3 className="support-chat-header-title">XamOps Chat Assistant</h3>
              <p className="support-chat-header-status">
                <span className="support-chat-status-dot" />
                Online - Ready to help
              </p>
            </div>
          </div>
          <button
            onClick={handleToggle}
            className="support-chat-close-btn"
            aria-label="Close chat"
          >
            <i className="fas fa-times" />
          </button>
        </div>

        {/* Messages Container */}
        <div className="support-chat-messages">
          {messages.length === 0 ? (
            <div className="support-chat-empty">
              <div className="support-chat-empty-icon">
                <i className="fas fa-comments" />
              </div>
              <p className="support-chat-empty-text">
                Hi there! How can I help you today?
              </p>
              <p className="support-chat-empty-hint">
                Ask me anything about your cloud resources, billing, or XamOps features.
              </p>
            </div>
          ) : (
            <>
              {messages.map((msg) => (
                <div
                  key={msg.id}
                  className={`support-chat-message ${msg.isUser ? 'support-chat-message-user' : 'support-chat-message-bot'}`}
                >
                  {!msg.isUser && (
                    <div className="support-chat-message-avatar">
                      <i className="fas fa-robot" />
                    </div>
                  )}
                  <div className={`support-chat-message-bubble ${msg.isUser ? 'support-chat-bubble-user' : 'support-chat-bubble-bot'}`}>
                    <p className="support-chat-message-text">{msg.text}</p>
                    {msg.timestamp && (
                      <span className="support-chat-message-time">
                        {formatTime(msg.timestamp)}
                      </span>
                    )}
                  </div>
                </div>
              ))}

              {/* Loading indicator */}
              {isLoading && (
                <div className="support-chat-message support-chat-message-bot">
                  <div className="support-chat-message-avatar">
                    <i className="fas fa-robot" />
                  </div>
                  <div className="support-chat-bubble-bot support-chat-typing">
                    <span className="support-chat-typing-dot" />
                    <span className="support-chat-typing-dot" />
                    <span className="support-chat-typing-dot" />
                  </div>
                </div>
              )}

              {/* Scroll anchor */}
              <div ref={messagesEndRef} />
            </>
          )}
        </div>

        {/* Input Area */}
        <div className="support-chat-input-area">
          <div className="support-chat-input-wrapper">
            <input
              type="text"
              value={newMessage}
              onChange={(e) => setNewMessage(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Type your message..."
              className="support-chat-input"
              disabled={isLoading}
              aria-label="Chat message input"
            />
            <button
              onClick={sendMessage}
              disabled={isLoading || newMessage.trim() === ''}
              className="support-chat-send-btn"
              aria-label="Send message"
            >
              <i className="fas fa-paper-plane" />
            </button>
          </div>
          <p className="support-chat-powered-by">
            Powered by XamOps AI
          </p>
        </div>
      </div>
    </div>
  );
};

export default SupportChatWidget;
