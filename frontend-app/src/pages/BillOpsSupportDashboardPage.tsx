/**
 * BillOps Support Dashboard — Advanced Chat Console
 * Features: Inbox, real-time chat, file attachments (drag & drop),
 *           typing indicator, image lightbox, admin-initiated conversations.
 */

import React, { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { useSupportDashboard } from '../hooks/useSupportDashboard';
import { useTopbarActions } from '../contexts/TopbarContext';
import { SupportConversation, SupportClient, SupportProfile, AttachmentInfo, ChatMessage, CloudProvider } from '../types/supportDashboard';

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

const formatTime = (isoString: string): string => {
  if (!isoString) return '';
  const d = new Date(isoString);
  const day = d.getDate().toString().padStart(2, '0');
  const month = d.toLocaleString('default', { month: 'short' });
  const year = d.getFullYear();
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return `${day} ${month} ${year}, ${time}`;
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
    clients,
    clientProfiles,
    loading,
    chatLoading,
    sending,
    loadingClients,
    loadingProfiles,
    searchQuery,
    providerFilter,
    setSearchQuery,
    setProviderFilter,
    selectConversation,
    sendReply,
    startConversation,
    uploadAttachment,
    editAdminMessage,
    deleteAdminMessage,
    sendTicketEmail,
    refreshInbox,
    refreshChat,
    fetchClients,
    fetchClientProfiles,
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
  const [modalError, setModalError] = useState<string | null>(null);
  // Step 1 – Client
  const [clientSearch, setClientSearch] = useState('');
  const [selectedClient, setSelectedClient] = useState<SupportClient | null>(null);
  // Step 2 – Profile / User
  const [profileSearch, setProfileSearch] = useState('');
  const [selectedProfile, setSelectedProfile] = useState<SupportProfile | null>(null);
  // Message (Step 3)
  const [newChatMessage, setNewChatMessage] = useState('');
  // Auto-ping
  const [autoPingEnabled, setAutoPingEnabled] = useState(false);
  const [pingWhatsApp, setPingWhatsApp] = useState(false);
  const [pingSms, setPingSms] = useState(false);
  const [pingEmail, setPingEmail] = useState(true);

  /* ── scroll-to-bottom ── */
  const [showScrollBtn, setShowScrollBtn] = useState(false);
  /* ── copy message ── */
  const [copiedMessageId, setCopiedMessageId] = useState<number | null>(null);
  /* ── edit message (admin) ── */
  const [editingMessageId, setEditingMessageId] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');
  /* ── quick reply templates ── */
  const [showTemplates, setShowTemplates] = useState(false);
  /* ── inbox sort ── */
  const [inboxSort, setInboxSort] = useState<'default' | 'unread' | 'name'>('default');
  /* ── textarea ref ── */
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  /* ── email modal ── */
  const [showEmailModal, setShowEmailModal] = useState(false);
  const [emailRecipients, setEmailRecipients] = useState<string[]>([]);
  const [emailInput, setEmailInput] = useState('');
  const [emailSubject, setEmailSubject] = useState('');
  const [emailSendMode, setEmailSendMode] = useState<'separate' | 'combined'>('separate');
  const [sendingEmail, setSendingEmail] = useState(false);
  const [emailResult, setEmailResult] = useState<{ success: boolean; error?: string } | null>(null);

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
    setClientSearch('');
    setSelectedClient(null);
    setProfileSearch('');
    setSelectedProfile(null);
    setNewChatMessage('');
    setAutoPingEnabled(false);
    setPingWhatsApp(false);
    setPingSms(false);
    setPingEmail(true);
    fetchClients();
  }, [fetchClients]);

  const closeNewChatModal = useCallback(() => {
    setShowNewChatModal(false);
    setModalError(null);
    setClientSearch('');
    setSelectedClient(null);
    setProfileSearch('');
    setSelectedProfile(null);
    setNewChatMessage('');
    setAutoPingEnabled(false);
  }, []);

  const handleStartConversation = useCallback(async () => {
    if (!selectedProfile || !newChatMessage.trim() || sending) return;
    setModalError(null);
    const autoPingChannels: string[] = [];
    if (pingWhatsApp) autoPingChannels.push('WHATSAPP');
    if (pingSms) autoPingChannels.push('SMS');
    if (pingEmail) autoPingChannels.push('EMAIL');
    const success = await startConversation(
      selectedProfile,
      newChatMessage,
      autoPingEnabled ? { enabled: true, channels: autoPingChannels } : undefined,
    );
    if (success) {
      closeNewChatModal();
    } else {
      setModalError('Failed to start conversation. Please try again.');
    }
  }, [selectedProfile, newChatMessage, sending, autoPingEnabled,
      pingWhatsApp, pingSms, pingEmail, startConversation, closeNewChatModal]);

  const handleNewChatKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      handleStartConversation();
    }
  }, [handleStartConversation]);

  /* ─── email modal ─── */
  const openEmailModal = useCallback(() => {
    setEmailRecipients([]);
    setEmailInput('');
    setEmailSubject(activeAccount ? `Support Chat Thread – ${activeAccount.username}` : '');
    setEmailSendMode('separate');
    setEmailResult(null);
    setShowEmailModal(true);
  }, [activeAccount]);

  const closeEmailModal = useCallback(() => {
    setShowEmailModal(false);
    setEmailRecipients([]);
    setEmailInput('');
    setEmailSendMode('separate');
    setEmailResult(null);
  }, []);

  const addEmailRecipient = useCallback((email: string) => {
    const trimmed = email.trim().toLowerCase();
    if (trimmed && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed) && !emailRecipients.includes(trimmed)) {
      setEmailRecipients(prev => [...prev, trimmed]);
    }
    setEmailInput('');
  }, [emailRecipients]);

  const removeEmailRecipient = useCallback((email: string) => {
    setEmailRecipients(prev => prev.filter(e => e !== email));
  }, []);

  const handleEmailInputKeyDown = useCallback((e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      addEmailRecipient(emailInput);
    } else if (e.key === 'Backspace' && !emailInput && emailRecipients.length > 0) {
      setEmailRecipients(prev => prev.slice(0, -1));
    }
  }, [emailInput, emailRecipients, addEmailRecipient]);

  const handleSendEmail = useCallback(async () => {
    if (!activeAccount?.userId) return;
    const allRecipients = emailInput.trim()
      ? [...emailRecipients, emailInput.trim()]
      : emailRecipients;
    setSendingEmail(true);
    setEmailResult(null);
    const result = await sendTicketEmail(
      activeAccount.userId,
      allRecipients,
      emailSendMode,
      emailSubject.trim() || undefined
    );
    setSendingEmail(false);
    setEmailResult(result);
    if (result.success) {
      setTimeout(() => closeEmailModal(), 2000);
    }
  }, [activeAccount, emailRecipients, emailInput, emailSendMode, emailSubject, sendTicketEmail, closeEmailModal]);

  /* ─── total unread count ─── */
  const totalUnread = conversations.reduce((sum, c) => sum + (c.unreadCount || 0), 0);

  /* ─── inbox sort ─── */
  const sortedConversations = useMemo(() => {
    const list = [...filteredConversations];
    if (inboxSort === 'unread') return list.sort((a, b) => (b.unreadCount || 0) - (a.unreadCount || 0));
    if (inboxSort === 'name') return list.sort((a, b) => (a.username || '').localeCompare(b.username || ''));
    // default = 'latest' — most recent message first
    return list.sort((a, b) => {
      const ta = a.lastMessageTime ? new Date(a.lastMessageTime).getTime() : 0;
      const tb = b.lastMessageTime ? new Date(b.lastMessageTime).getTime() : 0;
      return tb - ta;
    });
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

  /* ─── modal: filtered lists ─── */
  const filteredClients = clients.filter(c =>
    c.name.toLowerCase().includes(clientSearch.toLowerCase())
  );

  const filteredProfiles = clientProfiles.filter(p => {
    const t = profileSearch.toLowerCase();
    return p.username.toLowerCase().includes(t) || (p.email || '').toLowerCase().includes(t);
  });

  /* ─── modal: select client → fetch profiles ─── */
  const handleSelectClient = useCallback((client: SupportClient) => {
    setSelectedClient(client);
    setSelectedProfile(null);
    setProfileSearch('');
    fetchClientProfiles(client.id);
  }, [fetchClientProfiles]);

  /* ─── modal: select profile ─── */
  const handleSelectProfile = useCallback((profile: SupportProfile) => {
    setSelectedProfile(profile);
  }, []);

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

  const renderConversationItem = (conv: SupportConversation) => {
    const isActive = activeAccount?.userId === conv.userId;
    return (
      <div
        key={`user-${conv.userId ?? 'null'}`}
        onClick={() => selectConversation(conv)}
        className={`p-4 border-b border-gray-50 cursor-pointer transition-all hover:bg-white hover:shadow-sm group relative ${isActive
          ? 'bg-indigo-50/80 border-l-4 border-l-indigo-600'
          : 'border-l-4 border-l-transparent'}`}
      >
        <div className="flex justify-between items-start mb-1">
          {/* Username is now the primary conversation identifier */}
          <h3 className="font-bold text-gray-800 text-sm truncate pr-2 group-hover:text-indigo-700 transition-colors flex items-center gap-1.5">
            <i className="fas fa-user-circle text-indigo-400 text-xs"></i>
            {conv.username || conv.senderUsername || 'Unknown User'}
          </h3>
          <div className="flex items-center gap-2">
            {!!conv.unreadCount && conv.unreadCount > 0 && (
              <span className="flex items-center justify-center bg-rose-500 text-white text-[10px] font-bold h-5 min-w-[20px] px-1.5 rounded-full shadow-sm animate-pulse ring-2 ring-white">
                {conv.unreadCount}
              </span>
            )}
          </div>
        </div>
        <p className="text-xs text-gray-500 font-medium truncate mb-0.5">{conv.lastMessage || 'No messages yet'}</p>
        <div className="flex items-center justify-between mt-1 gap-2">
          <span className="text-[10px] text-gray-400 truncate flex-1">
            {conv.lastMessageTime ? formatTime(conv.lastMessageTime) : ''}
          </span>
          <div className="flex items-center gap-1.5 flex-shrink-0">
            {(conv.unreadCount ?? 0) > 0 ? (
              <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full uppercase tracking-wide bg-amber-100 text-amber-700">
                Unread
              </span>
            ) : (
              <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full uppercase tracking-wide bg-green-100 text-green-700">
                Read
              </span>
            )}
            {/* Admin messages user hasn't seen yet — shown as blue ticks count */}
            {(conv.adminUnreadCount ?? 0) > 0 && (
              <span
                title={`${conv.adminUnreadCount} of your messages not yet seen by user`}
                style={{ color: '#0ea5e9', background: '#e0f2fe', borderColor: '#bae6fd' }}
                className="flex items-center gap-0.5 text-[9px] font-bold px-1.5 py-0.5 rounded-full border"
              >
                <span style={{ letterSpacing: '-1px', fontSize: '11px' }}>✓✓</span>
                {conv.adminUnreadCount}
              </span>
            )}
            {conv.assignedAdminUsername && (
              <span className="text-[9px] text-purple-500 font-semibold truncate max-w-[60px]" title={`Assigned: ${conv.assignedAdminUsername}`}>
                <i className="fas fa-user-shield text-[8px] mr-0.5"></i>{conv.assignedAdminUsername}
              </span>
            )}
          </div>
        </div>
      </div>
    );
  };

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

  const canEditMsg = (msg: ChatMessage) =>
    !msg.userMessage && !msg.isDeleted &&
    (Date.now() - new Date(msg.timestamp).getTime()) < 15 * 60 * 1000;

  const canDeleteMsg = (msg: ChatMessage) =>
    !msg.userMessage && !msg.isDeleted &&
    (Date.now() - new Date(msg.timestamp).getTime()) < 60 * 60 * 1000;

  const handleEditSave = async (msg: ChatMessage) => {
    if (!editContent.trim()) return;
    await editAdminMessage(msg.id, editContent);
    setEditingMessageId(null);
  };

  const handleDeleteMsg = async (msg: ChatMessage) => {
    await deleteAdminMessage(msg.id);
  };

  const renderMessage = (msg: ChatMessage) => (
    <div key={msg.id} className={`flex flex-col ${msg.userMessage ? 'items-start' : 'items-end'}`}>
      {/* Sender label — admin sees real usernames; user-facing widget always shows "Support Team" */}
      {!msg.isDeleted && (
        <span className={`text-[10px] font-semibold mb-1 px-1 flex items-center gap-1 ${msg.userMessage ? 'text-gray-400' : 'text-purple-500'}`}>
          {msg.userMessage
            ? <><i className="fas fa-user text-[8px]"></i>{activeAccount?.username || 'User'}</>
            : <><i className="fas fa-user-shield text-[8px]"></i>{msg.senderUsername || 'Support Team'}</>
          }
        </span>
      )}
      <div className={`max-w-[75%] rounded-2xl px-5 py-3 shadow-sm text-sm relative group ${msg.userMessage
        ? 'bg-white text-gray-800 rounded-tl-none border border-gray-200'
        : msg.isDeleted
          ? 'bg-gray-100 text-gray-400 rounded-tr-none border border-gray-200'
          : 'bg-gradient-to-r from-indigo-600 to-indigo-700 text-white rounded-tr-none shadow-indigo-200'}`}
      >
        {/* Deleted message */}
        {msg.isDeleted ? (
          <p className="leading-relaxed italic text-sm flex items-center gap-1.5">
            <i className="fas fa-ban text-[11px]"></i> This message was deleted
          </p>
        ) : editingMessageId === msg.id ? (
          /* Inline edit mode */
          <div className="flex flex-col gap-2 min-w-[200px]">
            <textarea
              value={editContent}
              onChange={e => setEditContent(e.target.value)}
              className="w-full rounded-lg border border-indigo-300 p-2 text-sm text-gray-800 resize-none focus:outline-none focus:ring-2 focus:ring-indigo-400"
              rows={3}
              autoFocus
            />
            <div className="flex gap-2 justify-end">
              <button
                onClick={() => setEditingMessageId(null)}
                className="text-xs px-3 py-1 rounded-lg bg-white/20 hover:bg-white/30 text-white border border-white/30"
              >Cancel</button>
              <button
                onClick={() => handleEditSave(msg)}
                className="text-xs px-3 py-1 rounded-lg bg-white text-indigo-700 font-semibold hover:bg-indigo-50"
              >Save</button>
            </div>
          </div>
        ) : (
          <>
            {/* Copy + Edit + Delete buttons on hover (admin messages only) */}
            <div className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity flex items-center gap-1">
              {msg.content && (
                <button
                  onClick={() => copyMessage(msg.content, msg.id)}
                  className="p-1 rounded"
                  title={copiedMessageId === msg.id ? 'Copied!' : 'Copy'}
                >
                  <i className={`fas ${copiedMessageId === msg.id ? 'fa-check' : 'fa-copy'} text-[10px] ${
                    msg.userMessage
                      ? (copiedMessageId === msg.id ? 'text-green-500' : 'text-gray-300')
                      : (copiedMessageId === msg.id ? 'text-green-300' : 'text-white/50')
                  }`}></i>
                </button>
              )}
              {canEditMsg(msg) && (
                <button
                  onClick={() => { setEditingMessageId(msg.id); setEditContent(msg.content); }}
                  className="p-1 rounded"
                  title="Edit (within 15 min)"
                >
                  <i className="fas fa-pencil-alt text-[10px] text-white/50 hover:text-white"></i>
                </button>
              )}
              {canDeleteMsg(msg) && (
                <button
                  onClick={() => handleDeleteMsg(msg)}
                  className="p-1 rounded"
                  title="Delete (within 60 min)"
                >
                  <i className="fas fa-trash text-[10px] text-white/50 hover:text-red-300"></i>
                </button>
              )}
            </div>
            {msg.content && <p className="leading-relaxed whitespace-pre-wrap pr-16">{msg.content}</p>}
            {renderAttachment(msg)}
          </>
        )}
        {/* Timestamp + edited label + read ticks */}
        {!msg.isDeleted && editingMessageId !== msg.id && (
          <span
            className={`text-[10px] mt-1.5 flex items-center gap-1 opacity-70 font-medium ${msg.userMessage ? 'text-gray-400' : 'text-indigo-100 justify-end'}`}
            title={formatTime(msg.timestamp)}
          >
            {formatTime(msg.timestamp)}
            {msg.isEdited && <span className="ml-1 italic opacity-80">· Edited</span>}
            {/* Read receipt ticks — only on outgoing admin messages */}
            {!msg.userMessage && (
              <span
                title={msg.status === 'READ' ? 'Seen by user' : 'Delivered, not yet seen'}
                style={{
                  letterSpacing: '-1px', fontSize: '12px', marginLeft: '1px', opacity: 1,
                  color: msg.status === 'READ' ? '#38bdf8' : 'rgba(255,255,255,0.45)',
                  fontWeight: msg.status === 'READ' ? 700 : 400,
                }}
              >
                ✓✓
              </span>
            )}
          </span>
        )}
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
            <div className="bg-white/80 border-b border-gray-100 px-5 py-2.5 flex items-center justify-between flex-shrink-0 backdrop-blur-sm gap-3">
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-9 h-9 rounded-xl flex items-center justify-center bg-indigo-100 text-indigo-600 text-sm shadow-sm flex-shrink-0">
                  <i className="fas fa-user-circle"></i>
                </div>
                <div className="min-w-0">
                  <h2 className="font-bold text-gray-800 text-sm leading-tight truncate">
                    {activeAccount.username || activeAccount.senderUsername || 'Unknown User'}
                  </h2>
                  <div className="flex items-center gap-2 mt-0.5 flex-wrap">
                    {messages.length > 0 && (
                      <span className="text-[10px] text-indigo-400 font-semibold">{messages.length} messages</span>
                    )}
                    {activeAccount.assignedAdminUsername && (
                      <span className="text-[10px] text-purple-500 font-semibold flex items-center gap-1">
                        <i className="fas fa-user-shield text-[9px]"></i>
                        {activeAccount.assignedAdminUsername}
                      </span>
                    )}
                  </div>
                </div>
              </div>

              {/* Right side: email + refresh */}
              <div className="flex items-center gap-2 flex-shrink-0">
                {/* Send Email button */}
                <button
                  onClick={openEmailModal}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 bg-purple-50 border border-purple-200 text-purple-700 hover:bg-purple-100 rounded-lg text-[11px] font-bold transition-all"
                  title="Send chat thread as email"
                >
                  <i className="fas fa-envelope text-[10px]"></i>
                  Email Thread
                </button>

                <button onClick={() => refreshChat()} className="text-gray-400 hover:text-indigo-600 transition p-2 rounded-full hover:bg-gray-100" title="Refresh">
                  <i className="fas fa-sync-alt text-sm"></i>
                </button>
              </div>
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
      {showNewChatModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-xl mx-4 overflow-hidden flex flex-col max-h-[92vh]">

            {/* Modal Header */}
            <div className="px-6 py-4 border-b border-gray-100 flex items-center justify-between bg-gradient-to-r from-indigo-600 to-purple-600 flex-shrink-0">
              <div>
                <h3 className="text-base font-bold text-white flex items-center gap-2">
                  <i className="fas fa-comment-dots"></i> Start New Conversation
                </h3>
                <p className="text-xs text-indigo-200 mt-0.5">Select client → profile → send your message</p>
              </div>
              <p className="text-xs text-gray-500 mt-0.5">Select a client account and send your first message</p>
            </div>

            {/* Step breadcrumb */}
            <div className="flex items-center gap-0 px-6 py-3 bg-indigo-50 border-b border-indigo-100 flex-shrink-0">
              {[
                { label: 'Client', icon: 'fa-building', done: !!selectedClient },
                { label: 'Profile', icon: 'fa-user', done: !!selectedProfile },
                { label: 'Message', icon: 'fa-paper-plane', done: !!newChatMessage.trim() },
              ].map((step, i) => (
                <React.Fragment key={step.label}>
                  <div className={`flex items-center gap-1.5 px-2 py-1 rounded-lg text-xs font-semibold transition-colors
                    ${step.done ? 'text-indigo-700 bg-indigo-100' : 'text-gray-400'}`}>
                    <i className={`fas ${step.icon} text-[10px]`}></i>
                    <span>{step.label}</span>
                    {step.done && <i className="fas fa-check text-[9px] text-indigo-500"></i>}
                  </div>
                  {i < 2 && <i className="fas fa-chevron-right text-[9px] text-gray-300 mx-1"></i>}
                </React.Fragment>
              ))}
            </div>

            <div className="flex flex-col flex-1 overflow-y-auto p-5 gap-4 custom-scrollbar">

              {/* Error banner */}
              {modalError && (
                <div className="flex items-start gap-2 px-4 py-3 bg-red-50 border border-red-200 rounded-xl text-sm text-red-700 flex-shrink-0">
                  <i className="fas fa-exclamation-circle mt-0.5 flex-shrink-0"></i>
                  <span>{modalError}</span>
                </div>
              )}

              {/* ── Step 1: Select Client ── */}
              <div className="border border-gray-200 rounded-xl overflow-hidden">
                <div className={`flex items-center gap-2 px-4 py-2.5 border-b border-gray-100 ${selectedClient ? 'bg-indigo-50' : 'bg-gray-50'}`}>
                  <span className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold text-white flex-shrink-0 ${selectedClient ? 'bg-indigo-600' : 'bg-gray-300'}`}>1</span>
                  <span className="text-xs font-bold text-gray-700 uppercase tracking-wide">Select Client</span>
                  {selectedClient && (
                    <span className="ml-auto flex items-center gap-1.5 text-xs font-semibold text-indigo-700 bg-indigo-100 px-2 py-0.5 rounded-full">
                      <i className="fas fa-building text-[9px]"></i>{selectedClient.name}
                      <button onClick={() => { setSelectedClient(null); setSelectedProfile(null); }}
                        className="text-indigo-400 hover:text-indigo-700 ml-0.5">
                        <i className="fas fa-times text-[9px]"></i>
                      </button>
                    </span>
                  )}
                </div>
                {!selectedClient && (
                  <div className="p-3">
                    <div className="relative mb-2">
                      <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
                      <input type="text" value={clientSearch} onChange={e => setClientSearch(e.target.value)}
                        placeholder="Search clients..." autoFocus
                        className="w-full pl-8 pr-4 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/50 focus:border-indigo-500"/>
                    </div>
                    <div className="max-h-36 overflow-y-auto custom-scrollbar">
                      {loadingClients ? (
                        <div className="flex items-center justify-center py-4 text-gray-400 text-xs">
                          <i className="fas fa-circle-notch fa-spin mr-2"></i>Loading clients...
                        </div>
                      ) : filteredClients.length === 0 ? (
                        <div className="flex items-center justify-center py-4 text-gray-400 text-xs">
                          <i className="fas fa-building mr-1.5"></i>
                          {clients.length === 0 ? 'No clients available' : 'No clients match your search'}
                        </div>
                      ) : filteredClients.map(client => (
                        <button key={client.id} onClick={() => handleSelectClient(client)}
                          className="w-full text-left px-3 py-2.5 flex items-center gap-3 hover:bg-indigo-50 transition-colors rounded-lg mb-0.5">
                          <span className="w-7 h-7 rounded-lg bg-indigo-100 flex items-center justify-center text-indigo-600 text-xs flex-shrink-0">
                            <i className="fas fa-building"></i>
                          </span>
                          <span className="text-sm font-semibold text-gray-800 truncate">{client.name}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>

              {/* ── Step 2: Select Profile ── */}
              {selectedClient && (
                <div className="border border-gray-200 rounded-xl overflow-hidden">
                  <div className={`flex items-center gap-2 px-4 py-2.5 border-b border-gray-100 ${selectedProfile ? 'bg-indigo-50' : 'bg-gray-50'}`}>
                    <span className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold text-white flex-shrink-0 ${selectedProfile ? 'bg-indigo-600' : 'bg-gray-300'}`}>2</span>
                    <span className="text-xs font-bold text-gray-700 uppercase tracking-wide">Select Profile</span>
                    {selectedProfile && (
                      <span className="ml-auto flex items-center gap-1.5 text-xs font-semibold text-indigo-700 bg-indigo-100 px-2 py-0.5 rounded-full">
                        <i className="fas fa-user text-[9px]"></i>{selectedProfile.username}
                        <button onClick={() => setSelectedProfile(null)}
                          className="text-indigo-400 hover:text-indigo-700 ml-0.5">
                          <i className="fas fa-times text-[9px]"></i>
                        </button>
                      </span>
                    )}
                  </div>
                  {!selectedProfile && (
                    <div className="p-3">
                      <div className="relative mb-2">
                        <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
                        <input type="text" value={profileSearch} onChange={e => setProfileSearch(e.target.value)}
                          placeholder="Search profiles..."
                          className="w-full pl-8 pr-4 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500/50 focus:border-indigo-500"/>
                      </div>
                      <div className="max-h-36 overflow-y-auto custom-scrollbar">
                        {loadingProfiles ? (
                          <div className="flex items-center justify-center py-4 text-gray-400 text-xs">
                            <i className="fas fa-circle-notch fa-spin mr-2"></i>Loading profiles...
                          </div>
                        ) : filteredProfiles.length === 0 ? (
                          <div className="flex items-center justify-center py-4 text-gray-400 text-xs">
                            <i className="fas fa-user mr-1.5"></i>
                            {clientProfiles.length === 0 ? 'No profiles found for this client' : 'No profiles match your search'}
                          </div>
                        ) : filteredProfiles.map(profile => (
                          <button key={profile.id} onClick={() => handleSelectProfile(profile)}
                            className="w-full text-left px-3 py-2.5 flex items-center gap-3 hover:bg-indigo-50 transition-colors rounded-lg mb-0.5">
                            <span className="w-7 h-7 rounded-lg bg-purple-100 flex items-center justify-center text-purple-600 text-xs flex-shrink-0">
                              <i className="fas fa-user"></i>
                            </span>
                            <div className="flex-1 min-w-0">
                              <p className="text-sm font-semibold text-gray-800 truncate">{profile.username}</p>
                              {profile.email && <p className="text-[10px] text-gray-400 truncate">{profile.email}</p>}
                            </div>
                            <span className="text-[10px] text-gray-400 flex-shrink-0">
                              {profile.accounts.length} account{profile.accounts.length !== 1 ? 's' : ''}
                            </span>
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* ── Account badges (info only — shown once profile is selected) ── */}
              {selectedProfile && selectedProfile.accounts.length > 0 && (
                <div className="px-1">
                  <p className="text-[10px] font-semibold text-gray-500 uppercase tracking-wide mb-1.5 flex items-center gap-1">
                    <i className="fas fa-cloud text-gray-400"></i> Accounts accessible by {selectedProfile.username}
                  </p>
                  <div className="flex flex-wrap gap-1.5">
                    {selectedProfile.accounts.map(a => (
                      <span key={a.accountId} className="inline-flex items-center gap-1 text-[10px] bg-gray-100 text-gray-600 px-2 py-0.5 rounded-full font-mono border border-gray-200">
                        <span className={`w-2 h-2 rounded-full inline-block ${getProviderClasses(a.provider)}`}></span>
                        {a.accountName}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {/* ── Step 3: Message ── */}
              {selectedProfile && (
                <div>
                  <label className="block text-xs font-bold text-gray-600 uppercase tracking-wide mb-2">
                    Initial Message
                  </label>
                  <textarea
                    value={newChatMessage}
                    onChange={e => setNewChatMessage(e.target.value)}
                    onKeyDown={handleNewChatKeyDown}
                    placeholder="Type your message... (Ctrl+Enter to send)"
                    rows={3}
                    className="w-full border border-gray-200 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 bg-gray-50 focus:bg-white transition-all resize-none"
                    disabled={sending}
                    autoFocus
                  />
                  <p className="text-[10px] text-gray-400 mt-1">Ctrl+Enter to send</p>
                </div>
              )}

              {/* ── Auto-ping settings ── */}
              {selectedProfile && (
                <div className={`border rounded-xl p-4 transition-colors ${autoPingEnabled ? 'border-amber-200 bg-amber-50' : 'border-gray-200 bg-gray-50'}`}>
                  <label className="flex items-center gap-3 cursor-pointer select-none">
                    <div
                      onClick={() => setAutoPingEnabled(v => !v)}
                      className={`relative w-10 h-5 rounded-full transition-colors flex-shrink-0 ${autoPingEnabled ? 'bg-amber-500' : 'bg-gray-300'}`}
                    >
                      <span className={`absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${autoPingEnabled ? 'translate-x-5' : ''}`}></span>
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-gray-800">Auto-ping if no response in 1 working hour</p>
                      <p className="text-[10px] text-gray-500 mt-0.5">Sends a reminder to the client via selected channels</p>
                    </div>
                  </label>
                  {autoPingEnabled && (
                    <div className="mt-3 pt-3 border-t border-amber-200 flex items-center gap-4">
                      <span className="text-xs font-semibold text-gray-600 mr-1">Notify via:</span>
                      {/* WhatsApp — coming soon */}
                      <label className="flex items-center gap-1.5 select-none opacity-40 cursor-not-allowed" title="Coming soon">
                        <input type="checkbox" disabled className="w-3.5 h-3.5"/>
                        <i className="fab fa-whatsapp text-green-600 text-sm"></i>
                        <span className="text-xs font-medium text-gray-500">WhatsApp</span>
                        <span className="text-[9px] text-gray-400 italic">soon</span>
                      </label>
                      {/* SMS — coming soon */}
                      <label className="flex items-center gap-1.5 select-none opacity-40 cursor-not-allowed" title="Coming soon">
                        <input type="checkbox" disabled className="w-3.5 h-3.5"/>
                        <i className="fas fa-sms text-blue-600 text-sm"></i>
                        <span className="text-xs font-medium text-gray-500">SMS</span>
                        <span className="text-[9px] text-gray-400 italic">soon</span>
                      </label>
                      {/* Email — active */}
                      <label className="flex items-center gap-1.5 cursor-pointer select-none">
                        <input type="checkbox" checked={pingEmail} onChange={e => setPingEmail(e.target.checked)}
                          className="w-3.5 h-3.5 accent-indigo-600"/>
                        <i className="fas fa-envelope text-purple-600 text-sm"></i>
                        <span className="text-xs font-medium text-gray-700">Email</span>
                      </label>
                    </div>
                  )}
                </div>
              )}
            </div>

            {/* Modal Footer */}
            <div className="px-6 py-4 border-t border-gray-100 flex items-center justify-end gap-3 bg-gray-50/50 flex-shrink-0">
              <button onClick={closeNewChatModal}
                className="px-4 py-2 text-sm font-semibold text-gray-600 hover:text-gray-800 border border-gray-200 rounded-xl hover:bg-gray-100 transition-all">
                Cancel
              </button>
              <button
                onClick={handleStartConversation}
                disabled={!selectedProfile || !newChatMessage.trim() || sending}
                className="flex items-center gap-2 px-5 py-2 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 text-white text-sm font-bold rounded-xl shadow-md transition-all active:scale-95 disabled:opacity-50 disabled:cursor-not-allowed disabled:active:scale-100"
              >
                {sending ? (
                  <><i className="fas fa-circle-notch fa-spin text-xs"></i><span>Starting...</span></>
                ) : (
                  <><i className="fas fa-paper-plane text-xs"></i><span>Start Conversation</span></>
                )}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ─── Email Thread Modal ─── */}
      {showEmailModal && activeAccount && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden flex flex-col max-h-[90vh]" onClick={e => e.stopPropagation()}>

            {/* Header */}
            <div className="px-6 py-4 border-b border-gray-100 flex items-center justify-between bg-gradient-to-r from-purple-600 to-indigo-600 flex-shrink-0">
              <div>
                <h3 className="text-base font-bold text-white flex items-center gap-2">
                  <i className="fas fa-envelope"></i> Send Chat Thread via Email
                </h3>
                <p className="text-xs text-purple-200 mt-0.5">Send the full conversation to any email address</p>
              </div>
              <button onClick={closeEmailModal} className="text-white/70 hover:text-white transition p-1.5 rounded-full hover:bg-white/10">
                <i className="fas fa-times text-sm"></i>
              </button>
            </div>

            <div className="p-6 flex flex-col gap-4 flex-1 overflow-y-auto custom-scrollbar">
              {/* Result banner */}
              {emailResult && (
                <div className={`flex items-center gap-2 px-4 py-3 rounded-xl text-sm font-semibold ${emailResult.success ? 'bg-green-50 border border-green-200 text-green-700' : 'bg-red-50 border border-red-200 text-red-700'}`}>
                  <i className={`fas ${emailResult.success ? 'fa-check-circle' : 'fa-exclamation-circle'}`}></i>
                  {emailResult.success ? 'Email sent successfully!' : emailResult.error}
                </div>
              )}

              {/* Chip-based recipients input */}
              <div>
                <label className="block text-[10px] font-black text-gray-500 mb-1.5 uppercase tracking-widest">
                  Recipients <span className="text-purple-500 font-medium normal-case tracking-normal">(blank → customer's registered email)</span>
                </label>
                <div className="min-h-[52px] border border-gray-200 rounded-xl px-3 py-2 flex flex-wrap gap-1.5 items-center focus-within:ring-2 focus-within:ring-purple-400 focus-within:border-purple-400 transition-all bg-white cursor-text">
                  {emailRecipients.map(email => (
                    <span key={email} className="flex items-center gap-1 pl-2.5 pr-1 py-0.5 bg-purple-100 text-purple-800 rounded-full text-[11px] font-bold border border-purple-200 flex-shrink-0">
                      {email}
                      <button type="button" onClick={() => removeEmailRecipient(email)} className="w-4 h-4 flex items-center justify-center rounded-full hover:bg-purple-200 transition-colors ml-0.5 text-purple-500">
                        <i className="fas fa-times text-[7px]"></i>
                      </button>
                    </span>
                  ))}
                  <input
                    type="text"
                    value={emailInput}
                    onChange={e => setEmailInput(e.target.value)}
                    onKeyDown={handleEmailInputKeyDown}
                    onBlur={() => emailInput.trim() && addEmailRecipient(emailInput)}
                    placeholder={emailRecipients.length === 0 ? "Type email and press Enter, or leave blank..." : "Add more..."}
                    className="flex-1 min-w-[180px] outline-none text-sm text-gray-700 placeholder-gray-300 bg-transparent py-0.5"
                  />
                </div>
                <p className="mt-1 text-[10px] text-gray-400">Press Enter or comma to add · Backspace to remove last</p>
              </div>

              {/* Quick Add — conversation user */}
              {activeAccount?.email && (
                <div>
                  <label className="block text-[10px] font-black text-gray-500 mb-1.5 uppercase tracking-widest">Quick Add</label>
                  <div className="border border-gray-100 rounded-xl overflow-hidden">
                    <div className="flex items-center justify-between px-3.5 py-3 bg-white hover:bg-gray-50 transition-colors">
                      <div className="flex items-center gap-2.5 min-w-0">
                        <div className="w-7 h-7 rounded-full bg-indigo-100 text-indigo-600 flex items-center justify-center text-[10px] font-black flex-shrink-0">
                          {(activeAccount.username || 'U').charAt(0).toUpperCase()}
                        </div>
                        <div className="min-w-0">
                          <div className="flex items-center gap-1.5">
                            <p className="text-[11px] font-bold text-gray-700 leading-none">{activeAccount.username}</p>
                            <span className="px-1.5 py-0.5 text-[8px] font-black bg-indigo-100 text-indigo-700 rounded-full uppercase tracking-widest border border-indigo-200">Customer</span>
                          </div>
                          <p className="text-[10px] text-gray-400 mt-0.5 truncate">{activeAccount.email}</p>
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={() => addEmailRecipient(activeAccount.email!)}
                        disabled={emailRecipients.includes(activeAccount.email!.toLowerCase())}
                        className="flex items-center gap-1 px-3 py-1.5 text-[10px] font-black rounded-lg border transition-all disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0 ml-3 bg-white text-purple-600 border-purple-200 hover:bg-purple-50 active:scale-95"
                      >
                        {emailRecipients.includes(activeAccount.email.toLowerCase())
                          ? <><i className="fas fa-check text-emerald-500"></i> Added</>
                          : <><i className="fas fa-plus"></i> Add</>
                        }
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* Subject */}
              <div>
                <label className="block text-[10px] font-black text-gray-500 mb-1.5 uppercase tracking-widest">Subject</label>
                <input
                  type="text"
                  value={emailSubject}
                  onChange={e => setEmailSubject(e.target.value)}
                  className="w-full border border-gray-200 rounded-xl px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-purple-500 bg-gray-50 focus:bg-white transition-all"
                />
              </div>

              {/* Send mode toggle */}
              <div>
                <label className="block text-[10px] font-black text-gray-500 mb-1.5 uppercase tracking-widest">Send Mode</label>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setEmailSendMode('separate')}
                    className={`flex-1 flex items-center gap-2.5 px-4 py-3 rounded-xl border text-left transition-all ${
                      emailSendMode === 'separate'
                        ? 'bg-purple-50 border-purple-400 text-purple-800 shadow-sm'
                        : 'bg-white border-gray-200 text-gray-500 hover:border-gray-300'
                    }`}
                  >
                    <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${emailSendMode === 'separate' ? 'border-purple-500' : 'border-gray-300'}`}>
                      {emailSendMode === 'separate' && <div className="w-2 h-2 rounded-full bg-purple-500"></div>}
                    </div>
                    <div>
                      <div className="text-[11px] font-black uppercase tracking-widest leading-none">Separate</div>
                      <div className="text-[9px] font-medium text-gray-400 mt-0.5">One email per recipient</div>
                    </div>
                  </button>
                  <button
                    type="button"
                    onClick={() => setEmailSendMode('combined')}
                    className={`flex-1 flex items-center gap-2.5 px-4 py-3 rounded-xl border text-left transition-all ${
                      emailSendMode === 'combined'
                        ? 'bg-purple-50 border-purple-400 text-purple-800 shadow-sm'
                        : 'bg-white border-gray-200 text-gray-500 hover:border-gray-300'
                    }`}
                  >
                    <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${emailSendMode === 'combined' ? 'border-purple-500' : 'border-gray-300'}`}>
                      {emailSendMode === 'combined' && <div className="w-2 h-2 rounded-full bg-purple-500"></div>}
                    </div>
                    <div>
                      <div className="text-[11px] font-black uppercase tracking-widest leading-none">Combined</div>
                      <div className="text-[9px] font-medium text-gray-400 mt-0.5">All in one email (CC visible)</div>
                    </div>
                  </button>
                </div>
              </div>

              {/* Thread preview */}
              <div>
                <p className="text-[10px] font-black text-gray-500 uppercase tracking-widest mb-1.5">Thread Preview</p>
                <div className="border border-gray-200 rounded-xl overflow-hidden max-h-40 overflow-y-auto custom-scrollbar bg-gray-50 p-3 space-y-2">
                  {messages.filter(m => !m.isDeleted).map(m => (
                    <div key={m.id} className={`flex ${m.userMessage ? 'justify-start' : 'justify-end'}`}>
                      <div className={`max-w-[80%] rounded-xl px-3 py-2 text-xs ${m.userMessage ? 'bg-white border border-gray-200 text-gray-700' : 'bg-indigo-100 text-indigo-800'}`}>
                        <p className={`text-[9px] font-bold mb-0.5 ${m.userMessage ? 'text-gray-400' : 'text-indigo-500'}`}>
                          {m.userMessage ? (activeAccount.username || 'User') : (m.senderUsername || 'Support Team')}
                        </p>
                        <p className="leading-snug">{m.content}</p>
                      </div>
                    </div>
                  ))}
                  {messages.filter(m => !m.isDeleted).length === 0 && (
                    <p className="text-center text-xs text-gray-400 py-4">No messages to preview</p>
                  )}
                </div>
              </div>
            </div>

            {/* Footer */}
            <div className="px-6 py-4 border-t border-gray-100 flex items-center justify-between bg-gray-50/50 flex-shrink-0">
              <span className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">
                {emailRecipients.length + (emailInput.trim() ? 1 : 0) > 0
                  ? `${emailRecipients.length + (emailInput.trim() ? 1 : 0)} recipient${(emailRecipients.length + (emailInput.trim() ? 1 : 0)) !== 1 ? 's' : ''}`
                  : "Customer's email (fallback)"
                }
              </span>
              <div className="flex gap-3">
                <button onClick={closeEmailModal} className="px-4 py-2 text-sm font-semibold text-gray-600 hover:text-gray-800 border border-gray-200 rounded-xl hover:bg-gray-100 transition-all">
                  Cancel
                </button>
                <button
                  onClick={handleSendEmail}
                  disabled={sendingEmail || !!emailResult?.success}
                  className="flex items-center gap-2 px-5 py-2 bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-700 hover:to-indigo-700 text-white text-sm font-bold rounded-xl shadow-md transition-all active:scale-95 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {sendingEmail ? (
                    <><i className="fas fa-circle-notch fa-spin text-xs"></i><span>Sending...</span></>
                  ) : emailResult?.success ? (
                    <><i className="fas fa-check text-xs"></i><span>Sent!</span></>
                  ) : (
                    <><i className="fas fa-paper-plane text-xs"></i><span>Send Email</span></>
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

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
