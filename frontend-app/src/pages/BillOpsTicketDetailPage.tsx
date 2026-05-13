/**
 * BillOps Ticket Detail Page Component
 *
 * Advanced ticket detail view with:
 * - IST timezone on all timestamps
 * - Internal admin notes (amber, lock icon, hidden from customers)
 * - Ticket assignment to admins
 * - SLA status card in header
 * - RCA generation (admin only)
 * - Rich text replies with file attachments
 */

import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import ReactDOM from 'react-dom';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useBillOpsTicketDetail } from '../hooks/useBillOpsTicketDetail';
import { RCAFormData, RCAFormSection, TicketReply } from '../types/billopsTickets';
import { useTopbarActions } from '../contexts/TopbarContext';
import { formatIST, getSlaStatus, getResponseSlaLabel } from '../utils/timeUtils';
import Quill from 'quill';
import 'quill/dist/quill.snow.css';
import '../styles/billopsTicketDetail.css';
import { DialogRoot, DialogPanel } from '../components/ui';

// ── Note panel types & constants ──────────────────────────────────────────
interface NoteTask {
  id: string;
  text: string;
  done: boolean;
}

const NOTE_COLORS = {
  yellow: { bg: 'linear-gradient(160deg,#fef9c3 0%,#fef08a 100%)', line: 'rgba(202,138,4,0.12)', btn: '#fbbf24', text: '#713f12' },
  blue:   { bg: 'linear-gradient(160deg,#dbeafe 0%,#bfdbfe 100%)', line: 'rgba(59,130,246,0.12)',  btn: '#60a5fa', text: '#1e3a8a' },
  green:  { bg: 'linear-gradient(160deg,#dcfce7 0%,#bbf7d0 100%)', line: 'rgba(34,197,94,0.12)',   btn: '#4ade80', text: '#14532d' },
  pink:   { bg: 'linear-gradient(160deg,#fce7f3 0%,#fbcfe8 100%)', line: 'rgba(236,72,153,0.12)',  btn: '#f472b6', text: '#831843' },
} as const;

const BillOpsTicketDetailPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const ticketId = searchParams.get('id');
  const { setActions, clearActions } = useTopbarActions();

  const {
    ticket,
    currentUser,
    loading,
    isSubmitting,
    error,
    isAdmin,
    adminUsers,
    submitReply,
    closeTicket,
    reopenTicket,
    submitRCA,
    assignTicket,
    sendEmailThread,
    sendMeetingInvite,
    editReply,
    deleteReply
  } = useBillOpsTicketDetail(ticketId);

  // Quill editor
  const quillRef = useRef<HTMLDivElement>(null);
  const quillInstance = useRef<Quill | null>(null);
  const [newReplyHasContent, setNewReplyHasContent] = useState(false);

  // Edit / Delete state
  const [editingReplyId, setEditingReplyId] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');

  // Internal note toggle (kept for submit path only)
  const [isInternalNote, setIsInternalNote] = useState(false);

  // Sticky note side panel — core
  const [showNotePanel, setShowNotePanel] = useState(false);
  const [noteSaved, setNoteSaved] = useState(false);
  const noteSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Note panel — enhancements
  const [noteTab, setNoteTab] = useState<'note' | 'tasks'>('note');
  const [noteHtml, setNoteHtml] = useState('');
  const [noteWordCount, setNoteWordCount] = useState(0);
  const [noteTasks, setNoteTasks] = useState<NoteTask[]>([]);
  const [newTaskText, setNewTaskText] = useState('');
  const [noteColor, setNoteColor] = useState<keyof typeof NOTE_COLORS>('yellow');
  const [noteHistory, setNoteHistory] = useState<{ html: string; savedAt: number }[]>([]);
  const [showNoteHistory, setShowNoteHistory] = useState(false);
  const [noteWidth, setNoteWidth] = useState(() => {
    const saved = sessionStorage.getItem('xamops_note_panel_width');
    return saved ? Math.min(700, Math.max(280, parseInt(saved))) : 384;
  });
  const [isPostingNote, setIsPostingNote] = useState(false);
  const noteQuillRef = useRef<HTMLDivElement>(null);
  const noteQuillInstance = useRef<Quill | null>(null);
  const isDraggingRef = useRef(false);

  // File attachment
  const [attachment, setAttachment] = useState<File | null>(null);
  const [fileName, setFileName] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Assignment dropdown state
  const [showAssignDropdown, setShowAssignDropdown] = useState(false);
  const [isAssigning, setIsAssigning] = useState(false);

  // Email Thread Modal
  const [showEmailModal, setShowEmailModal] = useState(false);
  const [emailRecipients, setEmailRecipients] = useState<string[]>([]);
  const [emailInput, setEmailInput] = useState('');
  const [isSendingEmail, setIsSendingEmail] = useState(false);
  const [sendMode, setSendMode] = useState<'separate' | 'combined'>('separate');

  // Google Meet scheduling modal
  const [showMeetModal, setShowMeetModal] = useState(false);
  const [meetTitle, setMeetTitle] = useState('');
  const [meetDate, setMeetDate] = useState('');
  const [meetTime, setMeetTime] = useState('10:00');
  const [meetDuration, setMeetDuration] = useState('60');
  const [meetLink, setMeetLink] = useState('');
  const [meetEmailRecipients, setMeetEmailRecipients] = useState<string[]>([]);
  const [meetEmailInput, setMeetEmailInput] = useState('');
  const [isSendingMeetEmail, setIsSendingMeetEmail] = useState(false);
  const [isSharingMeet, setIsSharingMeet] = useState(false);

  // RCA Modal
  const [showRcaModal, setShowRcaModal] = useState(false);
  const [rcaSubmitting, setRcaSubmitting] = useState(false);
  const [rcaFormData, setRcaFormData] = useState<RCAFormData>({
    problemStatement: '',
    conclusion: '',
    incidentOverview: [{ heading: 'Incident Name', content: '' }],
    descriptionSections: [{ heading: 'Symptoms', content: '' }],
    rootCauseSections: [{ heading: 'Primary Cause', content: '' }],
    resolutionSections: [{ heading: 'Steps Taken', content: '' }],
    preventionSections: [{ heading: 'Action Item', content: '' }]
  });

  // Chat container ref for auto-scroll + scroll-to-bottom button
  const chatContainerRef = useRef<HTMLDivElement>(null);
  const [showScrollBtn, setShowScrollBtn] = useState(false);

  const getStatusBadgeClasses = (status: string) => {
    if (status === 'OPEN') return 'bg-emerald-500 text-white shadow-emerald-200 ring-4 ring-emerald-500/10';
    if (status === 'IN_PROGRESS') return 'bg-amber-500 text-white shadow-amber-200 ring-4 ring-amber-500/10';
    return 'bg-slate-400 text-white shadow-slate-200 ring-4 ring-slate-400/10';
  };

  const getInitials = useCallback((name: string): string => {
    if (!name) return 'U';
    return name.charAt(0).toUpperCase();
  }, []);

  const isMe = useCallback((reply: any): boolean => {
    // Position logic (mirrors support dashboard):
    // - Admin viewer: admin replies → RIGHT (with real username), customer replies → LEFT
    // - Customer viewer: own replies → RIGHT ("You"), admin replies → LEFT ("Support Team")
    if (isAdmin) return reply.admin === true;
    if (!currentUser?.id) return false;
    return !reply.admin && String(reply.authorId) === String(currentUser.id);
  }, [currentUser, isAdmin]);

  const handleCloseTicket = useCallback(async () => {
    if (globalThis.confirm('Are you sure you want to close this ticket?')) {
      try {
        await closeTicket();
      } catch (err) {
        globalThis.alert('Error closing ticket. Please try again.');
      }
    }
  }, [closeTicket]);

  const handleReopenTicket = useCallback(async () => {
    if (globalThis.confirm('Reopen this ticket?')) {
      try {
        await reopenTicket();
      } catch (err) {
        globalThis.alert('Error reopening ticket. Please try again.');
      }
    }
  }, [reopenTicket]);

  const goBackToTickets = useCallback(() => {
    if (isAdmin) {
      navigate('/billops-admin-tickets');
    } else {
      navigate('/billops-tickets');
    }
  }, [navigate, isAdmin]);

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      if (file.size > 10 * 1024 * 1024) {
        globalThis.alert('File is too large. Max size is 10MB.');
        return;
      }
      setAttachment(file);
      setFileName(file.name);
    }
  };

  const clearFile = useCallback(() => {
    setAttachment(null);
    setFileName('');
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, []);

  const handleAssign = async (adminId: number, adminUsername: string) => {
    setIsAssigning(true);
    try {
      await assignTicket(adminId, adminUsername);
      setShowAssignDropdown(false);
    } catch {
      globalThis.alert('Failed to assign ticket.');
    } finally {
      setIsAssigning(false);
    }
  };

  /**
   * Quill initialization — runs ONCE when ticket loads (not on isInternalNote change)
   * to prevent the double-toolbar bug caused by re-initializing on the same DOM node.
   */
  useEffect(() => {
    if (!quillRef.current || quillInstance.current || !ticket || ticket.status === 'CLOSED') return;

    quillInstance.current = new Quill(quillRef.current, {
      theme: 'snow',
      placeholder: 'Type your message here...',
      modules: {
        toolbar: [
          ['bold', 'italic', 'underline', 'strike'],
          ['blockquote', 'code-block'],
          [{ list: 'ordered' }, { list: 'bullet' }],
          ['clean']
        ]
      }
    });

    quillInstance.current.on('text-change', () => {
      if (quillInstance.current) {
        const text = quillInstance.current.getText().trim();
        setNewReplyHasContent(text.length > 0);
      }
    });

    // Intercept pasted/dropped images via Quill's clipboard module so they
    // become a proper file attachment instead of a base64 embed in the HTML.
    const Delta = Quill.import('delta') as any;
    const clipboard = quillInstance.current.getModule('clipboard') as any;
    clipboard.addMatcher('IMG', (node: HTMLImageElement) => {
      const src = node.getAttribute('src');
      if (src && src.startsWith('data:')) {
        fetch(src)
          .then(res => res.blob())
          .then(blob => {
            const file = new File([blob], 'pasted-image.png', { type: blob.type });
            if (file.size > 10 * 1024 * 1024) {
              globalThis.alert('Pasted image is too large. Max size is 10MB.');
              return;
            }
            setAttachment(file);
            setFileName('pasted-image.png');
          });
      }
      return new Delta(); // empty delta — block the image from entering the editor
    });

    return () => {
      // Clean up toolbar DOM so re-mounting doesn't produce a double toolbar
      if (quillRef.current) {
        const toolbar = quillRef.current.parentElement?.querySelector('.ql-toolbar');
        if (toolbar) toolbar.remove();
        quillRef.current.innerHTML = '';
        quillRef.current.className = '';
      }
      quillInstance.current = null;
    };
  }, [ticket]); // intentionally excludes isInternalNote

  // (placeholder effect removed — notes now use the separate sticky note panel)

  /**
   * Scroll to bottom when replies change
   */
  useEffect(() => {
    if (chatContainerRef.current && ticket) {
      setTimeout(() => {
        if (chatContainerRef.current) {
          chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
        }
      }, 100);
    }
  }, [ticket?.replies]);

  /**
   * Load all note data (HTML, tasks, color, history) from localStorage.
   * Migrates old plain-text notes to HTML automatically.
   */
  useEffect(() => {
    if (!ticketId) return;
    const oldText = localStorage.getItem(`xamops_ticket_note_${ticketId}`);
    const html = localStorage.getItem(`xamops_ticket_note_html_${ticketId}`)
      || (oldText ? `<p>${oldText.replace(/\n/g, '</p><p>')}</p>` : '');
    setNoteHtml(html);
    if (html) {
      const tmp = document.createElement('div');
      tmp.innerHTML = html;
      const text = (tmp.textContent || '').trim();
      setNoteWordCount(text ? text.split(/\s+/).filter(Boolean).length : 0);
    }
    try {
      const tasks = JSON.parse(localStorage.getItem(`xamops_ticket_tasks_${ticketId}`) || '[]');
      setNoteTasks(tasks);
    } catch { setNoteTasks([]); }
    const savedColor = localStorage.getItem(`xamops_ticket_note_color_${ticketId}`) as keyof typeof NOTE_COLORS | null;
    if (savedColor && NOTE_COLORS[savedColor]) setNoteColor(savedColor);
    try {
      const hist = JSON.parse(localStorage.getItem(`xamops_ticket_note_history_${ticketId}`) || '[]');
      setNoteHistory(hist);
    } catch { setNoteHistory([]); }
  }, [ticketId]);

  /** Keyboard shortcut: Ctrl/Cmd + Shift + N toggles note panel (admin only) */
  useEffect(() => {
    if (!isAdmin) return;
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === 'N') {
        e.preventDefault();
        setShowNotePanel(v => !v);
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [isAdmin]);

  /**
   * Initialize the note Quill editor when the note panel opens.
   * The editor div is always in the DOM while the panel is open (hidden via CSS on tasks tab),
   * so we only need to init/cleanup based on showNotePanel.
   */
  useEffect(() => {
    if (!showNotePanel) {
      noteQuillInstance.current = null;
      return;
    }
    const timer = setTimeout(() => {
      if (!noteQuillRef.current || noteQuillInstance.current) return;
      // Clean up any stale toolbar left from a previous session
      const stale = noteQuillRef.current.parentElement?.querySelector('.ql-toolbar');
      if (stale) stale.remove();
      noteQuillRef.current.innerHTML = '';
      noteQuillRef.current.className = '';

      noteQuillInstance.current = new Quill(noteQuillRef.current, {
        theme: 'snow',
        placeholder: 'Write your internal note here...',
        modules: {
          toolbar: [
            ['bold', 'italic', 'underline'],
            [{ list: 'ordered' }, { list: 'bullet' }],
            ['code-block', 'clean'],
          ],
        },
      });

      // Load saved content directly from storage to avoid stale-state timing issues
      const savedHtml = ticketId
        ? (localStorage.getItem(`xamops_ticket_note_html_${ticketId}`) || '')
        : '';
      if (savedHtml) noteQuillInstance.current.root.innerHTML = savedHtml;

      noteQuillInstance.current.on('text-change', () => {
        if (!noteQuillInstance.current) return;
        const html = noteQuillInstance.current.root.innerHTML;
        const text = noteQuillInstance.current.getText().trim();
        setNoteHtml(html);
        setNoteWordCount(text ? text.split(/\s+/).filter(Boolean).length : 0);
        if (noteSaveTimerRef.current) clearTimeout(noteSaveTimerRef.current);
        noteSaveTimerRef.current = setTimeout(() => {
          if (!ticketId) return;
          localStorage.setItem(`xamops_ticket_note_html_${ticketId}`, html);
          const entry = { html, savedAt: Date.now() };
          setNoteHistory(prev => {
            const updated = [entry, ...prev.slice(0, 2)];
            localStorage.setItem(`xamops_ticket_note_history_${ticketId}`, JSON.stringify(updated));
            return updated;
          });
          setNoteSaved(true);
          setTimeout(() => setNoteSaved(false), 2000);
        }, 500);
      });
    }, 50);
    return () => clearTimeout(timer);
  }, [showNotePanel, ticketId]);

  /**
   * Redirect if no ticket ID
   */
  useEffect(() => {
    if (!ticketId) navigate('/billops-tickets');
  }, [ticketId, navigate]);

  const openMeetModal = useCallback(() => {
    if (!ticket) return;
    setMeetTitle(ticket.subject);
    const nowUtc = Date.now();
    const istMs = nowUtc + 5.5 * 3600 * 1000;
    const istDate = new Date(istMs);
    const yyyy = istDate.getUTCFullYear();
    const mm = String(istDate.getUTCMonth() + 1).padStart(2, '0');
    const dd = String(istDate.getUTCDate()).padStart(2, '0');
    const h = istDate.getUTCHours();
    const mins = istDate.getUTCMinutes();
    const nextMins = mins < 30 ? 30 : 0;
    const nextH = mins < 30 ? h : (h + 1) % 24;
    setMeetDate(`${yyyy}-${mm}-${dd}`);
    setMeetTime(`${String(nextH).padStart(2, '0')}:${String(nextMins).padStart(2, '0')}`);
    setMeetDuration('60');
    setMeetLink('');
    setMeetEmailRecipients([]);
    setMeetEmailInput('');
    setShowMeetModal(true);
  }, [ticket]);

  const closeMeetModal = useCallback(() => {
    setShowMeetModal(false);
    setMeetTitle('');
    setMeetDate('');
    setMeetTime('10:00');
    setMeetDuration('60');
    setMeetLink('');
    setMeetEmailRecipients([]);
    setMeetEmailInput('');
  }, []);

  /**
   * Set Topbar Actions
   */
  useEffect(() => {
    if (!ticket) return;

    setActions(
      <div className="flex items-center gap-6">
        <div className="flex items-center gap-4 pr-6 border-r border-slate-200/50">
          <button
            onClick={goBackToTickets}
            className="w-9 h-9 flex items-center justify-center rounded-xl bg-slate-100 text-slate-500 hover:text-indigo-600 hover:bg-slate-200 transition-all font-bold flex-shrink-0"
            title="Back to List"
          >
            <i className="fas fa-arrow-left"></i>
          </button>
          <div className="min-w-0">
            <h1 className="text-sm font-bold text-slate-900 leading-none truncate max-w-[180px] lg:max-w-[360px]">
              {ticket.subject}
            </h1>
            <p className="text-[10px] text-slate-500 font-medium mt-1 truncate max-w-[180px] lg:max-w-[360px]">
              Case #{ticket.id} • {ticket.service}
            </p>
          </div>
        </div>

        <div className="flex items-center flex-wrap gap-2">
          <span className={`flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-[10px] font-bold uppercase tracking-wider shadow-sm transition-all ${getStatusBadgeClasses(ticket.status)}`}>
            <span className={`w-1.5 h-1.5 rounded-full bg-white shadow-[0_0_8px_white] ${ticket.status === 'OPEN' ? 'animate-pulse' : ''}`}></span>
            {ticket.status}
          </span>

          {/* Assign dropdown (admin only) */}
          {isAdmin && (
            <div className="relative" data-assign-dropdown>
              <button
                onClick={() => setShowAssignDropdown(v => !v)}
                disabled={isAssigning || ticket.status === 'CLOSED'}
                className={`flex items-center gap-2 px-3 py-2 rounded-xl text-[10px] font-black uppercase tracking-widest border transition-all ${
                  ticket.assignedToUsername
                    ? 'bg-indigo-50 text-indigo-700 border-indigo-200 hover:bg-indigo-100'
                    : 'bg-slate-50 text-slate-500 border-slate-200 hover:border-indigo-300 hover:text-indigo-600'
                } disabled:opacity-50 disabled:cursor-not-allowed`}
              >
                {ticket.assignedToUsername ? (
                  <>
                    <div className="w-4 h-4 rounded-full bg-indigo-600 flex items-center justify-center text-white text-[7px] font-black flex-shrink-0">
                      {ticket.assignedToUsername.charAt(0).toUpperCase()}
                    </div>
                    <span className="hidden xl:inline truncate max-w-[80px]">{ticket.assignedToUsername}</span>
                  </>
                ) : (
                  <><i className="fas fa-user-plus text-[10px]"></i><span className="hidden xl:inline"> Assign</span></>
                )}
                {!isAssigning && ticket.status !== 'CLOSED' && <i className="fas fa-chevron-down text-[8px] opacity-50 ml-1"></i>}
                {isAssigning && <i className="fas fa-circle-notch fa-spin text-[8px] ml-1"></i>}
              </button>

              {showAssignDropdown && adminUsers.length > 0 && (
                <div className="absolute top-full right-0 mt-1 w-48 bg-white rounded-xl shadow-xl border border-slate-100 py-1 z-50 overflow-hidden">
                  <div className="px-3 py-1.5 text-[9px] font-black text-slate-400 uppercase tracking-widest border-b border-slate-50">Assign to</div>
                  {adminUsers.map(admin => (
                    <button
                      key={admin.id}
                      onClick={() => handleAssign(admin.id, admin.username)}
                      className={`w-full text-left flex items-center gap-2 px-3 py-2 text-[11px] font-bold hover:bg-indigo-50 hover:text-indigo-700 transition-colors ${
                        ticket.assignedToId === admin.id ? 'bg-indigo-50 text-indigo-700' : 'text-slate-700'
                      }`}
                    >
                      <div className="w-5 h-5 rounded-full bg-indigo-600 flex items-center justify-center text-white text-[8px] font-black flex-shrink-0">
                        {admin.username.charAt(0).toUpperCase()}
                      </div>
                      {admin.username}
                      {ticket.assignedToId === admin.id && <i className="fas fa-check text-indigo-500 ml-auto text-[9px]"></i>}
                    </button>
                  ))}
                  {ticket.assignedToUsername && (
                    <button
                      onClick={() => handleAssign(0, '')}
                      className="w-full text-left flex items-center gap-2 px-3 py-2 text-[11px] font-bold text-rose-500 hover:bg-rose-50 transition-colors border-t border-slate-50"
                    >
                      <i className="fas fa-user-times text-[10px]"></i>
                      Unassign
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          {isAdmin && ticket.status !== 'CLOSED' && (
            <button
              onClick={() => setShowRcaModal(true)}
              title="Generate RCA"
              className="flex items-center gap-2 px-3 py-2 lg:px-4 bg-purple-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-purple-500/20 hover:bg-purple-700 transition-all hover:-translate-y-0.5"
            >
              <i className="fas fa-file-medical-alt"></i>
              <span className="hidden lg:inline">Generate RCA</span>
            </button>
          )}

          {isAdmin && ticket.status !== 'CLOSED' && (
            <button
              onClick={openMeetModal}
              title="Schedule Google Meet"
              className="flex items-center gap-2 px-3 py-2 lg:px-4 bg-emerald-50 text-emerald-700 rounded-xl text-xs font-bold border border-emerald-200 hover:bg-emerald-100 transition-all active:scale-95"
            >
              <i className="fas fa-video"></i>
              <span className="hidden lg:inline">Schedule Meet</span>
            </button>
          )}

          {isAdmin && (ticket.replies?.length ?? 0) > 0 && (
            <button
              onClick={() => setShowEmailModal(true)}
              title="Email Thread"
              className="flex items-center gap-2 px-3 py-2 lg:px-4 bg-sky-50 text-sky-700 rounded-xl text-xs font-bold border border-sky-200 hover:bg-sky-100 transition-all active:scale-95"
            >
              <i className="fas fa-envelope"></i>
              <span className="hidden lg:inline">Email Thread</span>
            </button>
          )}

          {ticket.status !== 'CLOSED' && (
            <button
              onClick={handleCloseTicket}
              title="Close Ticket"
              className="flex items-center gap-2 px-3 py-2 lg:px-4 bg-rose-50 text-rose-600 rounded-xl text-xs font-bold border border-rose-100 hover:bg-rose-100 transition-all active:scale-95"
            >
              <i className="far fa-times-circle"></i>
              <span className="hidden lg:inline">Close Ticket</span>
            </button>
          )}

          {isAdmin && ticket.status === 'CLOSED' && (
            <button
              onClick={handleReopenTicket}
              title="Reopen Ticket"
              className="flex items-center gap-2 px-3 py-2 lg:px-4 bg-emerald-50 text-emerald-700 rounded-xl text-xs font-bold border border-emerald-200 hover:bg-emerald-100 transition-all active:scale-95"
            >
              <i className="fas fa-lock-open"></i>
              <span className="hidden lg:inline">Reopen Ticket</span>
            </button>
          )}
        </div>
      </div>
    );

    return () => clearActions();
  }, [setActions, clearActions, ticket, isAdmin, handleCloseTicket, handleReopenTicket, goBackToTickets,
      adminUsers, showAssignDropdown, isAssigning, handleAssign, showEmailModal, openMeetModal]);

  const addEmailRecipient = (email: string) => {
    const trimmed = email.trim().toLowerCase();
    if (trimmed && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed) && !emailRecipients.includes(trimmed)) {
      setEmailRecipients(prev => [...prev, trimmed]);
    }
    setEmailInput('');
  };

  const removeEmailRecipient = (email: string) => {
    setEmailRecipients(prev => prev.filter(e => e !== email));
  };

  const handleEmailInputKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      addEmailRecipient(emailInput);
    } else if (e.key === 'Backspace' && !emailInput && emailRecipients.length > 0) {
      setEmailRecipients(prev => prev.slice(0, -1));
    }
  };

  const closeEmailModal = () => {
    setShowEmailModal(false);
    setEmailRecipients([]);
    setEmailInput('');
    setSendMode('separate');
  };

  const handleSendEmail = async () => {
    const allRecipients = emailInput.trim()
      ? [...emailRecipients, emailInput.trim()]
      : emailRecipients;
    if (allRecipients.length === 0) {
      globalThis.alert('Please add at least one recipient email address.');
      return;
    }
    setIsSendingEmail(true);
    try {
      await sendEmailThread(allRecipients, sendMode);
      const modeLabel = sendMode === 'combined' ? 'one combined email' : `${allRecipients.length} separate email${allRecipients.length > 1 ? 's' : ''}`;
      globalThis.alert(`Sent as ${modeLabel}!`);
      closeEmailModal();
    } catch {
      globalThis.alert('Failed to send email. Please try again.');
    } finally {
      setIsSendingEmail(false);
    }
  };

  // ── Google Meet helpers ──────────────────────────────────────────────────

  const generateGoogleCalendarUrl = useCallback(() => {
    if (!meetDate || !meetTime) return 'https://calendar.google.com';
    const [year, month, day] = meetDate.split('-').map(Number);
    const [hours, minutes] = meetTime.split(':').map(Number);
    // IST = UTC+5:30 — convert to UTC for Google Calendar
    const istMs = Date.UTC(year, month - 1, day, hours, minutes);
    const utcMs = istMs - (5 * 60 + 30) * 60 * 1000;
    const endMs = utcMs + parseInt(meetDuration) * 60 * 1000;
    const fmt = (ms: number) =>
      new Date(ms).toISOString().replace(/[-:]/g, '').replace(/\.\d+/, '');
    const title = encodeURIComponent(
      meetTitle || `Support Meeting — Ticket #${ticket?.id}`
    );
    const desc = encodeURIComponent(
      `Meeting regarding support ticket #${ticket?.id}: ${ticket?.subject}`
    );
    return (
      `https://calendar.google.com/calendar/render?action=TEMPLATE` +
      `&text=${title}&dates=${fmt(utcMs)}/${fmt(endMs)}` +
      `&details=${desc}&ctz=Asia%2FKolkata`
    );
  }, [meetDate, meetTime, meetDuration, meetTitle, ticket]);

  const handleShareMeetInChat = useCallback(async () => {
    if (!meetLink.trim()) {
      globalThis.alert('Please paste the Google Meet link first.');
      return;
    }
    const [year, month, day] = meetDate.split('-').map(Number);
    const [hours, minutes] = meetTime.split(':').map(Number);
    const dispDate = new Date(year, month - 1, day).toLocaleDateString('en-IN', {
      day: 'numeric', month: 'long', year: 'numeric'
    });
    const h12 = hours % 12 || 12;
    const ampm = hours >= 12 ? 'PM' : 'AM';
    const dispTime = `${h12}:${String(minutes).padStart(2, '0')} ${ampm} IST`;
    const durMins = parseInt(meetDuration);
    const durLabel = durMins === 30 ? '30 min'
      : durMins === 45 ? '45 min'
      : durMins === 60 ? '1 hour'
      : durMins === 90 ? '1.5 hours'
      : '2 hours';
    const title = meetTitle || `Support Meeting — Ticket #${ticket?.id}`;
    const html =
      `<div style="border:1.5px solid #a5b4fc;border-radius:14px;padding:18px 22px;background:linear-gradient(135deg,#f5f3ff 0%,#ede9fe 100%);max-width:480px;">` +
      `<div style="display:flex;align-items:center;gap:8px;margin-bottom:12px;">` +
      `<span style="font-size:20px;">📅</span>` +
      `<strong style="color:#4f46e5;font-size:15px;">Google Meet Scheduled</strong></div>` +
      `<div style="font-size:13px;color:#374151;line-height:2;">` +
      `<div><strong style="color:#6b7280;">Meeting:</strong> ${title}</div>` +
      `<div><strong style="color:#6b7280;">Date:</strong> ${dispDate}</div>` +
      `<div><strong style="color:#6b7280;">Time:</strong> ${dispTime}</div>` +
      `<div><strong style="color:#6b7280;">Duration:</strong> ${durLabel}</div></div>` +
      `<a href="${meetLink}" style="display:inline-flex;align-items:center;gap:6px;margin-top:14px;` +
      `padding:9px 18px;background:#4f46e5;color:white;border-radius:8px;text-decoration:none;` +
      `font-size:13px;font-weight:700;letter-spacing:0.2px;">🎥 Join Google Meet</a></div>`;
    setIsSharingMeet(true);
    try {
      await submitReply(html, null, false);
      closeMeetModal();
    } catch {
      globalThis.alert('Failed to share in chat. Please try again.');
    } finally {
      setIsSharingMeet(false);
    }
  }, [meetLink, meetDate, meetTime, meetDuration, meetTitle, ticket, submitReply, closeMeetModal]);

  const addMeetEmailRecipient = (email: string) => {
    const t = email.trim().toLowerCase();
    if (t && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t) && !meetEmailRecipients.includes(t)) {
      setMeetEmailRecipients(prev => [...prev, t]);
    }
    setMeetEmailInput('');
  };

  const handleMeetEmailKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      addMeetEmailRecipient(meetEmailInput);
    } else if (e.key === 'Backspace' && !meetEmailInput && meetEmailRecipients.length > 0) {
      setMeetEmailRecipients(prev => prev.slice(0, -1));
    }
  };

  const handleSendMeetEmail = useCallback(async () => {
    const all = meetEmailInput.trim()
      ? [...meetEmailRecipients, meetEmailInput.trim()]
      : meetEmailRecipients;
    if (all.length === 0) {
      globalThis.alert('Please add at least one recipient email.');
      return;
    }
    setIsSendingMeetEmail(true);
    try {
      await sendMeetingInvite({
        meetTitle: meetTitle || `Support Meeting — Ticket #${ticket?.id}`,
        meetDateTime: `${meetDate}T${meetTime}`,
        meetDuration,
        meetLink,
        recipientEmails: all
      });
      globalThis.alert(`Meeting invite sent to ${all.length} recipient${all.length > 1 ? 's' : ''}!`);
      closeMeetModal();
    } catch {
      globalThis.alert('Failed to send meeting invite. Please try again.');
    } finally {
      setIsSendingMeetEmail(false);
    }
  }, [meetEmailInput, meetEmailRecipients, meetTitle, meetDate, meetTime, meetDuration,
      meetLink, ticket, sendMeetingInvite, closeMeetModal]);

  const handleSubmitReply = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!quillInstance.current) return;

    const textContent = quillInstance.current.getText().trim();
    const rawHtml = quillInstance.current.root.innerHTML;
    const hasEmbeddedImage = /<img[^>]+src="data:/i.test(rawHtml);
    if (!textContent && !attachment && !hasEmbeddedImage) return;

    try {
      let htmlMessage = (textContent || hasEmbeddedImage) ? rawHtml : '';

      // Extract any base64 image from the HTML and convert to a file attachment.
      // This handles cases where Quill embeds a pasted image before our clipboard
      // matcher can capture it, or when the async fetch hasn't completed yet.
      let fileToSend = attachment;
      const b64Match = htmlMessage.match(/<img[^>]+src="(data:image\/[^;]+;base64,[^"]+)"[^>]*>/i);
      if (b64Match && !fileToSend) {
        const dataUrl = b64Match[1];
        const byteString = atob(dataUrl.split(',')[1]);
        const mimeType = dataUrl.match(/data:([^;]+)/)?.[1] || 'image/png';
        const ab = new ArrayBuffer(byteString.length);
        const ia = new Uint8Array(ab);
        for (let i = 0; i < byteString.length; i++) ia[i] = byteString.charCodeAt(i);
        fileToSend = new File([ab], 'pasted-image.png', { type: mimeType });
      }
      // Always strip base64 images from the message HTML
      htmlMessage = htmlMessage.replace(/<img[^>]+src="data:[^"]*"[^>]*>/gi, '');

      await submitReply(htmlMessage, fileToSend, isInternalNote);

      quillInstance.current.root.innerHTML = '';
      setNewReplyHasContent(false);
      clearFile();
      setIsInternalNote(false);

      setTimeout(() => {
        if (chatContainerRef.current) {
          chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
        }
      }, 100);
    } catch (err) {
      globalThis.alert('Error sending reply. Please try again.');
    }
  };

  // RCA form helpers
  const addRcaSection = (arrayName: keyof RCAFormData) => {
    if (Array.isArray(rcaFormData[arrayName])) {
      setRcaFormData({
        ...rcaFormData,
        [arrayName]: [...(rcaFormData[arrayName] as RCAFormSection[]), { heading: '', content: '' }]
      });
    }
  };

  const removeRcaSection = (arrayName: keyof RCAFormData, index: number) => {
    if (Array.isArray(rcaFormData[arrayName])) {
      const sections = [...(rcaFormData[arrayName] as RCAFormSection[])];
      sections.splice(index, 1);
      setRcaFormData({ ...rcaFormData, [arrayName]: sections });
    }
  };

  const updateRcaSection = (arrayName: keyof RCAFormData, index: number, field: 'heading' | 'content', value: string) => {
    if (Array.isArray(rcaFormData[arrayName])) {
      const sections = [...(rcaFormData[arrayName] as RCAFormSection[])];
      sections[index][field] = value;
      setRcaFormData({ ...rcaFormData, [arrayName]: sections });
    }
  };

  const handleSubmitRca = async () => {
    if (!rcaFormData.problemStatement) {
      globalThis.alert('Problem Statement is required.');
      return;
    }
    if (!globalThis.confirm('This will generate an RCA report and CLOSE the ticket. Continue?')) return;

    setRcaSubmitting(true);
    try {
      await submitRCA(rcaFormData);
      globalThis.alert('RCA Generated successfully. Ticket Closed.');
      setShowRcaModal(false);
    } catch (err: any) {
      globalThis.alert('Failed to submit RCA: ' + (err.message || 'Unknown error'));
    } finally {
      setRcaSubmitting(false);
    }
  };

  // ── Note panel helpers ──────────────────────────────────────────────────

  /** First line of note shown as tooltip on the Note button */
  const noteFirstLine = React.useMemo(() => {
    if (!noteHtml) return '';
    const tmp = document.createElement('div');
    tmp.innerHTML = noteHtml;
    const text = (tmp.textContent || '').trim();
    return text.length > 80 ? text.substring(0, 80) + '…' : text;
  }, [noteHtml]);

  /** True when the note has actual text content */
  const hasNoteContent = noteHtml && noteHtml.replace(/<[^>]+>/g, '').trim().length > 0;

  /**
   * Derive "seen by other party" for a reply from the reply ordering.
   * A reply is considered seen when the other party has sent at least one
   * non-internal reply AFTER this reply's createdAt timestamp.
   */
  const isReplySeen = useCallback((reply: TicketReply): boolean => {
    if (!ticket?.replies) return false;
    const replyTime = new Date(reply.createdAt).getTime();
    if (reply.admin) {
      // Admin's outgoing → seen once a non-internal user reply follows
      return ticket.replies.some(r => !r.admin && !r.isInternal && new Date(r.createdAt).getTime() > replyTime);
    } else {
      // User's outgoing → seen once a non-internal admin reply follows
      return ticket.replies.some(r => r.admin && !r.isInternal && new Date(r.createdAt).getTime() > replyTime);
    }
  }, [ticket?.replies]);

  /**
   * Index of the first "unread" reply from the other party —
   * i.e. the first non-internal reply from the other side that appears
   * AFTER the viewer's own last reply.  Returns -1 when everything is read.
   */
  const firstUnreadIndex = useMemo(() => {
    if (!ticket?.replies) return -1;
    let lastMyIndex = -1;
    for (let i = ticket.replies.length - 1; i >= 0; i--) {
      if (isMe(ticket.replies[i]) && !ticket.replies[i].isInternal) {
        lastMyIndex = i;
        break;
      }
    }
    for (let i = lastMyIndex + 1; i < ticket.replies.length; i++) {
      const r = ticket.replies[i];
      if (!isMe(r) && !r.isInternal) return i;
    }
    return -1;
  }, [ticket?.replies, isMe]);

  const addTask = () => {
    if (!newTaskText.trim()) return;
    const task: NoteTask = { id: Date.now().toString(), text: newTaskText.trim(), done: false };
    const updated = [...noteTasks, task];
    setNoteTasks(updated);
    setNewTaskText('');
    if (ticketId) localStorage.setItem(`xamops_ticket_tasks_${ticketId}`, JSON.stringify(updated));
  };

  const toggleTask = (id: string) => {
    const updated = noteTasks.map(t => t.id === id ? { ...t, done: !t.done } : t);
    setNoteTasks(updated);
    if (ticketId) localStorage.setItem(`xamops_ticket_tasks_${ticketId}`, JSON.stringify(updated));
  };

  const deleteTask = (id: string) => {
    const updated = noteTasks.filter(t => t.id !== id);
    setNoteTasks(updated);
    if (ticketId) localStorage.setItem(`xamops_ticket_tasks_${ticketId}`, JSON.stringify(updated));
  };

  const handleCopyNote = useCallback(() => {
    if (noteTab === 'tasks') {
      const text = noteTasks.map(t => `${t.done ? '✓' : '☐'} ${t.text}`).join('\n');
      navigator.clipboard.writeText(text).catch(() => {});
    } else if (noteQuillInstance.current) {
      navigator.clipboard.writeText(noteQuillInstance.current.getText()).catch(() => {});
    }
  }, [noteTab, noteTasks]);

  const handlePostNoteAsInternal = async () => {
    if (!noteQuillInstance.current) return;
    const html = noteQuillInstance.current.root.innerHTML;
    if (!noteQuillInstance.current.getText().trim()) return;
    setIsPostingNote(true);
    try {
      await submitReply(html, null, true);
    } catch {
      globalThis.alert('Failed to post as internal note.');
    } finally {
      setIsPostingNote(false);
    }
  };

  const startNoteDrag = useCallback((e: React.MouseEvent) => {
    const startX = e.clientX;
    const startWidth = noteWidth;
    isDraggingRef.current = true;
    e.preventDefault();
    const onMove = (ev: MouseEvent) => {
      if (!isDraggingRef.current) return;
      const w = Math.min(700, Math.max(280, startWidth + (startX - ev.clientX)));
      setNoteWidth(w);
      sessionStorage.setItem('xamops_note_panel_width', String(w));
    };
    const onUp = () => {
      isDraggingRef.current = false;
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  }, [noteWidth]);

  const handleChatScroll = useCallback(() => {
    if (!chatContainerRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = chatContainerRef.current;
    setShowScrollBtn(scrollHeight - scrollTop - clientHeight > 120);
  }, []);

  const scrollToBottom = useCallback(() => {
    if (chatContainerRef.current) chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
  }, []);

  const setNoteColorAndSave = (color: keyof typeof NOTE_COLORS) => {
    setNoteColor(color);
    if (ticketId) localStorage.setItem(`xamops_ticket_note_color_${ticketId}`, color);
  };

  const renderRcaSection = (arrayName: keyof RCAFormData, sections: RCAFormSection[]) => (
    <>
      {sections.map((section, index) => (
        <div key={index} className="mb-3 bg-gray-50 p-4 rounded-lg border border-gray-200 relative group">
          <button type="button" onClick={() => removeRcaSection(arrayName, index)} className="absolute top-2 right-2 text-gray-400 hover:text-red-500 transition-colors">
            <i className="fas fa-times"></i>
          </button>
          <div className="mb-2">
            <input value={section.heading} onChange={e => updateRcaSection(arrayName, index, 'heading', e.target.value)} type="text" className="block w-full bg-transparent border-0 border-b border-gray-300 focus:border-purple-500 focus:ring-0 text-sm font-semibold placeholder-gray-400 px-0" placeholder="Sub-heading" />
          </div>
          <textarea value={section.content} onChange={e => updateRcaSection(arrayName, index, 'content', e.target.value)} className="w-full bg-white border-gray-300 rounded-md shadow-sm focus:ring-purple-500 focus:border-purple-500 text-sm" rows={2} placeholder="Details..."></textarea>
        </div>
      ))}
      <button type="button" onClick={() => addRcaSection(arrayName)} className="text-sm text-purple-600 hover:text-purple-800 flex items-center font-medium">
        <i className="fas fa-plus-circle mr-1"></i> Add Sub-section
      </button>
    </>
  );

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <i className="fas fa-spinner fa-spin text-5xl text-indigo-600"></i>
      </div>
    );
  }

  if (error && !ticket) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <i className="fas fa-exclamation-circle text-5xl text-red-500 mb-4"></i>
          <p className="text-xl text-gray-700">{error}</p>
          <button onClick={goBackToTickets} className="mt-4 text-indigo-600 hover:text-indigo-500 underline">
            Back to Tickets
          </button>
        </div>
      </div>
    );
  }

  if (!ticket) return null;

  // SLA data
  const isClosed = ticket.status === 'CLOSED';
  const slaStatus = getSlaStatus(ticket.createdAt, ticket.severity, isClosed, ticket.closedAt);
  const responseSlaLabel = getResponseSlaLabel(ticket.createdAt, ticket.severity, ticket.chatStartedAt);

  return (
    <div className="flex flex-col overflow-hidden bg-transparent" style={{ height: 'calc(100dvh - 104px)' }}>
      {/* Chat Container */}
      <div
        ref={chatContainerRef}
        className="flex-1 min-h-0 overflow-y-auto custom-scrollbar bg-slate-50/50 scroll-smooth"
        onScroll={handleChatScroll}
      >
        <div className="w-full p-6 lg:p-10 space-y-8">

          {/* Original Ticket + Metadata Card */}
          <div className="relative group bg-white/70 backdrop-blur-xl border border-white p-8 rounded-[2.5rem] shadow-[0_8px_40px_rgba(0,0,0,0.03)] hover:shadow-[0_24px_48px_rgba(0,0,0,0.06)] transition-all duration-500 overflow-hidden">
            <div className="absolute top-0 right-0 w-40 h-40 bg-gradient-to-br from-indigo-500/5 to-purple-500/5 rounded-full blur-3xl -mr-20 -mt-20 group-hover:scale-150 transition-transform duration-700"></div>

            <div className="relative flex gap-6">
              <div className="flex-shrink-0">
                <div className="w-12 h-12 rounded-2xl bg-indigo-600 flex items-center justify-center text-white shadow-lg shadow-indigo-200">
                  <i className="fas fa-ticket-alt text-lg"></i>
                </div>
              </div>
              <div className="flex-1 min-w-0 space-y-4">
                <div className="flex justify-between items-center gap-2">
                  <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest leading-none truncate min-w-0">Original Issue Description</span>
                  <span className="text-[10px] font-bold text-slate-400 uppercase tracking-tighter flex-shrink-0">Request #{ticket.id}</span>
                </div>
                <div className="text-base text-slate-700 font-medium leading-relaxed whitespace-pre-wrap break-words">
                  {ticket.description || <span className="text-slate-400 italic text-sm">No description provided.</span>}
                </div>

                <div className="flex flex-wrap gap-4 pt-4 border-t border-slate-100">
                  {ticket.accountId && (
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Account / Project:</span>
                      <span className="text-[10px] font-black text-indigo-600 font-mono">{ticket.accountId}</span>
                    </div>
                  )}
                  {ticket.creatorUsername && (
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Raised by:</span>
                      <span className="flex items-center gap-1.5 text-[10px] font-black text-slate-700">
                        <span className="w-4 h-4 rounded-full bg-slate-300 text-slate-600 flex items-center justify-center text-[7px] font-black">
                          {ticket.creatorUsername.charAt(0).toUpperCase()}
                        </span>
                        {ticket.creatorUsername}
                      </span>
                    </div>
                  )}
                  {isAdmin && ticket.assignedToUsername && (
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Assigned:</span>
                      <span className="flex items-center gap-1.5 text-[10px] font-black text-indigo-700">
                        <span className="w-4 h-4 rounded-full bg-indigo-600 text-white flex items-center justify-center text-[7px] font-black">
                          {ticket.assignedToUsername.charAt(0).toUpperCase()}
                        </span>
                        {ticket.assignedToUsername}
                      </span>
                    </div>
                  )}
                  <div className="flex items-center gap-2">
                    <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Service:</span>
                    <span className="text-[10px] font-black text-indigo-600">{ticket.service}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Submitted:</span>
                    <span className="text-[10px] font-black text-slate-600">{formatIST(ticket.createdAt)}</span>
                  </div>
                  {ticket.chatStartedAt && (
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Chat Started:</span>
                      <span className="text-[10px] font-black text-indigo-600">{formatIST(ticket.chatStartedAt)}</span>
                    </div>
                  )}
                  {ticket.closedAt && (
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Closed At:</span>
                      <span className="text-[10px] font-black text-slate-600">{formatIST(ticket.closedAt)}</span>
                    </div>
                  )}
                </div>

                {/* SLA card (admin only) — same style as TicketDetailPage */}
                {isAdmin && (
                  <div className={`flex items-center gap-3 px-4 py-3 rounded-2xl border ${
                    slaStatus.status === 'ok' ? 'bg-emerald-100 text-emerald-700 border-emerald-200' :
                    slaStatus.status === 'warning' ? 'bg-amber-100 text-amber-700 border-amber-200' :
                    'bg-red-100 text-red-700 border-red-200'
                  }`}>
                    <span className={`w-2 h-2 rounded-full flex-shrink-0 ${
                      slaStatus.status === 'ok' ? 'bg-emerald-500' :
                      slaStatus.status === 'warning' ? 'bg-amber-500 animate-pulse' :
                      'bg-red-500 animate-pulse'
                    }`}></span>
                    <div className="flex-1 min-w-0">
                      <div className="text-[10px] font-black uppercase tracking-widest leading-none mb-1">
                        Resolution SLA — {slaStatus.status === 'breached' ? 'Breached' : slaStatus.status === 'warning' ? 'At Risk' : 'On Track'}
                      </div>
                      <div className="text-[11px] font-bold">{slaStatus.label}</div>
                    </div>
                    <div className="text-[10px] font-black opacity-70 whitespace-nowrap">{responseSlaLabel}</div>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Discussion Header */}
          <div className="flex items-center gap-3 text-slate-400">
            <span className="text-[10px] font-black uppercase tracking-[0.2em] whitespace-nowrap">Correspondence Log</span>
            <div className="h-px bg-slate-200 flex-1"></div>
            <span className="text-[10px] font-bold text-slate-400 whitespace-nowrap">{ticket.replies?.length || 0} msg{(ticket.replies?.length || 0) !== 1 ? 's' : ''}</span>
          </div>

          {/* Replies */}
          {ticket.replies && ticket.replies.length > 0 ? (
            <div className="space-y-6">
              {ticket.replies.map((reply, index) => {
                const isMine = isMe(reply);
                const isInternalReply = reply.isInternal;
                const seenByOther = isMine && !isInternalReply && isReplySeen(reply);

                // Internal notes: amber styling, right-aligned (admin only)
                if (isInternalReply) {
                  return (
                    <React.Fragment key={reply.id}>
                      <div className="flex w-full justify-end">
                        <div className="flex max-w-[85%] md:max-w-[70%] gap-4 flex-row-reverse min-w-0">
                          <div className="flex-shrink-0 mt-auto">
                            <div className="w-10 h-10 rounded-2xl flex items-center justify-center text-sm font-black shadow-sm bg-amber-500 text-white">
                              <i className="fas fa-lock text-xs"></i>
                            </div>
                          </div>
                          <div className="space-y-2 text-right min-w-0">
                            <div className="flex items-center justify-end gap-2 px-1">
                              <span className="px-2 py-0.5 text-[8px] font-black bg-amber-100 text-amber-600 border border-amber-200 rounded-full uppercase tracking-widest flex-shrink-0">Internal Note</span>
                              <span className="text-[9px] font-bold text-slate-400 uppercase tracking-tighter flex-shrink-0">{formatIST(reply.createdAt)}</span>
                            </div>
                            <div className="p-4 rounded-[1.5rem] rounded-tr-none shadow-sm text-sm font-medium leading-relaxed overflow-hidden bg-amber-50 text-amber-900 border border-amber-200">
                              <div dangerouslySetInnerHTML={{ __html: reply.message }} className="prose-chat break-words" />
                            </div>
                          </div>
                        </div>
                      </div>
                    </React.Fragment>
                  );
                }

                return (
                  <React.Fragment key={reply.id}>
                    {/* ── "Unread" divider — shown before the first unread message from the other side ── */}
                    {index === firstUnreadIndex && firstUnreadIndex > 0 && (
                      <div className="flex items-center gap-3 py-1">
                        <div className="h-px bg-rose-200 flex-1"></div>
                        <span className="flex items-center gap-1.5 text-[10px] font-black text-rose-600 uppercase tracking-widest px-2.5 py-0.5 bg-rose-50 border border-rose-200 rounded-full">
                          <span className="w-1.5 h-1.5 rounded-full bg-rose-500 animate-pulse flex-shrink-0"></span>
                          Unread
                        </span>
                        <div className="h-px bg-rose-200 flex-1"></div>
                      </div>
                    )}

                  <div className={`flex w-full ${isMine ? 'justify-end' : 'justify-start'}`}>
                    <div className={`flex max-w-[85%] md:max-w-[70%] gap-4 min-w-0 ${isMine ? 'flex-row-reverse' : ''}`}>
                      <div className="flex-shrink-0 mt-auto">
                        <div className={`w-10 h-10 rounded-2xl flex items-center justify-center text-sm font-black shadow-sm ${reply.admin ? 'bg-indigo-600 text-white' : 'bg-slate-200 text-slate-600'}`}>
                          {reply.admin ? <i className="fas fa-shield-alt"></i> : <span>{getInitials(reply.authorUsername)}</span>}
                        </div>
                      </div>

                      <div className={`space-y-2 min-w-0 ${isMine ? 'text-right' : 'text-left'}`}>
                        <div className={`flex items-center gap-1.5 px-1 ${isMine ? 'justify-end' : 'justify-start'}`}>
                          {!isMine && <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">{reply.authorUsername}</span>}
                          <span className="text-[9px] font-bold text-slate-400 uppercase tracking-tighter">{formatIST(reply.createdAt)}</span>
                          {/* ── Read receipt ticks (only on my outgoing messages) ── */}
                          {isMine && (
                            <span
                              title={seenByOther ? 'Seen' : 'Delivered, not yet seen'}
                              style={{
                                letterSpacing: '-1px',
                                fontSize: '13px',
                                lineHeight: 1,
                                color: seenByOther ? '#38bdf8' : 'rgba(148,163,184,0.7)',
                                fontWeight: seenByOther ? 700 : 400,
                              }}
                            >✓✓</span>
                          )}
                          {isMine && (
                            <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">
                              {isAdmin ? reply.authorUsername : 'You'}
                            </span>
                          )}
                        </div>

                        <div className={`group/msg relative p-4 rounded-[1.5rem] shadow-sm text-sm font-medium leading-relaxed overflow-visible ${isMine ? 'bg-indigo-600 text-white rounded-tr-none' : 'bg-white text-slate-700 border border-slate-100 rounded-tl-none'}`}>

                          {/* ── Edit / Delete hover buttons ── */}
                          {isMine && !reply.deleted && ticket.status !== 'CLOSED' && (
                            <div className={`absolute top-2 ${isMine ? 'left-2' : 'right-2'} opacity-0 group-hover/msg:opacity-100 transition-opacity flex items-center gap-1 z-10`}>
                              {/* Edit — only within 15 min */}
                              {(Date.now() - new Date(reply.createdAt).getTime()) < 15 * 60 * 1000 && (
                                <button
                                  onClick={() => {
                                    setEditingReplyId(reply.id);
                                    const tmp = document.createElement('div');
                                    tmp.innerHTML = reply.message;
                                    setEditContent(tmp.textContent || tmp.innerText || '');
                                  }}
                                  className="p-1.5 rounded-lg bg-white/20 hover:bg-white/30 transition-colors"
                                  title="Edit (within 15 min)"
                                >
                                  <i className="fas fa-pencil-alt text-[10px] text-white/70 hover:text-white"></i>
                                </button>
                              )}
                              <button
                                onClick={() => { if (globalThis.confirm('Delete this message?')) deleteReply(reply.id); }}
                                className="p-1.5 rounded-lg bg-white/20 hover:bg-red-500/40 transition-colors"
                                title="Delete message"
                              >
                                <i className="fas fa-trash text-[10px] text-white/70 hover:text-red-200"></i>
                              </button>
                            </div>
                          )}

                          {reply.deleted ? (
                            <div className={`flex items-center gap-2 italic ${isMine ? 'text-white/50' : 'text-slate-400'}`}>
                              <i className="fas fa-ban text-xs"></i>
                              <span className="text-sm">This message was deleted</span>
                            </div>
                          ) : editingReplyId === reply.id ? (
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
                                  onClick={() => setEditingReplyId(null)}
                                  className="text-xs px-3 py-1 rounded-lg bg-white/20 hover:bg-white/30 text-white border border-white/30"
                                >Cancel</button>
                                <button
                                  onClick={async () => {
                                    if (!editContent.trim()) return;
                                    try {
                                      const htmlMsg = editContent.split('\n').map(line => `<p>${line || '<br>'}</p>`).join('');
                                      await editReply(reply.id, htmlMsg);
                                      setEditingReplyId(null);
                                    } catch { globalThis.alert('Failed to edit. The 15-minute window may have passed.'); }
                                  }}
                                  className="text-xs px-3 py-1 rounded-lg bg-white text-indigo-700 font-semibold hover:bg-indigo-50"
                                >Save</button>
                              </div>
                            </div>
                          ) : (
                            <>
                              <div dangerouslySetInnerHTML={{ __html: reply.message }} className="prose-chat !p-0 break-words" />
                              {reply.edited && (
                                <span className={`text-[9px] font-bold italic mt-1 inline-block ${isMine ? 'text-white/40' : 'text-slate-400'}`}>Edited</span>
                              )}
                            </>
                          )}

                          {/* Attachments (only if not deleted) */}
                          {!reply.deleted && (reply.attachmentUrl || reply.attachmentName) && (
                            <div className="mt-4">
                              {reply.attachmentType?.startsWith('image/') && (
                                <div
                                  className="mb-3 rounded-2xl overflow-hidden border border-white/20 shadow-md max-w-xs cursor-pointer hover:opacity-90 transition-opacity"
                                  onClick={() => globalThis.open(reply.attachmentUrl, '_blank')}
                                >
                                  <img src={reply.attachmentUrl} alt={reply.attachmentName} className="w-full h-auto object-cover max-h-60" />
                                </div>
                              )}

                              {reply.attachmentName?.startsWith('rca_ticket_') && (
                                <div
                                  className="group cursor-pointer bg-white/10 backdrop-blur-md rounded-2xl overflow-hidden border border-white/20 hover:border-purple-300 transition-colors shadow-lg"
                                  onClick={() => reply.attachmentUrl ? globalThis.open(reply.attachmentUrl, '_blank') : globalThis.alert('File URL not found')}
                                >
                                  <div className={`flex items-center p-4 ${isMine ? 'bg-white/10' : 'bg-purple-50'}`}>
                                    <div className="p-3 bg-purple-600 text-white rounded-xl mr-4 shadow-lg shadow-purple-200">
                                      <i className="fas fa-file-medical-alt text-xl"></i>
                                    </div>
                                    <div className="flex-1">
                                      <h4 className={`text-sm font-black leading-tight ${isMine ? 'text-white' : 'text-purple-900'}`}>RCA Report Generated</h4>
                                      <p className={`text-[10px] font-bold mt-1 uppercase tracking-widest ${isMine ? 'text-white/70' : 'text-purple-600'}`}>Download PDF Report</p>
                                    </div>
                                    <i className={`fas fa-download ${isMine ? 'text-white/50' : 'text-purple-400'}`}></i>
                                  </div>
                                </div>
                              )}

                              {(!reply.attachmentName || !reply.attachmentName.startsWith('rca_ticket_')) && (
                                <div
                                  className={`flex items-center p-3 rounded-2xl transition-all cursor-pointer ${isMine ? 'bg-white/10 border border-white/20 hover:bg-white/20' : 'bg-slate-50 border border-slate-100 hover:bg-slate-100'}`}
                                  onClick={() => reply.attachmentUrl ? globalThis.open(reply.attachmentUrl, '_blank') : alert('File URL not found')}
                                >
                                  <div className={`w-10 h-10 flex items-center justify-center rounded-xl mr-3 ${isMine ? 'bg-white/20 text-white' : 'bg-white shadow-sm text-indigo-500'}`}>
                                    {reply.attachmentType?.startsWith('image/') ? <i className="fas fa-file-image text-lg"></i> : reply.attachmentType?.includes('pdf') ? <i className="fas fa-file-pdf text-lg"></i> : <i className="fas fa-file-alt text-lg"></i>}
                                  </div>
                                  <div className="flex-1 min-w-0">
                                    <p className={`text-xs font-bold truncate ${isMine ? 'text-white' : 'text-slate-800'}`}>{reply.attachmentName || 'Attached File'}</p>
                                    <p className={`text-[9px] font-bold uppercase tracking-widest mt-0.5 ${isMine ? 'text-white/60' : 'text-slate-400'}`}>Click to view</p>
                                  </div>
                                  <i className={`fas fa-download text-xs ${isMine ? 'text-white/40' : 'text-slate-300'}`}></i>
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>
                  </React.Fragment>
                );
              })}
            </div>
          ) : (
            <div className="text-center py-10">
              <p className="text-gray-400 text-sm italic">No replies yet. Start the conversation below.</p>
            </div>
          )}

          {/* Closed banner — shown inline at bottom of chat */}
          {isClosed && (
            <div className="flex items-center justify-between gap-3 px-6 py-4 bg-slate-100 rounded-2xl border border-slate-200 text-[11px] font-bold text-slate-500">
              <div className="flex items-center gap-3">
                <i className="fas fa-lock text-slate-400"></i>
                This ticket was closed
                {ticket.closedAt && <span className="text-slate-400">· {formatIST(ticket.closedAt)}</span>}
              </div>
              {isAdmin && (
                <button
                  onClick={handleReopenTicket}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-emerald-50 text-emerald-700 rounded-lg text-[10px] font-bold border border-emerald-200 hover:bg-emerald-100 transition-all active:scale-95"
                >
                  <i className="fas fa-lock-open text-[9px]"></i>
                  Reopen
                </button>
              )}
            </div>
          )}
        </div>

        {/* Scroll-to-bottom floating button */}
        {showScrollBtn && (
          <div className="sticky bottom-2 flex justify-end pointer-events-none">
            <button
              onClick={scrollToBottom}
              className="pointer-events-auto flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[11px] font-bold rounded-full shadow-lg transition-all"
            >
              <i className="fas fa-chevron-down text-[9px]"></i>
              Latest
            </button>
          </div>
        )}
      </div>

      {/* Footer - Reply Form */}
      {ticket.status !== 'CLOSED' ? (
        <div className="p-6 bg-white/70 backdrop-blur-xl border-t border-slate-200/50">
          <div className="w-full">
            <form onSubmit={handleSubmitReply}>
              <div className="relative bg-white rounded-3xl border overflow-hidden focus-within:ring-4 transition-all shadow-sm border-slate-200 focus-within:ring-indigo-500/10 focus-within:border-indigo-400/40">
                <div className="px-5 py-3 border-b border-slate-50 flex items-center justify-between bg-slate-50/30">
                  <div className="flex items-center gap-2 text-[10px] font-black uppercase tracking-widest leading-none text-slate-500">
                    <i className="fas fa-reply text-indigo-500"></i>
                    Official Ticket Response
                  </div>
                  <div className="flex items-center gap-3">
                    {attachment && (
                      <div className="flex items-center gap-2 px-3 py-1.5 bg-indigo-50 text-indigo-700 rounded-lg border border-indigo-100 text-[9px] font-black uppercase tracking-widest">
                        <i className="fas fa-paperclip"></i>
                        <span className="max-w-[150px] truncate">{fileName}</span>
                        <button type="button" onClick={clearFile} className="hover:text-rose-500 transition-colors">
                          <i className="fas fa-times"></i>
                        </button>
                      </div>
                    )}
                    {/* Sticky note side panel trigger - admin only */}
                    {isAdmin && (
                      <button
                        type="button"
                        onClick={() => setShowNotePanel(true)}
                        title={noteFirstLine ? `📝 ${noteFirstLine}` : 'Internal note pad (Ctrl/⌘+Shift+N)'}
                        className="relative flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[9px] font-black uppercase tracking-widest border transition-all bg-amber-50 text-amber-600 border-amber-300 hover:bg-amber-100 active:scale-95"
                      >
                        <i className="fas fa-sticky-note"></i>
                        Note
                        {hasNoteContent && (
                          <span className="absolute -top-1 -right-1 w-2 h-2 rounded-full bg-amber-500 border border-white"></span>
                        )}
                        {noteTasks.filter(t => !t.done).length > 0 && (
                          <span className="absolute -top-1.5 -left-1 min-w-[14px] h-[14px] flex items-center justify-center rounded-full bg-indigo-500 border border-white text-white text-[7px] font-black px-0.5">
                            {noteTasks.filter(t => !t.done).length}
                          </span>
                        )}
                      </button>
                    )}
                  </div>
                </div>

                <div className="quill-minimal-wrapper">
                  <div ref={quillRef} className="bg-white"></div>
                </div>

                <div className="px-5 py-3 border-t border-slate-50 flex items-center justify-between bg-slate-50/10">
                  <label className="flex items-center gap-2 px-4 py-2 bg-slate-100 hover:bg-slate-200 text-slate-600 rounded-xl text-[10px] font-black uppercase tracking-widest cursor-pointer transition-all active:scale-95">
                    <i className="fas fa-paperclip text-sm"></i>
                    <span>Attach Files</span>
                    <input ref={fileInputRef} type="file" className="hidden" onChange={handleFileSelect} />
                  </label>

                  <button
                    type="submit"
                    disabled={isSubmitting || (!newReplyHasContent && !attachment)}
                    className="flex items-center gap-2 px-6 py-2.5 text-white rounded-xl text-[11px] font-black uppercase tracking-widest shadow-lg transition-all disabled:opacity-50 active:scale-95 bg-indigo-600 hover:bg-indigo-700 shadow-indigo-200"
                  >
                    {isSubmitting
                      ? <><i className="fas fa-circle-notch fa-spin"></i> Sending...</>
                      : <><i className="fas fa-paper-plane"></i> Send</>
                    }
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      ) : null}

      {/* Email Thread Modal — rendered via portal so fixed/backdrop works regardless of scroll/transforms */}
      {showEmailModal && ReactDOM.createPortal(
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="fixed inset-0 bg-black/40 backdrop-blur-sm" onClick={closeEmailModal}></div>
          <div className="relative bg-white rounded-2xl shadow-2xl w-full max-w-lg overflow-hidden z-10" onClick={e => e.stopPropagation()}>

              {/* Header */}
              <div className="px-6 pt-6 pb-4 border-b border-slate-100">
                <div className="flex items-center justify-between">
                  <h3 className="text-base font-bold text-slate-900 flex items-center gap-2.5">
                    <div className="w-8 h-8 rounded-xl bg-sky-100 flex items-center justify-center flex-shrink-0">
                      <i className="fas fa-envelope text-sky-600 text-sm"></i>
                    </div>
                    Email Ticket Thread
                  </h3>
                  <button onClick={closeEmailModal} className="w-8 h-8 flex items-center justify-center rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors">
                    <i className="fas fa-times text-sm"></i>
                  </button>
                </div>
                <p className="mt-1 text-xs text-slate-400 pl-10">Send the full conversation history as a formatted email.</p>
              </div>

              <div className="px-6 py-5 space-y-5 max-h-[65vh] overflow-y-auto custom-scrollbar">

                {/* Chip-based recipients input */}
                <div>
                  <label className="block text-[10px] font-black text-slate-500 mb-2 uppercase tracking-widest">
                    Recipients <span className="text-red-500">*</span>
                  </label>
                  <div className="min-h-[52px] border border-slate-200 rounded-xl px-3 py-2 flex flex-wrap gap-1.5 items-center focus-within:ring-2 focus-within:ring-sky-400 focus-within:border-sky-400 transition-all bg-white cursor-text">
                    {emailRecipients.map(email => (
                      <span key={email} className="flex items-center gap-1 pl-2.5 pr-1 py-0.5 bg-sky-100 text-sky-800 rounded-full text-[11px] font-bold border border-sky-200 flex-shrink-0">
                        {email}
                        <button type="button" onClick={() => removeEmailRecipient(email)} className="w-4 h-4 flex items-center justify-center rounded-full hover:bg-sky-200 transition-colors ml-0.5 text-sky-500">
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
                      placeholder={emailRecipients.length === 0 ? 'Type email and press Enter...' : 'Add more...'}
                      className="flex-1 min-w-[160px] outline-none text-sm text-slate-700 placeholder-slate-300 bg-transparent py-0.5"
                    />
                  </div>
                  <p className="mt-1 text-[10px] text-slate-400">Press Enter or comma to add • Backspace to remove last</p>
                </div>

                {/* Quick-add: known users */}
                {(adminUsers.filter(a => a.email).length > 0 || ticket?.creatorEmail) && (
                  <div>
                    <label className="block text-[10px] font-black text-slate-500 mb-2 uppercase tracking-widest">Quick Add Users</label>
                    <div className="space-y-1.5 border border-slate-100 rounded-xl overflow-hidden divide-y divide-slate-100">

                      {/* Ticket creator */}
                      {ticket?.creatorEmail && (
                        <div className="flex items-center justify-between px-3.5 py-3 bg-white hover:bg-slate-50 transition-colors">
                          <div className="flex items-center gap-2.5 min-w-0">
                            <div className="w-7 h-7 rounded-full bg-slate-200 text-slate-600 flex items-center justify-center text-[10px] font-black flex-shrink-0">
                              {(ticket.creatorUsername || 'U').charAt(0).toUpperCase()}
                            </div>
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5">
                                <p className="text-[11px] font-bold text-slate-700 leading-none">{ticket.creatorUsername}</p>
                                <span className="px-1.5 py-0.5 text-[8px] font-black bg-emerald-100 text-emerald-700 rounded-full uppercase tracking-widest border border-emerald-200">Creator</span>
                              </div>
                              <p className="text-[10px] text-slate-400 mt-0.5 truncate">{ticket.creatorEmail}</p>
                            </div>
                          </div>
                          <button
                            type="button"
                            onClick={() => addEmailRecipient(ticket.creatorEmail!)}
                            disabled={emailRecipients.includes(ticket.creatorEmail!.toLowerCase())}
                            className="flex items-center gap-1 px-3 py-1.5 text-[10px] font-black rounded-lg border transition-all disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0 ml-3 bg-white text-sky-600 border-sky-200 hover:bg-sky-50 active:scale-95"
                          >
                            {emailRecipients.includes(ticket.creatorEmail.toLowerCase())
                              ? <><i className="fas fa-check text-emerald-500"></i> Added</>
                              : <><i className="fas fa-plus"></i> Add</>
                            }
                          </button>
                        </div>
                      )}

                      {/* Admin users */}
                      {adminUsers.filter(a => a.email).map(admin => (
                        <div key={admin.id} className="flex items-center justify-between px-3.5 py-3 bg-white hover:bg-slate-50 transition-colors">
                          <div className="flex items-center gap-2.5 min-w-0">
                            <div className="w-7 h-7 rounded-full bg-indigo-600 text-white flex items-center justify-center text-[10px] font-black flex-shrink-0">
                              {admin.username.charAt(0).toUpperCase()}
                            </div>
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5">
                                <p className="text-[11px] font-bold text-slate-700 leading-none">{admin.username}</p>
                                <span className="px-1.5 py-0.5 text-[8px] font-black bg-indigo-100 text-indigo-700 rounded-full uppercase tracking-widest border border-indigo-200">Admin</span>
                              </div>
                              <p className="text-[10px] text-slate-400 mt-0.5 truncate">{admin.email}</p>
                            </div>
                          </div>
                          <button
                            type="button"
                            onClick={() => addEmailRecipient(admin.email!)}
                            disabled={emailRecipients.includes(admin.email!.toLowerCase())}
                            className="flex items-center gap-1 px-3 py-1.5 text-[10px] font-black rounded-lg border transition-all disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0 ml-3 bg-white text-sky-600 border-sky-200 hover:bg-sky-50 active:scale-95"
                          >
                            {emailRecipients.includes(admin.email!.toLowerCase())
                              ? <><i className="fas fa-check text-emerald-500"></i> Added</>
                              : <><i className="fas fa-plus"></i> Add</>
                            }
                          </button>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Send mode toggle */}
                <div>
                  <label className="block text-[10px] font-black text-slate-500 mb-2 uppercase tracking-widest">Send Mode</label>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => setSendMode('separate')}
                      className={`flex-1 flex items-center gap-2.5 px-4 py-3 rounded-xl border text-left transition-all ${
                        sendMode === 'separate'
                          ? 'bg-sky-50 border-sky-400 text-sky-800 shadow-sm shadow-sky-100'
                          : 'bg-white border-slate-200 text-slate-500 hover:border-slate-300'
                      }`}
                    >
                      <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${sendMode === 'separate' ? 'border-sky-500' : 'border-slate-300'}`}>
                        {sendMode === 'separate' && <div className="w-2 h-2 rounded-full bg-sky-500"></div>}
                      </div>
                      <div>
                        <div className="text-[11px] font-black uppercase tracking-widest leading-none">Separate</div>
                        <div className="text-[9px] font-medium text-slate-400 mt-0.5 leading-snug">One email per recipient</div>
                      </div>
                    </button>
                    <button
                      type="button"
                      onClick={() => setSendMode('combined')}
                      className={`flex-1 flex items-center gap-2.5 px-4 py-3 rounded-xl border text-left transition-all ${
                        sendMode === 'combined'
                          ? 'bg-sky-50 border-sky-400 text-sky-800 shadow-sm shadow-sky-100'
                          : 'bg-white border-slate-200 text-slate-500 hover:border-slate-300'
                      }`}
                    >
                      <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${sendMode === 'combined' ? 'border-sky-500' : 'border-slate-300'}`}>
                        {sendMode === 'combined' && <div className="w-2 h-2 rounded-full bg-sky-500"></div>}
                      </div>
                      <div>
                        <div className="text-[11px] font-black uppercase tracking-widest leading-none">Combined</div>
                        <div className="text-[9px] font-medium text-slate-400 mt-0.5 leading-snug">All in one email (CC visible)</div>
                      </div>
                    </button>
                  </div>
                </div>

                {/* Info banner */}
                <div className="bg-sky-50 border border-sky-100 rounded-xl px-4 py-3 text-xs text-sky-700 space-y-1">
                  <div className="font-bold flex items-center gap-1.5"><i className="fas fa-info-circle"></i> What will be sent:</div>
                  <ul className="list-disc list-inside space-y-0.5 pl-1 text-sky-600">
                    <li>Ticket #{ticket?.id} — {ticket?.subject}</li>
                    <li>All public replies (internal notes excluded)</li>
                    <li>Admin replies shown as "Support Team"</li>
                  </ul>
                </div>
              </div>

              {/* Footer */}
              <div className="px-6 py-4 flex justify-between items-center border-t border-slate-100 bg-slate-50/50">
                <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">
                  {emailRecipients.length + (emailInput.trim() ? 1 : 0)} recipient{(emailRecipients.length + (emailInput.trim() ? 1 : 0)) !== 1 ? 's' : ''} selected
                </span>
                <div className="flex gap-3">
                  <button
                    type="button"
                    onClick={closeEmailModal}
                    className="px-4 py-2 text-sm font-semibold text-slate-600 bg-white border border-slate-200 hover:bg-slate-100 rounded-xl transition-colors"
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    onClick={handleSendEmail}
                    disabled={isSendingEmail || (emailRecipients.length === 0 && !emailInput.trim())}
                    className="flex items-center gap-2 px-5 py-2 text-sm font-bold text-white bg-sky-600 hover:bg-sky-700 rounded-xl shadow-lg shadow-sky-500/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed active:scale-95"
                  >
                    {isSendingEmail
                      ? <><i className="fas fa-circle-notch fa-spin"></i> Sending...</>
                      : <><i className="fas fa-paper-plane"></i> Send Email</>
                    }
                  </button>
                </div>
              </div>
            </div>
        </div>,
        document.body
      )}

      {/* RCA Modal */}
      <DialogRoot isOpen={showRcaModal} onClose={() => setShowRcaModal(false)} maxWidth="max-w-4xl">
        <DialogPanel
          title={
            <span className="flex items-center gap-2 text-lg font-medium text-gray-900">
              <i className="fas fa-file-medical-alt text-purple-600"></i> Generate Root Cause Analysis
            </span>
          }
          onClose={() => setShowRcaModal(false)}
          footer={
            <>
              <button type="button" onClick={() => setShowRcaModal(false)} className="inline-flex justify-center rounded-md border border-gray-300 shadow-sm px-4 py-2 bg-white text-sm font-medium text-gray-700 hover:bg-gray-50">
                Cancel
              </button>
              <button type="button" onClick={handleSubmitRca} disabled={rcaSubmitting} className="inline-flex justify-center rounded-md border border-transparent shadow-sm px-4 py-2 bg-purple-600 text-sm font-medium text-white hover:bg-purple-700 disabled:opacity-50">
                {rcaSubmitting ? <><i className="fas fa-circle-notch fa-spin mr-2"></i> Generating PDF...</> : 'Submit & Close Ticket'}
              </button>
            </>
          }
        >
          <div className="overflow-y-auto max-h-[60vh] pr-2 custom-scrollbar">
            <form className="space-y-6">
              <div>
                <label className="block text-sm font-bold text-gray-800 mb-2">1. Problem Statement <span className="text-red-500">*</span></label>
                <textarea value={rcaFormData.problemStatement} onChange={e => setRcaFormData({ ...rcaFormData, problemStatement: e.target.value })} className="w-full shadow-sm border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm" rows={3} placeholder="Describe the problem briefly..."></textarea>
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">2. Incident Overview</label>
                {renderRcaSection('incidentOverview', rcaFormData.incidentOverview)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">3. Description of the Issue</label>
                {renderRcaSection('descriptionSections', rcaFormData.descriptionSections)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">4. Root Cause Identification</label>
                {renderRcaSection('rootCauseSections', rcaFormData.rootCauseSections)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">5. Resolution</label>
                {renderRcaSection('resolutionSections', rcaFormData.resolutionSections)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">6. Prevention / Future Mitigation</label>
                {renderRcaSection('preventionSections', rcaFormData.preventionSections)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">7. Conclusion</label>
                <textarea value={rcaFormData.conclusion} onChange={e => setRcaFormData({ ...rcaFormData, conclusion: e.target.value })} className="w-full shadow-sm border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm" rows={3} placeholder="Final summary..."></textarea>
              </div>
            </form>
          </div>
        </DialogPanel>
      </DialogRoot>

      {/* ── Google Meet Scheduling Modal ──────────────────────────────────────── */}
      {showMeetModal && ReactDOM.createPortal(
        <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
          {/* Backdrop */}
          <div
            className="absolute inset-0 bg-black/40 backdrop-blur-sm"
            onClick={closeMeetModal}
          />

          <div className="relative w-full max-w-lg bg-white rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
            {/* Header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100 bg-gradient-to-r from-emerald-50 to-teal-50 flex-shrink-0">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-xl bg-emerald-500 flex items-center justify-center shadow-lg shadow-emerald-200">
                  <i className="fas fa-video text-white text-sm"></i>
                </div>
                <div>
                  <h2 className="text-sm font-black text-slate-900 uppercase tracking-widest">Schedule Google Meet</h2>
                  <p className="text-[10px] text-slate-500 font-medium mt-0.5">Admin only · Ticket #{ticket?.id}</p>
                </div>
              </div>
              <button
                onClick={closeMeetModal}
                className="w-8 h-8 flex items-center justify-center rounded-xl text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
              >
                <i className="fas fa-times text-sm"></i>
              </button>
            </div>

            {/* Body */}
            <div className="overflow-y-auto flex-1 custom-scrollbar">
              <div className="px-6 py-5 space-y-4">

                {/* Meeting Title */}
                <div>
                  <label className="block text-[10px] font-black text-slate-500 mb-1.5 uppercase tracking-widest">Meeting Title</label>
                  <input
                    type="text"
                    value={meetTitle}
                    onChange={e => setMeetTitle(e.target.value)}
                    placeholder={`Support Meeting — Ticket #${ticket?.id}`}
                    className="w-full px-3 py-2.5 text-sm border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-400 focus:border-transparent bg-slate-50"
                  />
                </div>

                {/* Date + Time (IST) */}
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-[10px] font-black text-slate-500 mb-1.5 uppercase tracking-widest">Date (IST)</label>
                    <input
                      type="date"
                      value={meetDate}
                      onChange={e => setMeetDate(e.target.value)}
                      min={new Date().toISOString().substring(0, 10)}
                      className="w-full px-3 py-2.5 text-sm border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-400 focus:border-transparent bg-slate-50"
                    />
                  </div>
                  <div>
                    <label className="block text-[10px] font-black text-slate-500 mb-1.5 uppercase tracking-widest">Time (IST)</label>
                    <input
                      type="time"
                      value={meetTime}
                      onChange={e => setMeetTime(e.target.value)}
                      className="w-full px-3 py-2.5 text-sm border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-400 focus:border-transparent bg-slate-50"
                    />
                  </div>
                </div>

                {/* Duration */}
                <div>
                  <label className="block text-[10px] font-black text-slate-500 mb-1.5 uppercase tracking-widest">Duration</label>
                  <div className="flex gap-2 flex-wrap">
                    {[{ v: '30', l: '30 min' }, { v: '45', l: '45 min' }, { v: '60', l: '1 hour' }, { v: '90', l: '1.5 hrs' }, { v: '120', l: '2 hrs' }].map(opt => (
                      <button
                        key={opt.v}
                        type="button"
                        onClick={() => setMeetDuration(opt.v)}
                        className={`px-3 py-1.5 rounded-lg text-xs font-bold border transition-all ${
                          meetDuration === opt.v
                            ? 'bg-emerald-500 text-white border-emerald-500 shadow-sm shadow-emerald-200'
                            : 'bg-white text-slate-600 border-slate-200 hover:border-emerald-300 hover:text-emerald-600'
                        }`}
                      >
                        {opt.l}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Meet Link — create via Google then paste back */}
                <div className="space-y-3">
                  {/* Step 1 — Create the real meeting */}
                  <div className="bg-emerald-50 border border-emerald-100 rounded-xl p-4">
                    <p className="text-xs font-bold text-emerald-800 mb-1">
                      Step 1 — Create a Google Meet
                    </p>
                    <p className="text-[11px] text-emerald-700 mb-3">
                      Click below to open Google Meet. A real meeting link will be created instantly — copy it from the browser address bar.
                    </p>
                    <a
                      href="https://meet.google.com/new"
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-2 px-4 py-2 bg-emerald-600 text-white rounded-xl text-xs font-bold shadow-md shadow-emerald-200 hover:bg-emerald-700 transition-colors"
                    >
                      <i className="fas fa-video text-[10px]"></i>
                      Open Google Meet
                    </a>
                  </div>

                  {/* Step 2 — Paste the link */}
                  <div>
                    <label className="block text-[10px] font-black text-slate-500 mb-1.5 uppercase tracking-widest">
                      Step 2 — Paste the Meet Link
                    </label>
                    <div className="flex gap-2 items-center">
                      <input
                        type="url"
                        value={meetLink}
                        onChange={e => setMeetLink(e.target.value)}
                        placeholder="https://meet.google.com/xxx-xxxx-xxx"
                        className="flex-1 px-3 py-2.5 text-sm border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-400 focus:border-transparent bg-slate-50 font-mono"
                      />
                      {meetLink && (
                        <button type="button" onClick={() => setMeetLink('')}
                          className="px-3 py-2.5 text-slate-400 hover:text-slate-600 transition-colors">
                          <i className="fas fa-times text-xs"></i>
                        </button>
                      )}
                    </div>
                    {meetLink && !/^https:\/\/meet\.google\.com\//.test(meetLink) && (
                      <p className="text-[10px] text-amber-600 mt-1 font-medium flex items-center gap-1">
                        <i className="fas fa-exclamation-triangle text-[9px]"></i>
                        Should start with https://meet.google.com/
                      </p>
                    )}
                  </div>

                  <div className="flex justify-end">
                    <a
                      href={generateGoogleCalendarUrl()}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-[10px] text-slate-400 hover:text-indigo-500 font-medium underline underline-offset-2 transition-colors"
                    >
                      Add to Google Calendar ↗
                    </a>
                  </div>
                </div>

                {/* Share options — always visible once link exists */}
                {meetLink && (
                  <div className="space-y-3 pt-1">
                    <p className="text-[10px] font-black text-slate-500 uppercase tracking-widest flex items-center gap-1.5">
                      <i className="fas fa-share-alt text-slate-400 text-[10px]"></i>
                      Share the Link
                    </p>

                    {/* Share in Chat */}
                    {ticket?.status !== 'CLOSED' && (
                      <button
                        type="button"
                        onClick={handleShareMeetInChat}
                        disabled={isSharingMeet || !meetDate || !meetTime}
                        className="w-full flex items-center gap-3 px-4 py-3 bg-indigo-50 border border-indigo-200 text-indigo-700 rounded-xl text-sm font-bold hover:bg-indigo-100 transition-all active:scale-95 disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {isSharingMeet
                          ? <i className="fas fa-circle-notch fa-spin text-indigo-500"></i>
                          : <i className="fas fa-comment-dots text-indigo-500"></i>
                        }
                        <div className="text-left">
                          <div className="text-xs font-black">{isSharingMeet ? 'Sharing…' : 'Share in Chat'}</div>
                          <div className="text-[10px] font-medium text-indigo-500">Posts meeting card in ticket thread</div>
                        </div>
                      </button>
                    )}

                    {/* Email recipients */}
                    <div className="border border-slate-200 rounded-xl overflow-hidden">
                      <div className="px-4 py-2.5 bg-slate-50 border-b border-slate-100">
                        <p className="text-[10px] font-black text-slate-500 uppercase tracking-widest flex items-center gap-1.5">
                          <i className="fas fa-envelope text-slate-400 text-[10px]"></i>
                          Send Invite via Email
                        </p>
                      </div>
                      <div className="p-4 space-y-3">
                        {/* Recipient pills */}
                        <div className="flex flex-wrap gap-1.5 min-h-[36px] px-3 py-2 border border-slate-200 rounded-xl bg-slate-50 cursor-text"
                          onClick={() => document.getElementById('meet-email-input')?.focus()}
                        >
                          {meetEmailRecipients.map(email => (
                            <span key={email} className="inline-flex items-center gap-1 px-2 py-0.5 bg-white border border-slate-200 text-slate-700 rounded-lg text-[11px] font-bold shadow-sm">
                              {email}
                              <button
                                type="button"
                                onClick={e => { e.stopPropagation(); setMeetEmailRecipients(prev => prev.filter(r => r !== email)); }}
                                className="text-slate-400 hover:text-rose-500 transition-colors ml-0.5"
                              >
                                <i className="fas fa-times text-[9px]"></i>
                              </button>
                            </span>
                          ))}
                          <input
                            id="meet-email-input"
                            type="email"
                            value={meetEmailInput}
                            onChange={e => setMeetEmailInput(e.target.value)}
                            onKeyDown={handleMeetEmailKeyDown}
                            onBlur={() => meetEmailInput.trim() && addMeetEmailRecipient(meetEmailInput)}
                            placeholder={meetEmailRecipients.length === 0 ? 'Enter email and press Enter…' : ''}
                            className="flex-1 min-w-[160px] bg-transparent text-xs outline-none text-slate-700 placeholder-slate-400"
                          />
                        </div>

                        {/* Admin quick-add */}
                        {adminUsers.filter(a => a.email).length > 0 && (
                          <div className="flex flex-wrap gap-1.5">
                            {adminUsers.filter(a => a.email).map(admin => (
                              <button
                                key={admin.id}
                                type="button"
                                onClick={() => !meetEmailRecipients.includes(admin.email!.toLowerCase()) && addMeetEmailRecipient(admin.email!)}
                                disabled={meetEmailRecipients.includes(admin.email!.toLowerCase())}
                                className="flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold border rounded-lg transition-all disabled:opacity-40 disabled:cursor-not-allowed bg-white text-slate-600 border-slate-200 hover:border-emerald-300 hover:text-emerald-600"
                              >
                                {meetEmailRecipients.includes(admin.email!.toLowerCase())
                                  ? <><i className="fas fa-check text-emerald-500"></i> {admin.username}</>
                                  : <><i className="fas fa-plus text-[9px]"></i> {admin.username}</>
                                }
                              </button>
                            ))}
                          </div>
                        )}

                        <button
                          type="button"
                          onClick={handleSendMeetEmail}
                          disabled={isSendingMeetEmail || (meetEmailRecipients.length === 0 && !meetEmailInput.trim())}
                          className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-sky-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-sky-500/20 hover:bg-sky-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed active:scale-95"
                        >
                          {isSendingMeetEmail
                            ? <><i className="fas fa-circle-notch fa-spin"></i> Sending…</>
                            : <><i className="fas fa-paper-plane"></i> Send Meeting Invite</>
                          }
                        </button>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* Footer */}
            <div className="px-6 py-3 border-t border-slate-100 bg-slate-50/50 flex justify-end flex-shrink-0">
              <button
                type="button"
                onClick={closeMeetModal}
                className="px-4 py-2 text-sm font-semibold text-slate-600 bg-white border border-slate-200 hover:bg-slate-100 rounded-xl transition-colors"
              >
                Close
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* ── Sticky Note Panel (enhanced) ─────────────────────────────────────── */}
      {showNotePanel && ReactDOM.createPortal(
        <>
          {/* Quill style overrides scoped to the note panel */}
          <style>{`
            .note-panel-quill .ql-toolbar{background:rgba(255,255,255,.22)!important;border:none!important;border-bottom:1px solid rgba(0,0,0,.07)!important;padding:5px 12px!important;flex-shrink:0}
            .note-panel-quill .ql-container{background:transparent!important;border:none!important;font-size:13px!important;font-family:inherit!important;overflow:auto;flex:1}
            .note-panel-quill .ql-editor{background:transparent!important;padding:14px 20px!important;line-height:2!important;color:inherit!important;min-height:120px}
            .note-panel-quill .ql-editor.ql-blank::before{font-style:normal!important;color:inherit!important;opacity:.38!important;font-size:13px!important}
            .note-panel-quill .ql-toolbar button:hover .ql-stroke,.note-panel-quill .ql-toolbar button.ql-active .ql-stroke{stroke:rgba(0,0,0,.65)!important}
            .note-panel-quill .ql-toolbar button:hover .ql-fill,.note-panel-quill .ql-toolbar button.ql-active .ql-fill{fill:rgba(0,0,0,.65)!important}
            .note-panel-quill .ql-snow .ql-stroke{stroke:rgba(0,0,0,.45)}
            .note-panel-quill .ql-snow .ql-fill{fill:rgba(0,0,0,.45)}
          `}</style>

          {/* Panel — no backdrop, floats over the right side so chat remains interactive */}
          <div
            className="fixed right-0 top-20 bottom-0 z-50 flex flex-col shadow-2xl overflow-hidden"
            style={{ width: noteWidth, background: NOTE_COLORS[noteColor].bg, color: NOTE_COLORS[noteColor].text }}
          >
              {/* ── Drag-resize handle ─────────────────────────────────── */}
              <div
                className="absolute left-0 top-0 bottom-0 w-1.5 cursor-col-resize z-20 group flex items-center justify-center hover:bg-black/10 transition-colors"
                onMouseDown={startNoteDrag}
                title="Drag to resize"
              >
                <div className="w-0.5 h-8 rounded-full bg-current opacity-20 group-hover:opacity-50 group-hover:h-14 transition-all" />
              </div>

              {/* ── Header ─────────────────────────────────────────────── */}
              <div className="flex items-center justify-between pl-6 pr-4 pt-4 pb-3 border-b border-current/10 flex-shrink-0">
                <div className="flex items-center gap-2">
                  <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.08)' }}>
                    <i className="fas fa-lock text-xs" />
                  </div>
                  <div>
                    <div className="text-[11px] font-black uppercase tracking-widest leading-none">Internal Note</div>
                    <div className="text-[9px] font-bold opacity-50 mt-0.5">Admin only · Ctrl/⌘+Shift+N</div>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  {/* Color theme picker */}
                  {(['yellow', 'blue', 'green', 'pink'] as const).map(c => (
                    <button
                      key={c}
                      onClick={() => setNoteColorAndSave(c)}
                      style={{ background: NOTE_COLORS[c].btn }}
                      className={`w-3.5 h-3.5 rounded-full transition-all border-2 ${noteColor === c ? 'scale-125 border-current/60' : 'border-transparent opacity-55 hover:opacity-100 hover:scale-110'}`}
                      title={c.charAt(0).toUpperCase() + c.slice(1)}
                    />
                  ))}
                  <div className="w-px h-4 bg-current/20 mx-1" />
                  <button
                    onClick={() => setShowNotePanel(false)}
                    className="w-7 h-7 flex items-center justify-center rounded-lg transition-colors hover:bg-current/10"
                  >
                    <i className="fas fa-times text-sm" />
                  </button>
                </div>
              </div>

              {/* ── Tab bar ────────────────────────────────────────────── */}
              <div className="flex border-b border-current/10 pl-5 gap-1 flex-shrink-0">
                <button
                  onClick={() => setNoteTab('note')}
                  className={`px-3 py-2.5 text-[10px] font-black uppercase tracking-widest transition-all border-b-2 flex items-center gap-1.5 ${noteTab === 'note' ? 'border-current/60 opacity-100' : 'border-transparent opacity-45 hover:opacity-70'}`}
                >
                  <i className="fas fa-pen text-[9px]" /> Note
                </button>
                <button
                  onClick={() => setNoteTab('tasks')}
                  className={`px-3 py-2.5 text-[10px] font-black uppercase tracking-widest transition-all border-b-2 flex items-center gap-1.5 ${noteTab === 'tasks' ? 'border-current/60 opacity-100' : 'border-transparent opacity-45 hover:opacity-70'}`}
                >
                  <i className="fas fa-check-square text-[9px]" /> Tasks
                  {noteTasks.filter(t => !t.done).length > 0 && (
                    <span className="w-4 h-4 rounded-full flex items-center justify-center text-[8px] font-black" style={{ background: 'rgba(0,0,0,0.12)' }}>
                      {noteTasks.filter(t => !t.done).length}
                    </span>
                  )}
                </button>
              </div>

              {/* ── Note Editor tab (always in DOM; hidden via CSS when tasks active) ── */}
              <div className={`flex-1 flex flex-col overflow-hidden relative ${noteTab !== 'note' ? 'hidden' : ''}`}>
                {/* Lined paper decoration */}
                <div className="absolute inset-0 pointer-events-none" style={{
                  backgroundImage: `repeating-linear-gradient(transparent,transparent 31px,${NOTE_COLORS[noteColor].line} 31px,${NOTE_COLORS[noteColor].line} 32px)`,
                  backgroundPosition: '0 72px',
                }} />
                <div className="relative flex-1 overflow-hidden flex flex-col note-panel-quill">
                  <div ref={noteQuillRef} />
                </div>
              </div>

              {/* ── Tasks tab ──────────────────────────────────────────── */}
              <div className={`flex-1 flex flex-col overflow-hidden ${noteTab !== 'tasks' ? 'hidden' : ''}`}>
                {/* Add task input */}
                <div className="px-5 pt-4 pb-2 flex gap-2 flex-shrink-0">
                  <input
                    type="text"
                    value={newTaskText}
                    onChange={e => setNewTaskText(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && addTask()}
                    placeholder="Add a task & press Enter…"
                    className="flex-1 rounded-xl px-3 py-2 text-sm font-medium outline-none transition-all"
                    style={{ background: 'rgba(255,255,255,0.45)', border: '1.5px solid rgba(0,0,0,0.08)', color: NOTE_COLORS[noteColor].text }}
                  />
                  <button
                    onClick={addTask}
                    className="w-9 h-9 flex items-center justify-center rounded-xl font-black text-sm transition-all active:scale-95"
                    style={{ background: 'rgba(0,0,0,0.12)' }}
                  >
                    <i className="fas fa-plus text-xs" />
                  </button>
                </div>
                <div className="flex-1 overflow-y-auto px-5 pb-4 space-y-2">
                  {noteTasks.length === 0 ? (
                    <div className="py-12 text-center opacity-40">
                      <i className="fas fa-clipboard-list text-3xl mb-3 block" />
                      <p className="text-xs font-bold">No tasks yet — add one above</p>
                    </div>
                  ) : noteTasks.map(task => (
                    <div
                      key={task.id}
                      className={`flex items-center gap-3 px-3 py-2.5 rounded-xl group transition-all ${task.done ? 'opacity-50' : ''}`}
                      style={{ background: 'rgba(255,255,255,0.35)', border: '1px solid rgba(0,0,0,0.06)' }}
                    >
                      <button
                        onClick={() => toggleTask(task.id)}
                        className="w-5 h-5 rounded flex-shrink-0 flex items-center justify-center border-2 transition-all"
                        style={{ borderColor: 'rgba(0,0,0,0.3)', background: task.done ? 'rgba(0,0,0,0.25)' : 'transparent' }}
                      >
                        {task.done && <i className="fas fa-check text-[8px]" />}
                      </button>
                      <span className={`flex-1 text-sm font-medium leading-snug ${task.done ? 'line-through opacity-60' : ''}`}>{task.text}</span>
                      <button onClick={() => deleteTask(task.id)} className="opacity-0 group-hover:opacity-60 hover:!opacity-100 transition-opacity">
                        <i className="fas fa-times text-[10px]" />
                      </button>
                    </div>
                  ))}
                  {noteTasks.length > 0 && (
                    <p className="text-center text-[10px] font-bold opacity-35 pt-2">
                      {noteTasks.filter(t => t.done).length} of {noteTasks.length} completed
                    </p>
                  )}
                </div>
              </div>

              {/* ── Footer ─────────────────────────────────────────────── */}
              <div className="pl-6 pr-4 py-3 border-t border-current/10 flex items-center justify-between gap-2 flex-shrink-0">
                {/* Saved / word count */}
                <div className="min-w-0">
                  {noteSaved ? (
                    <span className="flex items-center gap-1 text-[9px] font-black text-emerald-600 uppercase tracking-widest">
                      <i className="fas fa-check-circle" /> Saved
                    </span>
                  ) : (
                    <span className="text-[9px] font-bold opacity-40 uppercase tracking-widest">
                      {noteTab === 'note'
                        ? `${noteWordCount} word${noteWordCount !== 1 ? 's' : ''}`
                        : `${noteTasks.filter(t => t.done).length}/${noteTasks.length} done`}
                    </span>
                  )}
                </div>

                {/* Action buttons */}
                <div className="flex items-center gap-1">
                  {/* History (note tab only) */}
                  {noteTab === 'note' && noteHistory.length > 0 && (
                    <div className="relative">
                      <button
                        onClick={() => setShowNoteHistory(v => !v)}
                        title="Restore a previous version"
                        className="w-7 h-7 flex items-center justify-center rounded-lg transition-colors hover:bg-current/10 opacity-50 hover:opacity-100"
                      >
                        <i className="fas fa-history text-[11px]" />
                      </button>
                      {showNoteHistory && (
                        <div className="absolute bottom-9 right-0 w-64 bg-white rounded-xl shadow-2xl border border-slate-100 overflow-hidden z-30">
                          <div className="px-3 py-2 text-[9px] font-black text-slate-400 uppercase tracking-widest border-b border-slate-50 flex items-center justify-between">
                            <span>Restore version</span>
                            <button onClick={() => setShowNoteHistory(false)} className="text-slate-300 hover:text-slate-500"><i className="fas fa-times" /></button>
                          </div>
                          {noteHistory.map((entry, i) => (
                            <button
                              key={i}
                              onClick={() => {
                                if (noteQuillInstance.current) {
                                  noteQuillInstance.current.root.innerHTML = entry.html;
                                  setNoteHtml(entry.html);
                                }
                                setShowNoteHistory(false);
                              }}
                              className="w-full text-left px-3 py-2.5 hover:bg-slate-50 transition-colors border-b border-slate-50 last:border-0"
                            >
                              <div className="text-[9px] font-black text-indigo-500 uppercase tracking-widest mb-1">
                                {new Date(entry.savedAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', dateStyle: 'short', timeStyle: 'short' })}
                              </div>
                              <div className="text-xs text-slate-600 font-medium line-clamp-2 leading-snug">
                                {(() => { const d = document.createElement('div'); d.innerHTML = entry.html; return d.textContent || ''; })()}
                              </div>
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  )}

                  {/* Copy to clipboard */}
                  <button
                    onClick={handleCopyNote}
                    title="Copy to clipboard"
                    className="w-7 h-7 flex items-center justify-center rounded-lg transition-colors hover:bg-current/10 opacity-50 hover:opacity-100"
                  >
                    <i className="fas fa-copy text-[11px]" />
                  </button>

                  {/* Post as internal note in thread */}
                  {noteTab === 'note' && ticket.status !== 'CLOSED' && noteWordCount > 0 && (
                    <button
                      onClick={handlePostNoteAsInternal}
                      disabled={isPostingNote}
                      title="Post as internal message in ticket thread"
                      className="flex items-center gap-1 px-2.5 py-1.5 text-[9px] font-black uppercase tracking-widest rounded-lg border border-current/20 transition-all active:scale-95 disabled:opacity-40 hover:bg-current/10"
                    >
                      {isPostingNote ? <i className="fas fa-circle-notch fa-spin text-[9px]" /> : <i className="fas fa-share text-[9px]" />}
                      Post
                    </button>
                  )}

                  {/* Clear */}
                  {((noteTab === 'note' && noteWordCount > 0) || (noteTab === 'tasks' && noteTasks.length > 0)) && (
                    <button
                      onClick={() => {
                        if (noteTab === 'note') {
                          if (noteQuillInstance.current) noteQuillInstance.current.root.innerHTML = '';
                          setNoteHtml('');
                          setNoteWordCount(0);
                          if (ticketId) {
                            localStorage.removeItem(`xamops_ticket_note_html_${ticketId}`);
                            localStorage.removeItem(`xamops_ticket_note_${ticketId}`);
                          }
                        } else {
                          setNoteTasks([]);
                          if (ticketId) localStorage.removeItem(`xamops_ticket_tasks_${ticketId}`);
                        }
                      }}
                      className="flex items-center gap-1 px-2.5 py-1.5 text-[9px] font-black uppercase tracking-widest rounded-lg border border-current/20 transition-all active:scale-95 opacity-50 hover:opacity-100 hover:bg-red-50 hover:text-red-600 hover:border-red-200"
                    >
                      <i className="fas fa-trash-alt text-[8px]" /> Clear
                    </button>
                  )}
                </div>
              </div>
            </div>
        </>,
        document.body
      )}
    </div>
  );
};

export default BillOpsTicketDetailPage;
