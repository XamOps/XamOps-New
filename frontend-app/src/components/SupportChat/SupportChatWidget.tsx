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

import React, { useCallback, useState, KeyboardEvent } from 'react';
import useSupportChat from '../../hooks/useSupportChat';
import { TooltipWrapper } from '../ui';
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
    unreadFromAdmin,
    sendMessage,
    fetchHistory,
    editMessage,
    deleteMessage,
    messagesEndRef
  } = useSupportChat();

  const [editingId, setEditingId] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');

  /**
   * Handle toggle open/close
   */
  const handleToggle = useCallback(() => {
    const newOpenState = !isOpen;
    setIsOpen(newOpenState);
    if (newOpenState) {
      // Mark messages as read by updating last chat open time
      try {
        localStorage.setItem('lastChatOpenTime', Date.now().toString());
      } catch (error) {
        console.error('Failed to update lastChatOpenTime:', error);
      }
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
   * Format timestamp for display — date + time
   */
  const formatTime = (timestamp?: string): string => {
    if (!timestamp) return '';
    try {
      const d = new Date(timestamp);
      const day = d.getDate().toString().padStart(2, '0');
      const month = d.toLocaleString('default', { month: 'short' });
      const year = d.getFullYear();
      const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      return `${day} ${month} ${year}, ${time}`;
    } catch {
      return '';
    }
  };

  const canEdit = (timestamp?: string) =>
    !!timestamp && (Date.now() - new Date(timestamp).getTime()) < 15 * 60 * 1000;

  const canDelete = (timestamp?: string) =>
    !!timestamp && (Date.now() - new Date(timestamp).getTime()) < 60 * 60 * 1000;

  const handleEditSave = async (id: number) => {
    if (!editContent.trim()) return;
    await editMessage(id, editContent);
    setEditingId(null);
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
        {/* Unread count badge — shown when widget is closed and there are unread admin messages */}
        {!isOpen && unreadFromAdmin > 0 && (
          <span style={{
            position: 'absolute', top: '-6px', right: '-6px',
            background: '#ef4444', color: '#fff',
            borderRadius: '9999px', fontSize: '10px', fontWeight: 700,
            minWidth: '18px', height: '18px', padding: '0 4px',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: '2px solid #fff', zIndex: 10,
          }}>
            {unreadFromAdmin}
          </span>
        )}
        {!isOpen && unreadFromAdmin === 0 && (
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
                  key={msg.id ?? msg.timestamp}
                  className={`support-chat-message ${msg.isUser ? 'support-chat-message-user' : 'support-chat-message-bot'}`}
                >
                  {!msg.isUser && (
                    <div className="support-chat-message-avatar">
                      <i className="fas fa-robot" />
                    </div>
                  )}
                  <div className={`support-chat-message-bubble ${msg.isUser ? 'support-chat-bubble-user' : 'support-chat-bubble-bot'} support-chat-bubble-group`}
                    style={{ position: 'relative' }}
                  >
                    {msg.isDeleted ? (
                      <p className="support-chat-message-text" style={{ fontStyle: 'italic', opacity: 0.65 }}>
                        🚫 This message was deleted
                      </p>
                    ) : editingId === msg.id ? (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', minWidth: '180px' }}>
                        <textarea
                          value={editContent}
                          onChange={e => setEditContent(e.target.value)}
                          rows={3}
                          autoFocus
                          style={{ width: '100%', borderRadius: '8px', border: '1px solid #6366f1', padding: '6px 8px', fontSize: '13px', resize: 'none' }}
                        />
                        <div style={{ display: 'flex', gap: '6px', justifyContent: 'flex-end' }}>
                          <button onClick={() => setEditingId(null)} style={{ fontSize: '11px', padding: '3px 10px', borderRadius: '6px', background: '#e5e7eb', border: 'none', cursor: 'pointer' }}>Cancel</button>
                          <button onClick={() => msg.id && handleEditSave(msg.id)} style={{ fontSize: '11px', padding: '3px 10px', borderRadius: '6px', background: '#6366f1', color: '#fff', border: 'none', cursor: 'pointer', fontWeight: 600 }}>Save</button>
                        </div>
                      </div>
                    ) : (
                      <>
                        <p className="support-chat-message-text">{msg.text}</p>
                        {/* Edit / Delete — only for user's own messages, on hover */}
                        {msg.isUser && (
                          <div className="support-chat-msg-actions">
                            {canEdit(msg.timestamp) && (
                              <TooltipWrapper tip="Edit message">
                                <button
                                  onClick={() => {
                                    setEditingId(msg.id!);
                                    setEditContent(msg.text);
                                  }}
                                  aria-label="Edit message"
                                  className="support-chat-msg-action-btn"
                                >
                                  ✏️
                                </button>
                              </TooltipWrapper>
                            )}
                            {canDelete(msg.timestamp) && (
                              <TooltipWrapper tip="Delete message">
                                <button
                                  onClick={() => msg.id && deleteMessage(msg.id)}
                                  aria-label="Delete message"
                                  className="support-chat-msg-action-btn"
                                >
                                  🗑️
                                </button>
                              </TooltipWrapper>
                            )}
                          </div>
                        )}
                      </>
                    )}
                    {!msg.isDeleted && editingId !== msg.id && msg.timestamp && (
                      <span className="support-chat-message-time" style={{ display: 'flex', alignItems: 'center', gap: '2px' }}>
                        {formatTime(msg.timestamp)}
                        {msg.isEdited && <span style={{ marginLeft: '4px', fontStyle: 'italic', fontSize: '10px', opacity: 0.75 }}>· Edited</span>}
                        {/* Read receipt ticks — only on user's own outgoing messages */}
                        {msg.isUser && (
                          <span
                            title={
                              msg.status === 'READ' ? 'Seen by support' :
                              msg.status === 'UNREAD' ? 'Delivered, waiting for support to read' :
                              'Sending...'
                            }
                            style={{
                              letterSpacing: '-1px', fontSize: '12px', marginLeft: '2px',
                              color: msg.status === 'READ' ? '#0ea5e9' : '#9ca3af',
                              fontWeight: msg.status === 'READ' ? 700 : 400,
                            }}
                          >
                            {/* No status = optimistic (still sending) = single tick
                                UNREAD = delivered to server, admin not read = double gray
                                READ   = admin has read = double blue */}
                            {!msg.status ? '✓' : '✓✓'}
                          </span>
                        )}
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
