/**
 * BillOps Support Dashboard Page Component
 *
 * Admin support console for viewing and responding to customer conversations.
 * Features: Inbox list, real-time chat, provider filtering, search.
 * Available for ROLE_BILLOPS_ADMIN users.
 *
 * Converted from: billops/support-dashboard.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Improved UI with modern design, better spacing, and animations
 * - Enhanced chat bubbles with gradient styling
 * - Better responsive design
 * - Professional loading states
 *
 * What stayed the same:
 * - All API endpoints remain identical
 * - Same auto-refresh intervals (10s inbox, 3s chat)
 * - Same filtering logic
 * - All business logic preserved exactly
 */

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useSupportDashboard } from '../hooks/useSupportDashboard';
import { useTopbar } from '../contexts/TopbarContext';
import { SupportConversation, ChatMessage, CloudProvider } from '../types/supportDashboard';

const BillOpsSupportDashboardPage: React.FC = () => {
  const {
    filteredConversations,
    messages,
    activeAccount,
    loading,
    chatLoading,
    sending,
    searchQuery,
    providerFilter,
    setSearchQuery,
    setProviderFilter,
    selectConversation,
    sendReply,
    refreshInbox,
    refreshChat,
  } = useSupportDashboard();

  const { setActions, clearActions } = useTopbar();

  const [replyText, setReplyText] = useState('');
  const [providerDropdownOpen, setProviderDropdownOpen] = useState(false);
  const chatBoxRef = useRef<HTMLDivElement>(null);

  /**
   * Scroll chat to bottom when messages change
   */
  useEffect(() => {
    if (chatBoxRef.current) {
      chatBoxRef.current.scrollTop = chatBoxRef.current.scrollHeight;
    }
  }, [messages]);

  /**
   * Handle send reply
   */
  const handleSendReply = useCallback(async () => {
    if (!replyText.trim() || sending) return;

    const success = await sendReply(replyText);
    if (success) {
      setReplyText('');
    }
  }, [replyText, sending, sendReply]);

  /**
   * Handle key press for sending message
   */
  const handleKeyPress = useCallback((e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendReply();
    }
  }, [handleSendReply]);

  /**
   * Format timestamp for display
   */
  const formatTime = (isoString: string): string => {
    if (!isoString) return '';
    const date = new Date(isoString);
    return `${date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} (${date.toLocaleDateString()})`;
  };

  /**
   * Get provider badge classes
   */
  const getProviderClasses = (provider: CloudProvider): string => {
    switch (provider) {
      case 'AWS':
        return 'bg-[#FF9900]';
      case 'Azure':
        return 'bg-[#0072C6]';
      case 'GCP':
        return 'bg-[#34A853]';
      default:
        return 'bg-gray-500';
    }
  };

  /**
   * Render conversation list item
   */
  const renderConversationItem = (conv: SupportConversation) => (
    <div
      key={conv.accountId}
      onClick={() => selectConversation(conv)}
      className={`p-4 border-b border-gray-50 cursor-pointer transition-all hover:bg-white hover:shadow-sm group relative ${activeAccount?.accountId === conv.accountId
          ? 'bg-indigo-50/80 border-l-4 border-l-indigo-600'
          : 'border-l-4 border-l-transparent'
        }`}
    >
      <div className="flex justify-between items-start mb-1">
        <h3 className="font-bold text-gray-800 text-sm truncate pr-2 group-hover:text-indigo-700 transition-colors">
          {conv.senderUsername || conv.accountName}
        </h3>
        <span
          className={`text-[9px] px-1.5 py-0.5 rounded font-bold uppercase tracking-wider text-white shadow-sm ${getProviderClasses(conv.provider)}`}
        >
          {conv.provider}
        </span>
      </div>

      <p className="text-xs text-gray-500 truncate mb-2">
        {conv.accountId}
      </p>

      <div className="flex items-center justify-end">
        <div className="text-[10px] text-indigo-500 font-semibold opacity-0 group-hover:opacity-100 transition-opacity flex items-center gap-1">
          Open <i className="fas fa-chevron-right"></i>
        </div>
      </div>
    </div>
  );

  /**
   * Render chat message bubble
   */
  const renderMessage = (msg: ChatMessage) => (
    <div
      key={msg.id}
      className={`flex flex-col ${msg.userMessage ? 'items-start' : 'items-end'}`}
    >
      <div
        className={`max-w-[75%] rounded-2xl px-5 py-3 shadow-sm text-sm relative group transition-transform ${msg.userMessage
            ? 'bg-white text-gray-800 rounded-tl-none border border-gray-200'
            : 'bg-gradient-to-r from-indigo-600 to-indigo-700 text-white rounded-tr-none shadow-indigo-200'
          }`}
      >
        <p className="leading-relaxed whitespace-pre-wrap">{msg.content}</p>
        <span
          className={`text-[10px] mt-1 block opacity-70 font-medium ${msg.userMessage ? 'text-gray-400' : 'text-indigo-100 text-right'
            }`}
        >
          {formatTime(msg.timestamp)}
        </span>
      </div>
    </div>
  );

  /**
   * Render empty inbox state
   */
  const renderEmptyInbox = () => (
    <div className="flex flex-col items-center justify-center h-48 text-gray-400">
      <div className="w-12 h-12 bg-gray-100 rounded-full flex items-center justify-center mb-2">
        <i className="fas fa-search text-lg"></i>
      </div>
      <p className="text-xs font-medium">No accounts found.</p>
    </div>
  );

  /**
   * Render no active conversation state
   */
  const renderNoActiveConversation = () => (
    <div className="absolute inset-0 flex flex-col items-center justify-center text-gray-400 bg-white/50 backdrop-blur-sm z-10">
      <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mb-4 shadow-inner">
        <i className="fas fa-comments text-3xl text-gray-300"></i>
      </div>
      <p className="font-medium text-sm text-gray-500">Select a conversation to start messaging</p>
    </div>
  );

  /**
   * Render loading skeleton for inbox
   */
  const renderInboxSkeleton = () => (
    <div className="space-y-2 p-4">
      {[1, 2, 3].map(i => (
        <div key={i} className="p-4 border-b border-gray-50 animate-pulse">
          <div className="h-4 bg-gray-200 rounded w-3/4 mb-2"></div>
          <div className="h-3 bg-gray-200 rounded w-1/2"></div>
        </div>
      ))}
    </div>
  );

  /**
   * Render chat loading skeleton
   */
  const renderChatSkeleton = () => (
    <div className="space-y-4 p-6">
      {[1, 2, 3].map(i => (
        <div key={i} className={`flex ${i % 2 === 0 ? 'justify-end' : 'justify-start'}`}>
          <div className={`h-16 bg-gray-200 rounded-2xl animate-pulse ${i % 2 === 0 ? 'w-2/3' : 'w-1/2'}`}></div>
        </div>
      ))}
    </div>
  );

  /**
   * Set Topbar Actions
   */
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        <button
          onClick={() => refreshInbox()}
          className={`flex items-center gap-2 px-4 py-2 bg-white border border-slate-200 rounded-xl text-xs font-bold text-slate-600 hover:bg-slate-50 transition-all ${loading ? 'opacity-50' : ''}`}
          title="Refresh Inbox"
        >
          <i className={`fas fa-sync-alt ${loading ? 'animate-spin' : ''}`}></i>
          <span>Refresh</span>
        </button>
        <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest bg-slate-100/50 px-3 py-1.5 rounded-full border border-slate-200/50 flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-green-500 animate-pulse shadow-[0_0_8px_rgba(34,197,94,0.4)]"></span>
          Live System
        </span>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, loading, refreshInbox]);

  return (
    <div className="support-dashboard-page flex flex-col h-full bg-transparent">

      {/* Main Content */}
      <div className="flex flex-1 overflow-hidden p-6 gap-6">
        {/* Conversation List Panel */}
        <div className="w-1/3 min-w-[320px] flex flex-col bg-white/95 backdrop-blur border border-gray-200 rounded-xl overflow-hidden h-full shadow-sm border-l-4 border-l-indigo-500">
          {/* Search and Filter Header */}
          <div className="p-5 border-b border-gray-100 bg-white/60 space-y-4">
            {/* Search Input */}
            <div className="relative group">
              <i className="fas fa-search absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400 group-focus-within:text-indigo-500 transition-colors"></i>
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full pl-10 pr-4 py-2.5 bg-white border border-gray-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500/50 focus:border-indigo-500 transition-all shadow-sm"
                placeholder="Search accounts..."
              />
            </div>

            {/* Provider Filter */}
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-gray-400 uppercase tracking-wide">Filter Cloud</span>

              <div className="relative">
                <button
                  onClick={() => setProviderDropdownOpen(!providerDropdownOpen)}
                  className="flex items-center gap-2 text-xs font-semibold px-3 py-1.5 rounded-lg border border-gray-200 bg-white hover:bg-gray-50 hover:border-gray-300 transition-all shadow-sm min-w-[120px] justify-between text-gray-700"
                >
                  <span>{providerFilter === '' ? 'All Providers' : providerFilter}</span>
                  <i className={`fas fa-chevron-down text-[10px] text-gray-400 transition-transform ${providerDropdownOpen ? 'rotate-180' : ''}`}></i>
                </button>

                {providerDropdownOpen && (
                  <>
                    <div
                      className="fixed inset-0 z-40"
                      onClick={() => setProviderDropdownOpen(false)}
                    ></div>
                    <div className="absolute right-0 mt-2 w-36 bg-white rounded-xl shadow-xl border border-gray-100 py-1 z-50 overflow-hidden ring-1 ring-black ring-opacity-5">
                      <button
                        onClick={() => { setProviderFilter(''); setProviderDropdownOpen(false); }}
                        className="w-full text-left px-4 py-2 text-xs text-gray-700 hover:bg-indigo-50 hover:text-indigo-600 transition-colors font-medium"
                      >
                        <i className="fas fa-globe w-4 text-center mr-1 text-gray-400"></i> All Clouds
                      </button>
                      <button
                        onClick={() => { setProviderFilter('AWS'); setProviderDropdownOpen(false); }}
                        className="w-full text-left px-4 py-2 text-xs text-gray-700 hover:bg-[#FF9900]/10 hover:text-[#FF9900] transition-colors font-medium"
                      >
                        <i className="fab fa-aws w-4 text-center mr-1 text-[#FF9900]"></i> AWS
                      </button>
                      <button
                        onClick={() => { setProviderFilter('GCP'); setProviderDropdownOpen(false); }}
                        className="w-full text-left px-4 py-2 text-xs text-gray-700 hover:bg-[#34A853]/10 hover:text-[#34A853] transition-colors font-medium"
                      >
                        <i className="fab fa-google w-4 text-center mr-1 text-[#34A853]"></i> GCP
                      </button>
                      <button
                        onClick={() => { setProviderFilter('Azure'); setProviderDropdownOpen(false); }}
                        className="w-full text-left px-4 py-2 text-xs text-gray-700 hover:bg-[#0078D4]/10 hover:text-[#0078D4] transition-colors font-medium"
                      >
                        <i className="fab fa-microsoft w-4 text-center mr-1 text-[#0078D4]"></i> Azure
                      </button>
                    </div>
                  </>
                )}
              </div>
            </div>
          </div>

          {/* Conversation List */}
          <div className="flex-1 overflow-y-auto custom-scrollbar bg-white/40">
            {loading ? (
              renderInboxSkeleton()
            ) : filteredConversations.length === 0 ? (
              renderEmptyInbox()
            ) : (
              filteredConversations.map(renderConversationItem)
            )}
          </div>
        </div>

        {/* Chat Panel */}
        <div className="flex-1 flex flex-col bg-white/95 backdrop-blur border border-gray-200 rounded-xl shadow-sm overflow-hidden relative border-t-4 border-t-purple-500">
          {/* Chat Header */}
          {activeAccount && (
            <div className="h-16 bg-white/80 border-b border-gray-100 px-6 flex items-center justify-between flex-shrink-0 backdrop-blur-sm">
              <div>
                <h2 className="font-bold text-gray-800 text-lg flex items-center gap-2">
                  <span>{activeAccount.accountName || activeAccount.senderUsername}</span>
                  <span className="text-xs font-mono text-gray-500 bg-gray-100 border border-gray-200 px-2 py-0.5 rounded">
                    {activeAccount.accountId}
                  </span>
                </h2>
              </div>
              <button
                onClick={() => refreshChat()}
                className="text-gray-400 hover:text-indigo-600 transition p-2 rounded-full hover:bg-gray-100"
                title="Refresh Chat"
              >
                <i className="fas fa-sync-alt"></i>
              </button>
            </div>
          )}

          {/* Chat Messages */}
          {activeAccount ? (
            <>
              <div
                ref={chatBoxRef}
                className="flex-1 overflow-y-auto p-6 space-y-4 custom-scrollbar bg-gray-50/30"
              >
                {chatLoading ? (
                  renderChatSkeleton()
                ) : messages.length === 0 ? (
                  <div className="flex flex-col items-center justify-center h-full text-gray-400">
                    <i className="fas fa-comments text-4xl mb-3 text-gray-300"></i>
                    <p className="text-sm">No messages yet. Start the conversation!</p>
                  </div>
                ) : (
                  messages.map(renderMessage)
                )}
              </div>

              {/* Reply Input */}
              <div className="p-4 bg-white border-t border-gray-100 flex-shrink-0">
                <div className="flex gap-3">
                  <input
                    type="text"
                    value={replyText}
                    onChange={(e) => setReplyText(e.target.value)}
                    onKeyPress={handleKeyPress}
                    className="flex-1 border border-gray-300 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 shadow-inner bg-gray-50 focus:bg-white transition-all"
                    placeholder="Type your reply here..."
                    disabled={sending}
                  />
                  <button
                    onClick={handleSendReply}
                    disabled={sending || !replyText.trim()}
                    className="bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 text-white px-6 py-2 rounded-xl font-bold text-sm shadow-md hover:shadow-lg transition-all active:scale-95 flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed disabled:active:scale-100"
                  >
                    {sending ? (
                      <>
                        <i className="fas fa-circle-notch fa-spin"></i>
                        <span>Sending...</span>
                      </>
                    ) : (
                      <>
                        <span>Send</span>
                        <i className="fas fa-paper-plane text-xs"></i>
                      </>
                    )}
                  </button>
                </div>
              </div>
            </>
          ) : (
            renderNoActiveConversation()
          )}
        </div>
      </div>
    </div>
  );
};

export default BillOpsSupportDashboardPage;
