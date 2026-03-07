/**
 * BillOps Support Dashboard — Advanced Chat Console
 * Features: Inbox, real-time chat, file attachments (drag & drop),
 *           typing indicator, image lightbox, admin-initiated conversations.
 */

import React, { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { useSupportDashboard } from '../hooks/useSupportDashboard';
import { useTopbarActions } from '../contexts/TopbarContext';
import { SupportConversation, SupportAccount, AttachmentInfo, ChatMessage, CloudProvider } from '../types/supportDashboard';
import { DialogRoot, DialogPanel } from '../components/ui';

/* ─── pending attachment (before send) ─── */
interface PendingAttachment extends AttachmentInfo {
  localUrl: string; // blob URL for preview
}

/* ─── helpers ─── */
const formatFileSize = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

const getFileIcon = (type: string): string => {
  if (type.startsWith('video/')) return 'fa-file-video';
  if (type.startsWith('audio/')) return 'fa-file-audio';
  if (type === 'application/pdf') return 'fa-file-pdf';
  if (type.includes('word') || type.includes('document')) return 'fa-file-word';
  if (type.includes('excel') || type.includes('spreadsheet')) return 'fa-file-excel';
  if (type.includes('zip') || type.includes('archive') || type.includes('compressed')) return 'fa-file-archive';
  return 'fa-file-alt';
};

const getProviderClasses = (provider: CloudProvider | string): string => {
  switch (provider) {
    case 'AWS': return 'bg-[#FF9900]';
    case 'Azure': return 'bg-[#0072C6]';
    case 'GCP': return 'bg-[#34A853]';
    default: return 'bg-gray-500';
  }
};

const getProviderIcon = (provider: string): string => {
  switch (provider?.toUpperCase()) {
    case 'AWS': return 'fab fa-aws';
    case 'GCP': return 'fab fa-google';
    case 'AZURE': return 'fab fa-microsoft';
    default: return 'fas fa-cloud';
  }
};

const formatTime = (isoString: string): string => {
  if (!isoString) return '';
  const d = new Date(isoString);
  return `${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} (${d.toLocaleDateString()})`;
};

const formatRelativeTime = (isoString: string): string => {
  if (!isoString) return '';
  const diff = Date.now() - new Date(isoString).getTime();
  const minutes = Math.floor(diff / 60000);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  if (hours < 24) return `${hours}h ago`;
  if (days < 7) return `${days}d ago`;
  return new Date(isoString).toLocaleDateString();
};

const QUICK_REPLIES = [
  "We'll look into this shortly and get back to you.",
  "Thank you for reaching out. Can you share more details?",
  "This has been escalated to our technical team.",
  "Your request has been received and is being processed.",
  "Could you share a screenshot or log for reference?",
  "The issue has been resolved. Please let us know if anything else comes up.",
];

/* ════════════════════════════════════════════════════════════════ */

const BillOpsSupportDashboardPage: React.FC = () => {
  const {
    conversations,
    filteredConversations,
    messages,
    activeAccount,
    accounts,
    loading,
    chatLoading,
    sending,
    loadingAccounts,
    searchQuery,
    providerFilter,
    setSearchQuery,
    setProviderFilter,
    selectConversation,
    sendReply,
    startConversation,
    uploadAttachment,
    refreshInbox,
    refreshChat,
    fetchAccounts,
  } = useSupportDashboard();

  const { setActions, clearActions } = useTopbarActions();

  /* ── reply state ── */
  const [replyText, setReplyText] = useState('');
  const [providerDropdownOpen, setProviderDropdownOpen] = useState(false);
  const chatBoxRef = useRef<HTMLDivElement>(null);

  /* ── attachment state ── */
  const [pendingAttachment, setPendingAttachment] = useState<PendingAttachment | null>(null);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  /* ── drag & drop ── */
  const [isDragging, setIsDragging] = useState(false);
  const dragCounter = useRef(0);

  /* ── lightbox ── */
  const [lightboxImage, setLightboxImage] = useState<string | null>(null);

  /* ── typing indicator ── */
  const [lastTypedAt, setLastTypedAt] = useState<number | null>(null);
  const [showTyping, setShowTyping] = useState(false);

  /* ── new chat modal ── */
  const [showNewChatModal, setShowNewChatModal] = useState(false);
  const [newChatAccountSearch, setNewChatAccountSearch] = useState('');
  const [selectedNewAccount, setSelectedNewAccount] = useState<SupportAccount | null>(null);
  const [newChatMessage, setNewChatMessage] = useState('');
  const [modalError, setModalError] = useState<string | null>(null);

  /* ── scroll-to-bottom ── */
  const [showScrollBtn, setShowScrollBtn] = useState(false);
  /* ── copy message ── */
  const [copiedMessageId, setCopiedMessageId] = useState<number | null>(null);
  /* ── quick reply templates ── */
  const [showTemplates, setShowTemplates] = useState(false);
  /* ── inbox sort ── */
  const [inboxSort, setInboxSort] = useState<'default' | 'unread' | 'name'>('default');
  /* ── textarea ref ── */
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  /* ────────────────────────────────────── effects ── */

  // Scroll chat to bottom on new messages
  useEffect(() => {
    if (chatBoxRef.current) {
      chatBoxRef.current.scrollTop = chatBoxRef.current.scrollHeight;
    }
  }, [messages, showTyping]);

  // Hide typing indicator after 2s of inactivity
  useEffect(() => {
    if (!lastTypedAt) return;
    const t = setTimeout(() => setShowTyping(false), 2000);
    return () => clearTimeout(t);
  }, [lastTypedAt]);

  /* ────────────────────────────────────── file upload ── */

  const processFile = useCallback(async (file: File) => {
    const localUrl = URL.createObjectURL(file);
    setUploading(true);
    try {
      const result = await uploadAttachment(file);
      if (result) {
        setPendingAttachment({ ...result, localUrl });
      } else {
        URL.revokeObjectURL(localUrl);
      }
    } finally {
      setUploading(false);
    }
  }, [uploadAttachment]);

  const clearPendingAttachment = useCallback(() => {
    if (pendingAttachment?.localUrl) URL.revokeObjectURL(pendingAttachment.localUrl);
    setPendingAttachment(null);
  }, [pendingAttachment]);

  const handleFilePickerChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) processFile(file);
    e.target.value = '';
  }, [processFile]);

  /* ─── drag & drop ─── */
  const handleDragEnter = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    dragCounter.current++;
    if (dragCounter.current === 1) setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    dragCounter.current--;
    if (dragCounter.current === 0) setIsDragging(false);
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
  }, []);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    dragCounter.current = 0;
    setIsDragging(false);
    const file = e.dataTransfer.files[0];
    if (file) processFile(file);
  }, [processFile]);

  /* ─── reply input ─── */
  const handleReplyChange = useCallback((val: string) => {
    setReplyText(val);
    if (val.trim()) {
      setLastTypedAt(Date.now());
      setShowTyping(true);
    } else {
      setShowTyping(false);
    }
  }, []);

  const handleSendReply = useCallback(async () => {
    if ((!replyText.trim() && !pendingAttachment) || sending || uploading) return;
    const attachment = pendingAttachment ? {
      url: pendingAttachment.url,
      name: pendingAttachment.name,
      type: pendingAttachment.type,
      size: pendingAttachment.size,
    } : undefined;
    const success = await sendReply(replyText, attachment);
    if (success) {
      setReplyText('');
      setShowTyping(false);
      if (pendingAttachment) clearPendingAttachment();
    }
  }, [replyText, pendingAttachment, sending, uploading, sendReply, clearPendingAttachment]);

  const handleKeyPress = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendReply();
    }
  }, [handleSendReply]);

  /* ─── new chat modal ─── */
  const openNewChatModal = useCallback(() => {
    setShowNewChatModal(true);
    setModalError(null);
    setNewChatAccountSearch('');
    setSelectedNewAccount(null);
    setNewChatMessage('');
    fetchAccounts();
  }, [fetchAccounts]);

  const closeNewChatModal = useCallback(() => {
    setShowNewChatModal(false);
    setModalError(null);
    setSelectedNewAccount(null);
    setNewChatMessage('');
    setNewChatAccountSearch('');
  }, []);

  const handleStartConversation = useCallback(async () => {
    if (!selectedNewAccount || !newChatMessage.trim() || sending) return;
    setModalError(null);
    const success = await startConversation(selectedNewAccount, newChatMessage);
    if (success) {
      closeNewChatModal();
    } else {
      setModalError('Failed to start conversation. Please check the account and try again.');
    }
  }, [selectedNewAccount, newChatMessage, sending, startConversation, closeNewChatModal]);

  const handleNewChatKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      handleStartConversation();
    }
  }, [handleStartConversation]);

  /* ─── total unread count ─── */
  const totalUnread = conversations.reduce((sum, c) => sum + (c.unreadCount || 0), 0);

  /* ─── inbox sort ─── */
  const sortedConversations = useMemo(() => {
    const list = [...filteredConversations];
    if (inboxSort === 'unread') return list.sort((a, b) => (b.unreadCount || 0) - (a.unreadCount || 0));
    if (inboxSort === 'name') return list.sort((a, b) => (a.accountName || '').localeCompare(b.accountName || ''));
    return list;
  }, [filteredConversations, inboxSort]);

  /* ─── textarea auto-expand ─── */
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 120)}px`;
    }
  }, [replyText]);

  /* ─── scroll-to-bottom ─── */
  const handleChatScroll = useCallback(() => {
    if (!chatBoxRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = chatBoxRef.current;
    setShowScrollBtn(scrollHeight - scrollTop - clientHeight > 100);
  }, []);

  const scrollToBottom = useCallback(() => {
    if (chatBoxRef.current) chatBoxRef.current.scrollTop = chatBoxRef.current.scrollHeight;
  }, []);

  /* ─── copy message ─── */
  const copyMessage = useCallback((content: string, id: number) => {
    navigator.clipboard.writeText(content).then(() => {
      setCopiedMessageId(id);
      setTimeout(() => setCopiedMessageId(null), 2000);
    }).catch(() => {});
  }, []);

  /* ─── filtered accounts for modal ─── */
  const filteredNewChatAccounts = accounts.filter(a => {
    const t = newChatAccountSearch.toLowerCase();
    return a.accountName.toLowerCase().includes(t) ||
      a.accountId.toLowerCase().includes(t) ||
      a.provider.toLowerCase().includes(t);
  });

  /* ─── topbar actions ─── */
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={openNewChatModal}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold shadow-sm transition-all"
        >
          <i className="fas fa-plus"></i>
          <span>New Chat</span>
        </button>
        <button
          onClick={() => refreshInbox()}
          className={`flex items-center gap-2 px-4 py-2 bg-white border border-slate-200 rounded-xl text-xs font-bold text-slate-600 hover:bg-slate-50 transition-all ${loading ? 'opacity-50' : ''}`}
        >
          <i className={`fas fa-sync-alt ${loading ? 'animate-spin' : ''}`}></i>
          <span>Refresh</span>
        </button>
        <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest bg-slate-100/50 px-3 py-1.5 rounded-full border border-slate-200/50 flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-green-500 animate-pulse shadow-[0_0_8px_rgba(34,197,94,0.4)]"></span>
          Live
        </span>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, loading, refreshInbox, openNewChatModal]);

  /* ════════════════════════════ render helpers ══════════════════ */

  const renderConversationItem = (conv: SupportConversation) => (
    <div
      key={conv.accountId}
      onClick={() => selectConversation(conv)}
      className={`p-4 border-b border-gray-50 cursor-pointer transition-all hover:bg-white hover:shadow-sm group relative ${activeAccount?.accountId === conv.accountId
        ? 'bg-indigo-50/80 border-l-4 border-l-indigo-600'
        : 'border-l-4 border-l-transparent'}`}
    >
      <div className="flex justify-between items-start mb-1">
        <h3 className="font-bold text-gray-800 text-sm truncate pr-2 group-hover:text-indigo-700 transition-colors">
          {conv.accountName || conv.senderUsername || conv.accountId}
        </h3>
        <div className="flex items-center gap-2">
          {!!conv.unreadCount && conv.unreadCount > 0 && (
            <span className="flex items-center justify-center bg-rose-500 text-white text-[10px] font-bold h-5 min-w-[20px] px-1.5 rounded-full shadow-sm animate-pulse ring-2 ring-white">
              {conv.unreadCount}
            </span>
          )}
          <span className={`text-[9px] px-1.5 py-0.5 rounded font-bold uppercase tracking-wider text-white shadow-sm ${getProviderClasses(conv.provider)}`}>
            {conv.provider}
          </span>
        </div>
      </div>
      {conv.senderUsername && (
        <p className="text-[11px] text-gray-500 mb-0.5 flex items-center gap-1">
          <i className="fas fa-user text-[9px]"></i> {conv.senderUsername}
        </p>
      )}
      <p className="text-xs text-indigo-600 font-medium truncate mb-0.5">{conv.lastMessage || conv.accountId}</p>
      <div className="flex items-center justify-between mt-1">
        <span className="text-[10px] text-gray-400 font-mono">{conv.lastMessage ? conv.accountId : ''}</span>
        <span className="text-[10px] text-indigo-500 font-semibold opacity-0 group-hover:opacity-100 transition-opacity flex items-center gap-1">
          Open <i className="fas fa-chevron-right"></i>
        </span>
      </div>
    </div>
  );

  const renderAttachment = (msg: ChatMessage) => {
    if (!msg.attachmentUrl) return null;
    const isImage = msg.attachmentType?.startsWith('image/');
    if (isImage) {
      return (
        <div
          className="mt-2 max-w-xs cursor-pointer"
          onClick={() => setLightboxImage(msg.attachmentUrl!)}
        >
          <img
            src={msg.attachmentUrl}
            alt={msg.attachmentName || 'image'}
            className="max-w-full max-h-48 object-cover rounded-xl hover:opacity-90 transition-opacity"
          />
          {msg.attachmentName && (
            <p className={`text-[10px] mt-1 opacity-60 truncate ${msg.userMessage ? 'text-gray-500' : 'text-indigo-100'}`}>
              {msg.attachmentName}
            </p>
          )}
        </div>
      );
    }
    return (
      <a
        href={msg.attachmentUrl}
        download={msg.attachmentName}
        target="_blank"
        rel="noreferrer"
        className={`mt-2 flex items-center gap-2.5 px-3 py-2.5 rounded-xl transition-colors cursor-pointer ${msg.userMessage
          ? 'bg-gray-100 hover:bg-gray-200'
          : 'bg-white/15 hover:bg-white/25'}`}
        onClick={e => e.stopPropagation()}
      >
        <div className={`w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0 ${msg.userMessage ? 'bg-indigo-100' : 'bg-white/20'}`}>
          <i className={`fas ${getFileIcon(msg.attachmentType || '')} text-sm ${msg.userMessage ? 'text-indigo-600' : 'text-white'}`}></i>
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-xs font-semibold truncate">{msg.attachmentName || 'Attachment'}</p>
          {msg.attachmentSize != null && (
            <p className={`text-[10px] ${msg.userMessage ? 'text-gray-500' : 'text-white/60'}`}>
              {formatFileSize(msg.attachmentSize)}
            </p>
          )}
        </div>
        <i className={`fas fa-download text-xs flex-shrink-0 ${msg.userMessage ? 'text-gray-400' : 'text-white/60'}`}></i>
      </a>
    );
  };

  const renderMessage = (msg: ChatMessage) => (
    <div key={msg.id} className={`flex flex-col ${msg.userMessage ? 'items-start' : 'items-end'}`}>
      <div className={`max-w-[75%] rounded-2xl px-5 py-3 shadow-sm text-sm relative group ${msg.userMessage
        ? 'bg-white text-gray-800 rounded-tl-none border border-gray-200'
        : 'bg-gradient-to-r from-indigo-600 to-indigo-700 text-white rounded-tr-none shadow-indigo-200'}`}
      >
        {/* Copy button — appears on hover */}
        {msg.content && (
          <button
            onClick={() => copyMessage(msg.content, msg.id)}
            className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity p-1 rounded"
            title={copiedMessageId === msg.id ? 'Copied!' : 'Copy message'}
          >
            <i className={`fas ${copiedMessageId === msg.id ? 'fa-check' : 'fa-copy'} text-[10px] ${
              msg.userMessage
                ? (copiedMessageId === msg.id ? 'text-green-500' : 'text-gray-300')
                : (copiedMessageId === msg.id ? 'text-green-300' : 'text-white/40')
            }`}></i>
          </button>
        )}
        {msg.content && <p className="leading-relaxed whitespace-pre-wrap pr-5">{msg.content}</p>}
        {renderAttachment(msg)}
        <span
          className={`text-[10px] mt-1.5 block opacity-70 font-medium ${msg.userMessage ? 'text-gray-400' : 'text-indigo-100 text-right'}`}
          title={formatTime(msg.timestamp)}
        >
          {formatRelativeTime(msg.timestamp)}
        </span>
      </div>
    </div>
  );

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

  const renderChatSkeleton = () => (
    <div className="space-y-4 p-6">
      {[1, 2, 3].map(i => (
        <div key={i} className={`flex ${i % 2 === 0 ? 'justify-end' : 'justify-start'}`}>
          <div className={`h-16 bg-gray-200 rounded-2xl animate-pulse ${i % 2 === 0 ? 'w-2/3' : 'w-1/2'}`}></div>
        </div>
      ))}
    </div>
  );

  const renderEmptyInbox = () => (
    <div className="flex flex-col items-center justify-center h-48 text-gray-400 px-4 text-center">
      <div className="w-12 h-12 bg-gray-100 rounded-full flex items-center justify-center mb-3">
        <i className="fas fa-comments text-lg text-gray-300"></i>
      </div>
      <p className="text-xs font-semibold text-gray-500 mb-1">No conversations yet</p>
      <p className="text-[11px] text-gray-400">Click "New Chat" to start a conversation with a client account.</p>
    </div>
  );

  const renderNoActiveConversation = () => (
    <div className="absolute inset-0 flex flex-col items-center justify-center text-gray-400 bg-white/50 backdrop-blur-sm z-10">
      <div className="w-20 h-20 bg-gray-100 rounded-full flex items-center justify-center mb-4 shadow-inner">
        <i className="fas fa-comments text-3xl text-gray-300"></i>
      </div>
      <p className="font-medium text-sm text-gray-500 mb-3">Select a conversation to start messaging</p>
      <button onClick={openNewChatModal} className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-bold rounded-xl shadow transition-all">
        <i className="fas fa-plus"></i> Start New Conversation
      </button>
    </div>
  );

  /* ════════════════════════════════════════════════════════════ */

  return (
    <div className="support-dashboard-page flex flex-col h-[calc(100vh-80px)] bg-transparent overflow-hidden">
      <div className="flex flex-1 overflow-hidden p-6 gap-6 h-full">

        {/* ─── Inbox Panel ─── */}
        <div className="w-1/3 min-w-[300px] flex flex-col bg-white/95 backdrop-blur border border-gray-200 rounded-xl overflow-hidden h-full shadow-sm border-l-4 border-l-indigo-500">
          <div className="px-5 pt-4 pb-3 border-b border-gray-100 bg-white/80 flex-shrink-0">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-sm font-bold text-gray-700 flex items-center gap-2">
                <i className="fas fa-inbox text-indigo-500"></i> Support Inbox
                {totalUnread > 0 && (
                  <span className="flex items-center justify-center bg-rose-500 text-white text-[10px] font-bold h-5 min-w-[20px] px-1.5 rounded-full shadow-sm">
                    {totalUnread}
                  </span>
                )}
              </h2>
              <button
                onClick={openNewChatModal}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[11px] font-bold rounded-lg shadow-sm transition-all active:scale-95"
              >
                <i className="fas fa-plus text-[9px]"></i> New Chat
              </button>
            </div>
            <div className="relative group mb-3">
              <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 group-focus-within:text-indigo-500 transition-colors text-xs"></i>
              <input
                type="text" value={searchQuery} onChange={e => setSearchQuery(e.target.value)}
                className="w-full pl-8 pr-4 py-2 bg-white border border-gray-200 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-indigo-500/50 focus:border-indigo-500 transition-all shadow-sm"
                placeholder="Search by account, user..."
              />
            </div>
            <div className="flex items-center justify-between gap-2">
              <select
                value={inboxSort}
                onChange={e => setInboxSort(e.target.value as 'default' | 'unread' | 'name')}
                className="text-[11px] font-semibold px-2 py-1.5 rounded-lg border border-gray-200 bg-white text-gray-600 cursor-pointer focus:outline-none focus:ring-1 focus:ring-indigo-400 flex-1"
              >
                <option value="default">Latest</option>
                <option value="unread">Unread first</option>
                <option value="name">Name A → Z</option>
              </select>
              <div className="relative">
                <button onClick={() => setProviderDropdownOpen(!providerDropdownOpen)} className="flex items-center gap-2 text-xs font-semibold px-3 py-1.5 rounded-lg border border-gray-200 bg-white hover:bg-gray-50 transition-all shadow-sm min-w-[120px] justify-between text-gray-700">
                  <span>{providerFilter || 'All Clouds'}</span>
                  <i className={`fas fa-chevron-down text-[10px] text-gray-400 transition-transform ${providerDropdownOpen ? 'rotate-180' : ''}`}></i>
                </button>
                {providerDropdownOpen && (
                  <>
                    <div className="fixed inset-0 z-40" onClick={() => setProviderDropdownOpen(false)}></div>
                    <div className="absolute right-0 mt-2 w-36 bg-white rounded-xl shadow-xl border border-gray-100 py-1 z-50 overflow-hidden">
                      {([['', 'All Clouds', 'fas fa-globe', 'text-gray-400'], ['AWS', 'AWS', 'fab fa-aws', 'text-[#FF9900]'], ['GCP', 'GCP', 'fab fa-google', 'text-[#34A853]'], ['Azure', 'Azure', 'fab fa-microsoft', 'text-[#0078D4]']] as const).map(([val, label, icon, cls]) => (
                        <button key={val} onClick={() => { setProviderFilter(val as CloudProvider | ''); setProviderDropdownOpen(false); }} className="w-full text-left px-4 py-2 text-xs text-gray-700 hover:bg-indigo-50 hover:text-indigo-600 transition-colors font-medium">
                          <i className={`${icon} w-4 text-center mr-1 ${cls}`}></i> {label}
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
            </div>
          </div>
          <div className="flex-1 overflow-y-auto custom-scrollbar bg-white/40">
            {loading ? renderInboxSkeleton() : sortedConversations.length === 0 ? renderEmptyInbox() : sortedConversations.map(renderConversationItem)}
          </div>
        </div>

        {/* ─── Chat Panel ─── */}
        <div className="flex-1 flex flex-col bg-white/95 backdrop-blur border border-gray-200 rounded-xl shadow-sm overflow-hidden relative border-t-4 border-t-purple-500">

          {/* Chat Header */}
          {activeAccount && (
            <div className="h-16 bg-white/80 border-b border-gray-100 px-6 flex items-center justify-between flex-shrink-0 backdrop-blur-sm">
              <div className="flex items-center gap-3">
                <div className={`w-9 h-9 rounded-xl flex items-center justify-center text-white text-sm shadow-sm ${getProviderClasses(activeAccount.provider)}`}>
                  <i className={getProviderIcon(activeAccount.provider)}></i>
                </div>
                <div>
                  <h2 className="font-bold text-gray-800 text-base leading-tight">{activeAccount.accountName || activeAccount.senderUsername}</h2>
                  <p className="text-[10px] text-gray-400 font-mono leading-tight">
                    {activeAccount.accountId}
                    {messages.length > 0 && (
                      <span className="ml-2 text-indigo-400 not-italic font-semibold">· {messages.length} messages</span>
                    )}
                  </p>
                </div>
              </div>
              <button onClick={() => refreshChat()} className="text-gray-400 hover:text-indigo-600 transition p-2 rounded-full hover:bg-gray-100" title="Refresh">
                <i className="fas fa-sync-alt text-sm"></i>
              </button>
            </div>
          )}

          {activeAccount ? (
            <>
              {/* Messages Area with drag & drop */}
              <div
                ref={chatBoxRef}
                className="flex-1 overflow-y-auto p-6 space-y-4 custom-scrollbar bg-gray-50/30 relative"
                onScroll={handleChatScroll}
                onDragEnter={handleDragEnter}
                onDragLeave={handleDragLeave}
                onDragOver={handleDragOver}
                onDrop={handleDrop}
              >
                {/* Drag overlay */}
                {isDragging && (
                  <div className="absolute inset-0 z-20 bg-indigo-500/10 border-2 border-dashed border-indigo-400 rounded-lg flex flex-col items-center justify-center pointer-events-none">
                    <div className="bg-white rounded-2xl px-8 py-6 shadow-xl text-center">
                      <i className="fas fa-cloud-upload-alt text-4xl text-indigo-500 mb-3 block"></i>
                      <p className="text-indigo-700 font-bold text-sm">Drop file here to attach</p>
                      <p className="text-gray-400 text-xs mt-1">Images, documents, and more</p>
                    </div>
                  </div>
                )}

                {chatLoading ? renderChatSkeleton() : messages.length === 0 ? (
                  <div className="flex flex-col items-center justify-center h-full text-gray-400">
                    <i className="fas fa-comments text-4xl mb-3 text-gray-300"></i>
                    <p className="text-sm">No messages yet. Start the conversation!</p>
                    <p className="text-xs text-gray-300 mt-1">You can also drag & drop files here</p>
                  </div>
                ) : (
                  messages.map(renderMessage)
                )}

                {/* Typing indicator (admin composing) */}
                {showTyping && (
                  <div className="flex flex-col items-end">
                    <div className="bg-gradient-to-r from-indigo-600/80 to-purple-600/80 rounded-2xl rounded-tr-none px-4 py-2.5 flex items-center gap-2 shadow-sm">
                      <span className="text-white/80 text-xs font-medium">Composing</span>
                      <div className="flex gap-0.5 items-center">
                        {[0, 150, 300].map(delay => (
                          <span key={delay} className="w-1.5 h-1.5 bg-white rounded-full animate-bounce" style={{ animationDelay: `${delay}ms` }}></span>
                        ))}
                      </div>
                    </div>
                  </div>
                )}

                {/* Scroll-to-bottom floating button */}
                {showScrollBtn && (
                  <div className="sticky bottom-0 flex justify-end pointer-events-none">
                    <button
                      onClick={scrollToBottom}
                      className="pointer-events-auto flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[11px] font-bold rounded-full shadow-lg transition-all"
                    >
                      <i className="fas fa-chevron-down text-[9px]"></i>
                      Jump to latest
                    </button>
                  </div>
                )}
              </div>

              {/* Attachment Preview Bar */}
              {(pendingAttachment || uploading) && (
                <div className="px-4 py-2.5 bg-indigo-50 border-t border-indigo-100 flex items-center gap-3 flex-shrink-0">
                  {uploading ? (
                    <div className="flex items-center gap-2 text-indigo-500 text-sm">
                      <i className="fas fa-circle-notch fa-spin"></i>
                      <span className="text-xs font-medium">Uploading file...</span>
                    </div>
                  ) : pendingAttachment && (
                    <>
                      {pendingAttachment.type.startsWith('image/') ? (
                        <img src={pendingAttachment.localUrl} alt="preview" className="w-10 h-10 object-cover rounded-lg border border-indigo-200" />
                      ) : (
                        <div className="w-10 h-10 bg-indigo-100 rounded-lg flex items-center justify-center">
                          <i className={`fas ${getFileIcon(pendingAttachment.type)} text-indigo-600`}></i>
                        </div>
                      )}
                      <div className="flex-1 min-w-0">
                        <p className="text-xs font-semibold text-gray-800 truncate">{pendingAttachment.name}</p>
                        <p className="text-[10px] text-gray-500">{formatFileSize(pendingAttachment.size)}</p>
                      </div>
                      <button onClick={clearPendingAttachment} className="text-gray-400 hover:text-red-500 transition p-1 rounded-full hover:bg-red-50">
                        <i className="fas fa-times text-sm"></i>
                      </button>
                    </>
                  )}
                </div>
              )}

              {/* Reply Input */}
              <div className="p-4 bg-white border-t border-gray-100 flex-shrink-0">
                <div className="flex gap-2 items-end">
                  {/* File attachment button */}
                  <label className={`cursor-pointer p-2.5 rounded-xl transition-all flex-shrink-0 ${uploading ? 'text-indigo-400 bg-indigo-50' : 'text-gray-400 hover:text-indigo-600 hover:bg-indigo-50'}`} title="Attach file">
                    <i className={`fas ${uploading ? 'fa-circle-notch fa-spin' : 'fa-paperclip'} text-base`}></i>
                    <input ref={fileInputRef} type="file" className="hidden" onChange={handleFilePickerChange} disabled={uploading} />
                  </label>

                  {/* Quick reply templates */}
                  <div className="relative flex-shrink-0">
                    <button
                      type="button"
                      onClick={() => setShowTemplates(!showTemplates)}
                      className={`p-2.5 rounded-xl transition-all ${showTemplates ? 'text-indigo-600 bg-indigo-50' : 'text-gray-400 hover:text-indigo-600 hover:bg-indigo-50'}`}
                      title="Quick replies"
                    >
                      <i className="fas fa-bolt text-base"></i>
                    </button>
                    {showTemplates && (
                      <>
                        <div className="fixed inset-0 z-40" onClick={() => setShowTemplates(false)}></div>
                        <div className="absolute bottom-full mb-2 left-0 w-72 bg-white rounded-xl shadow-xl border border-gray-100 py-1 z-50 overflow-hidden">
                          <p className="px-3 py-1.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider border-b border-gray-50">Quick Replies</p>
                          {QUICK_REPLIES.map((template, i) => (
                            <button
                              key={i}
                              onClick={() => { setReplyText(template); setShowTemplates(false); }}
                              className="w-full text-left px-4 py-2.5 text-xs text-gray-700 hover:bg-indigo-50 hover:text-indigo-700 transition-colors border-b border-gray-50 last:border-0 leading-relaxed"
                            >
                              {template}
                            </button>
                          ))}
                        </div>
                      </>
                    )}
                  </div>

                  {/* Textarea input — auto-expands, Shift+Enter for newline */}
                  <textarea
                    ref={textareaRef}
                    value={replyText}
                    onChange={e => handleReplyChange(e.target.value)}
                    onKeyDown={handleKeyPress}
                    rows={1}
                    className="flex-1 border border-gray-300 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 shadow-inner bg-gray-50 focus:bg-white transition-all resize-none overflow-hidden"
                    placeholder={pendingAttachment ? 'Add a caption (optional)...' : 'Type your reply here...'}
                    disabled={sending}
                    style={{ minHeight: '44px', maxHeight: '120px' }}
                  />

                  {/* Send button */}
                  <button
                    onClick={handleSendReply}
                    disabled={sending || uploading || (!replyText.trim() && !pendingAttachment)}
                    className="bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 text-white px-5 py-3 rounded-xl font-bold text-sm shadow-md hover:shadow-lg transition-all active:scale-95 flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed disabled:active:scale-100 flex-shrink-0"
                  >
                    {sending ? (
                      <><i className="fas fa-circle-notch fa-spin text-xs"></i><span className="hidden sm:inline">Sending</span></>
                    ) : (
                      <><span className="hidden sm:inline">Send</span><i className="fas fa-paper-plane text-xs"></i></>
                    )}
                  </button>
                </div>
                <p className="text-[10px] text-gray-400 mt-1.5 ml-1">
                  Enter to send · Shift+Enter for new line · Drag & drop files · <i className="fas fa-bolt text-[9px]"></i> for quick replies
                </p>
              </div>
            </>
          ) : (
            renderNoActiveConversation()
          )}
        </div>
      </div>

      {/* ─── New Chat Modal ─── */}
      <DialogRoot isOpen={showNewChatModal} onClose={closeNewChatModal} maxWidth="max-w-lg">
        <DialogPanel
          title={
            <div>
              <div className="text-base font-bold text-gray-900 flex items-center gap-2">
                <i className="fas fa-plus-circle text-indigo-600"></i> Start New Conversation
              </div>
              <p className="text-xs text-gray-500 mt-0.5">Select a client account and send your first message</p>
            </div>
          }
          onClose={closeNewChatModal}
          footer={
            <>
              <button onClick={closeNewChatModal} className="px-4 py-2 text-sm font-semibold text-gray-600 hover:text-gray-800 border border-gray-200 rounded-xl hover:bg-gray-100 transition-all">
                Cancel
              </button>
              <button
                onClick={handleStartConversation}
                disabled={!selectedNewAccount || !newChatMessage.trim() || sending}
                className="flex items-center gap-2 px-5 py-2 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 text-white text-sm font-bold rounded-xl shadow-md transition-all active:scale-95 disabled:opacity-50 disabled:cursor-not-allowed disabled:active:scale-100"
              >
                {sending ? (
                  <><i className="fas fa-circle-notch fa-spin text-xs"></i><span>Starting...</span></>
                ) : (
                  <><i className="fas fa-paper-plane text-xs"></i><span>Start Conversation</span></>
                )}
              </button>
            </>
          }
        >
          <div className="flex flex-col gap-5 overflow-y-auto max-h-[60vh]">

              {/* Error banner */}
              {modalError && (
                <div className="flex items-start gap-2 px-4 py-3 bg-red-50 border border-red-200 rounded-xl text-sm text-red-700">
                  <i className="fas fa-exclamation-circle mt-0.5 flex-shrink-0"></i>
                  <span>{modalError}</span>
                </div>
              )}

              {/* Account Selector */}
              <div>
                <label className="block text-xs font-bold text-gray-600 uppercase tracking-wide mb-2">
                  Select Account
                </label>

                {/* Search */}
                <div className="relative mb-2">
                  <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
                  <input
                    type="text" value={newChatAccountSearch} onChange={e => setNewChatAccountSearch(e.target.value)}
                    placeholder="Search accounts..." autoFocus
                    className="w-full pl-8 pr-4 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/50 focus:border-indigo-500"
                  />
                </div>

                {/* Selected account chip */}
                {selectedNewAccount && (
                  <div className="flex items-center gap-2 px-3 py-2 bg-indigo-50 border border-indigo-200 rounded-lg mb-2">
                    <span className={`text-[10px] px-1.5 py-0.5 rounded font-bold uppercase text-white ${getProviderClasses(selectedNewAccount.provider)}`}>
                      {selectedNewAccount.provider}
                    </span>
                    <span className="text-sm font-semibold text-indigo-800 flex-1 truncate">{selectedNewAccount.accountName}</span>
                    <span className="text-[10px] text-indigo-500 font-mono">{selectedNewAccount.accountId}</span>
                    <button onClick={() => setSelectedNewAccount(null)} className="text-indigo-400 hover:text-indigo-600 ml-1">
                      <i className="fas fa-times-circle text-xs"></i>
                    </button>
                  </div>
                )}

                {/* Account list */}
                <div className="border border-gray-200 rounded-xl overflow-hidden max-h-44 overflow-y-auto custom-scrollbar">
                  {loadingAccounts ? (
                    <div className="flex items-center justify-center py-6 text-gray-400">
                      <i className="fas fa-circle-notch fa-spin mr-2"></i>
                      <span className="text-xs">Loading accounts...</span>
                    </div>
                  ) : filteredNewChatAccounts.length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-6 text-gray-400">
                      <i className="fas fa-search mb-1.5 text-sm"></i>
                      <span className="text-xs">{accounts.length === 0 ? 'No accounts available' : 'No accounts match your search'}</span>
                    </div>
                  ) : filteredNewChatAccounts.map(account => (
                    <button
                      key={account.id}
                      onClick={() => setSelectedNewAccount(account)}
                      className={`w-full text-left px-4 py-3 flex items-center gap-3 hover:bg-indigo-50 transition-colors border-b border-gray-50 last:border-0 ${selectedNewAccount?.id === account.id ? 'bg-indigo-50' : ''}`}
                    >
                      <span className={`w-8 h-8 rounded-lg flex items-center justify-center text-white text-xs flex-shrink-0 ${getProviderClasses(account.provider)}`}>
                        <i className={getProviderIcon(account.provider)}></i>
                      </span>
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-semibold text-gray-800 truncate">{account.accountName}</p>
                        <p className="text-[10px] text-gray-400 font-mono truncate">{account.accountId}</p>
                      </div>
                      {selectedNewAccount?.id === account.id && (
                        <i className="fas fa-check-circle text-indigo-600 text-sm flex-shrink-0"></i>
                      )}
                    </button>
                  ))}
                </div>
              </div>

              {/* Message */}
              <div>
                <label className="block text-xs font-bold text-gray-600 uppercase tracking-wide mb-2">
                  Initial Message
                </label>
                <textarea
                  value={newChatMessage}
                  onChange={e => setNewChatMessage(e.target.value)}
                  onKeyDown={handleNewChatKeyDown}
                  placeholder="Type your message... (Ctrl+Enter to send)"
                  rows={4}
                  className="w-full border border-gray-200 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 bg-gray-50 focus:bg-white transition-all resize-none"
                  disabled={sending}
                />
                <p className="text-[10px] text-gray-400 mt-1">Ctrl+Enter to send</p>
              </div>
            </div>
        </DialogPanel>
      </DialogRoot>

      {/* ─── Image Lightbox ─── */}
      {lightboxImage && (
        <div
          className="fixed inset-0 z-50 bg-black/90 flex items-center justify-center"
          onClick={() => setLightboxImage(null)}
        >
          <button className="absolute top-4 right-4 text-white/70 hover:text-white transition bg-white/10 hover:bg-white/20 rounded-full p-2.5">
            <i className="fas fa-times text-lg"></i>
          </button>
          <img
            src={lightboxImage}
            alt="Full size"
            className="max-w-[90vw] max-h-[90vh] object-contain rounded-lg shadow-2xl"
            onClick={e => e.stopPropagation()}
          />
        </div>
      )}
    </div>
  );
};

export default BillOpsSupportDashboardPage;
