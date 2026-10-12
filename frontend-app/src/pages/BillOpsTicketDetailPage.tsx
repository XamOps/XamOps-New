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
import { Icon } from '@iconify/react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useBillOpsTicketDetail } from '../hooks/useBillOpsTicketDetail';
import { RCAFormData, RCAFormSection, TicketReply } from '../types/billopsTickets';
import { useTopbarActions } from '../contexts/TopbarContext';
import { getResponseSlaLabel } from '../utils/timeUtils';
import { useSlaCountdown } from '../components/tickets/SlaCountdown';
import { sanitizeRichText, htmlToPlainText, escapeHtml } from '../utils/sanitizeHtml';
import CannedResponsesPicker from '../components/tickets/CannedResponsesPicker';
import TicketEmailDetails from '../components/tickets/TicketEmailDetails';
import TicketCallButton from '../components/calls/TicketCallButton';
import TicketCallHistory from '../components/calls/TicketCallHistory';
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

/**
 * System call summaries are posted into the thread by CallService as
 * `📞 <em>Video call ended · 2m 52s · alice, bob</em>`. They are events, not
 * conversation, so they get their own card style instead of a chat bubble.
 */
function parseCallEvent(html?: string): { label: string; meta: string[] } | null {
  if (!html) return null;
  const text = html.replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
  if (!text.startsWith('📞')) return null;
  if (!/call ended/i.test(text)) return null;
  const segments = text.replace(/^📞\s*/, '').split('·').map((s) => s.trim()).filter(Boolean);
  if (segments.length === 0) return null;
  return { label: segments[0], meta: segments.slice(1) };
}

const BillOpsTicketDetailPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const ticketId = searchParams.get('id');
  // When arriving from the admin Chats search, this is the term that matched inside the
  // conversation. The detail view scrolls to and highlights the first message containing it.
  const highlightTerm = searchParams.get('highlight') || '';
  const { setActions, clearActions } = useTopbarActions();

  const {
    ticket,
    currentUser,
    loading,
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
  const [draftRestored, setDraftRestored] = useState(false);
  const draftSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Edit / Delete state
  const [editingReplyId, setEditingReplyId] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');
  const [editWidth, setEditWidth] = useState<number | null>(null);

  // Activity feed filter — "all" shows the call history + system event cards,
  // "messages" strips them so an agent can read the conversation without noise.
  const [activityFilter, setActivityFilter] = useState<'all' | 'messages'>('all');

  // Internal note toggle (kept for submit path only)
  const [isInternalNote, setIsInternalNote] = useState(false);

  // Sticky note side panel — core
  const [showNotePanel, setShowNotePanel] = useState(false);
  const [noteSaved, setNoteSaved] = useState(false);
  const noteSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Holds the latest loaded/edited note HTML so the Quill init can seed content
  // without relying on async state timing.
  const noteHtmlRef = useRef('');

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
  const hasInitialScrolled = useRef(false);

  // File attachments (multiple)
  const [attachments, setAttachments] = useState<File[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Optimistic "sending" bubbles: a reply is cleared from the editor the instant Send is pressed
  // and shown here at the bottom of the thread with a sending/spinner state, until the server
  // responds and the real reply arrives (or it fails and we drop/restore it).
  interface PendingReply { tempId: string; html: string; isInternal: boolean; failed: boolean; }
  const [pendingReplies, setPendingReplies] = useState<PendingReply[]>([]);

  // Assignment dropdown state
  const [showAssignDropdown, setShowAssignDropdown] = useState(false);
  const [isAssigning, setIsAssigning] = useState(false);
  const [showActionsDropdown, setShowActionsDropdown] = useState(false);

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

  // Live stop-the-clock resolution SLA. Called unconditionally (before any early return) to
  // satisfy the rules of hooks; tolerates a not-yet-loaded ticket via safe defaults.
  const { sla: slaStatus, bigLabel: slaBigLabel } = useSlaCountdown({
    createdAt: ticket?.createdAt ?? '',
    severity: ticket?.severity ?? '',
    isClosed: ticket?.status === 'CLOSED',
    closedAt: ticket?.closedAt,
    slaActiveMs: ticket?.slaActiveMs,
    slaRunningSince: ticket?.slaRunningSince,
  });

  const getStatusBadgeClasses = (status: string) => {
    if (status === 'OPEN') return 'bg-emerald-50 text-emerald-700 border border-emerald-200';
    if (status === 'IN_PROGRESS') return 'bg-amber-50 text-amber-700 border border-amber-200';
    return 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)] border border-[rgba(0,0,0,0.12)]';
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
    const selected = Array.from(event.target.files || []);
    if (selected.length === 0) return;
    const tooBig = selected.find((f) => f.size > 50 * 1024 * 1024);
    if (tooBig) {
      globalThis.alert(`"${tooBig.name}" is too large. Max size is 50MB per file.`);
      return;
    }
    setAttachments((prev) => [...prev, ...selected]);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const removeAttachment = useCallback((index: number) => {
    setAttachments((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const clearFile = useCallback(() => {
    setAttachments([]);
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, []);

  /** Insert a canned-response template into the reply editor at the cursor. */
  const insertCannedResponse = useCallback((body: string) => {
    const quill = quillInstance.current;
    if (!quill) return;
    const sel = quill.getSelection(true);
    const index = sel ? sel.index : quill.getLength();
    quill.insertText(index, body);
    quill.setSelection(index + body.length, 0);
    quill.focus();
    setNewReplyHasContent(quill.getText().trim().length > 0);
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

    // Restore any autosaved draft for this ticket (survives refresh/navigation)
    const draftKey = ticketId ? `xamops_ticket_draft_${ticketId}` : '';
    if (draftKey) {
      const savedDraft = localStorage.getItem(draftKey);
      if (savedDraft) {
        quillInstance.current.root.innerHTML = sanitizeRichText(savedDraft);
        setNewReplyHasContent(quillInstance.current.getText().trim().length > 0);
        setDraftRestored(true);
      }
    }

    quillInstance.current.on('text-change', () => {
      if (quillInstance.current) {
        const text = quillInstance.current.getText().trim();
        setNewReplyHasContent(text.length > 0);
        // Debounced draft autosave
        if (draftKey) {
          if (draftSaveTimerRef.current) clearTimeout(draftSaveTimerRef.current);
          draftSaveTimerRef.current = setTimeout(() => {
            if (!quillInstance.current) return;
            if (quillInstance.current.getText().trim().length > 0) {
              localStorage.setItem(draftKey, quillInstance.current.root.innerHTML);
            } else {
              localStorage.removeItem(draftKey);
              setDraftRestored(false);
            }
          }, 400);
        }
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
            if (file.size > 50 * 1024 * 1024) {
              globalThis.alert('Pasted image is too large. Max size is 50MB.');
              return;
            }
            setAttachments((prev) => [...prev, file]);
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
   * Scroll to bottom on initial load; on new replies only scroll when already near bottom
   * so we don't yank users away from older messages they're reading.
   */
  useEffect(() => {
    if (!chatContainerRef.current || !ticket?.replies?.length) return;
    // When we arrived with a ?highlight term, the highlight effect below owns the initial scroll
    // (to the matched message, not the bottom). Skip the bottom-scroll on first load so the two
    // don't fight; later new-reply auto-scroll still works once the user is near the bottom.
    if (highlightTerm && !hasInitialScrolled.current) {
      hasInitialScrolled.current = true;
      return;
    }
    setTimeout(() => {
      const el = chatContainerRef.current;
      if (!el) return;
      if (!hasInitialScrolled.current) {
        el.scrollTop = el.scrollHeight;
        hasInitialScrolled.current = true;
      } else {
        const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
        if (distFromBottom < 200) el.scrollTop = el.scrollHeight;
      }
    }, 100);
  }, [ticket?.replies, highlightTerm]);

  /**
   * Scroll to and highlight the message that matched the admin Chats search. Runs once per load
   * when a ?highlight term is present: finds the first non-deleted reply whose text contains the
   * term, scrolls its bubble into view, and flashes a ring around it. Pure DOM — no persistent
   * state, so the pulse fades on its own and leaves the thread untouched.
   */
  const didHighlightRef = useRef(false);
  useEffect(() => {
    if (!highlightTerm || didHighlightRef.current) return;
    if (!ticket?.replies?.length) return;

    const term = highlightTerm.toLowerCase();
    const matchingReply = ticket.replies.find(r =>
      !r.deleted && typeof r.message === 'string' &&
      r.message.replace(/<[^>]*>/g, ' ').toLowerCase().includes(term)
    );

    // Prefer a matching reply; otherwise, if the opening description contains the term, flash the
    // "Original Issue Description" card (the first message isn't rendered as a reply bubble).
    const descriptionMatches =
      typeof ticket.description === 'string' &&
      ticket.description.replace(/<[^>]*>/g, ' ').toLowerCase().includes(term);

    const targetId = matchingReply ? `msg-${matchingReply.id}` : descriptionMatches ? 'msg-description' : null;
    if (!targetId) return;

    didHighlightRef.current = true;
    // Let the bubbles render first, then scroll to and flash the match.
    const timer = setTimeout(() => {
      const node = document.getElementById(targetId);
      if (!node) return;
      node.scrollIntoView({ behavior: 'smooth', block: 'center' });
      node.classList.add('chat-search-flash');
      setTimeout(() => node.classList.remove('chat-search-flash'), 2600);
    }, 250);
    return () => clearTimeout(timer);
  }, [highlightTerm, ticket?.replies, ticket?.description]);

  /**
   * Persist one or more note fields to the backend (partial update).
   * The note pad is shared per-ticket across all agents.
   */
  const saveNoteFields = useCallback(async (fields: {
    noteHtml?: string;
    tasksJson?: string;
    color?: string;
    historyJson?: string;
  }) => {
    if (!ticketId) return;
    try {
      await fetch(`/api/billops/tickets/${ticketId}/note`, {
        method: 'PUT',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(fields),
      });
    } catch (e) {
      console.error('Failed to save ticket note', e);
    }
  }, [ticketId]);

  /**
   * Load the shared note (HTML, tasks, color, history) from the backend.
   * One-time migration: if the server has no note yet but this browser has
   * legacy localStorage notes, seed the server from them and clear the keys.
   */
  useEffect(() => {
    if (!ticketId || !isAdmin) return;
    let cancelled = false;

    const applyHtml = (html: string) => {
      noteHtmlRef.current = html;
      setNoteHtml(html);
      if (html) {
        const text = htmlToPlainText(html).trim();
        setNoteWordCount(text ? text.split(/\s+/).filter(Boolean).length : 0);
      } else {
        setNoteWordCount(0);
      }
    };

    (async () => {
      try {
        const res = await fetch(`/api/billops/tickets/${ticketId}/note`, { credentials: 'include' });
        const data = res.ok ? await res.json() : {};
        if (cancelled) return;

        const hasServerNote = data && (data.noteHtml || data.tasksJson || data.color || data.historyJson);
        if (hasServerNote) {
          applyHtml(data.noteHtml || '');
          try { setNoteTasks(data.tasksJson ? JSON.parse(data.tasksJson) : []); } catch { setNoteTasks([]); }
          if (data.color && NOTE_COLORS[data.color as keyof typeof NOTE_COLORS]) {
            setNoteColor(data.color as keyof typeof NOTE_COLORS);
          }
          try { setNoteHistory(data.historyJson ? JSON.parse(data.historyJson) : []); } catch { setNoteHistory([]); }
          return;
        }

        // No server note — migrate from legacy localStorage (if any)
        const oldText = localStorage.getItem(`xamops_ticket_note_${ticketId}`);
        const legacyHtml = localStorage.getItem(`xamops_ticket_note_html_${ticketId}`)
          || (oldText ? `<p>${escapeHtml(oldText).replace(/\n/g, '</p><p>')}</p>` : '');
        let legacyTasks = '[]';
        try { legacyTasks = localStorage.getItem(`xamops_ticket_tasks_${ticketId}`) || '[]'; } catch { /* ignore */ }
        const legacyColor = localStorage.getItem(`xamops_ticket_note_color_${ticketId}`) || '';
        const legacyHistory = localStorage.getItem(`xamops_ticket_note_history_${ticketId}`) || '[]';

        applyHtml(legacyHtml);
        try { setNoteTasks(JSON.parse(legacyTasks)); } catch { setNoteTasks([]); }
        if (legacyColor && NOTE_COLORS[legacyColor as keyof typeof NOTE_COLORS]) {
          setNoteColor(legacyColor as keyof typeof NOTE_COLORS);
        }
        try { setNoteHistory(JSON.parse(legacyHistory)); } catch { setNoteHistory([]); }

        if (legacyHtml || legacyTasks !== '[]' || legacyColor) {
          await saveNoteFields({
            noteHtml: legacyHtml,
            tasksJson: legacyTasks,
            color: legacyColor || 'yellow',
            historyJson: legacyHistory,
          });
          // Clear migrated keys so they don't override the server later
          localStorage.removeItem(`xamops_ticket_note_${ticketId}`);
          localStorage.removeItem(`xamops_ticket_note_html_${ticketId}`);
          localStorage.removeItem(`xamops_ticket_tasks_${ticketId}`);
          localStorage.removeItem(`xamops_ticket_note_color_${ticketId}`);
          localStorage.removeItem(`xamops_ticket_note_history_${ticketId}`);
        }
      } catch (e) {
        console.error('Failed to load ticket note', e);
      }
    })();

    return () => { cancelled = true; };
  }, [ticketId, isAdmin, saveNoteFields]);

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

  // Close actions dropdown on outside click
  useEffect(() => {
    if (!showActionsDropdown) return;
    const handler = (e: MouseEvent) => {
      const el = document.querySelector('[data-actions-dropdown]');
      if (el && !el.contains(e.target as Node)) setShowActionsDropdown(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showActionsDropdown]);

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

      // Seed content from the loaded note (ref avoids stale-state timing issues)
      const savedHtml = noteHtmlRef.current || '';
      if (savedHtml) noteQuillInstance.current.root.innerHTML = sanitizeRichText(savedHtml);

      noteQuillInstance.current.on('text-change', () => {
        if (!noteQuillInstance.current) return;
        const html = noteQuillInstance.current.root.innerHTML;
        const text = noteQuillInstance.current.getText().trim();
        noteHtmlRef.current = html;
        setNoteHtml(html);
        setNoteWordCount(text ? text.split(/\s+/).filter(Boolean).length : 0);
        if (noteSaveTimerRef.current) clearTimeout(noteSaveTimerRef.current);
        noteSaveTimerRef.current = setTimeout(() => {
          if (!ticketId) return;
          const entry = { html, savedAt: Date.now() };
          setNoteHistory(prev => {
            const updated = [entry, ...prev.slice(0, 2)];
            saveNoteFields({ noteHtml: html, historyJson: JSON.stringify(updated) });
            return updated;
          });
          setNoteSaved(true);
          setTimeout(() => setNoteSaved(false), 2000);
        }, 500);
      });
    }, 50);
    return () => clearTimeout(timer);
  }, [showNotePanel, ticketId, saveNoteFields]);

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
      <div className="flex items-center gap-3">
        <button
          onClick={goBackToTickets}
          className="w-8 h-8 flex items-center justify-center rounded-md bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.08)] transition-colors flex-shrink-0"
          title="Back to List"
        >
          <Icon icon="mdi:arrow-left" width={14} />
        </button>

        <div className="hidden lg:block min-w-0 pr-3 border-r border-[rgba(0,0,0,0.12)]">
          <h1 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] leading-none truncate max-w-[240px] xl:max-w-[400px]">
            {ticket.subject}
          </h1>
          <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-0.5 truncate max-w-[240px] xl:max-w-[400px]">
            Case #{ticket.id} · {ticket.service}
          </p>
        </div>

        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11px] font-medium ${getStatusBadgeClasses(ticket.status)}`}>
          {ticket.status === 'OPEN' && <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse flex-shrink-0"></span>}
          {ticket.status === 'IN_PROGRESS' && <span className="w-1.5 h-1.5 rounded-full bg-amber-500 flex-shrink-0"></span>}
          {ticket.status === 'IN_PROGRESS' ? 'In Progress' : ticket.status === 'OPEN' ? 'Open' : 'Closed'}
        </span>

        <TicketCallButton ticketId={ticket.id} disabled={ticket.status === 'CLOSED'} />

        {/* Assign dropdown (admin only) */}
        {isAdmin && (
          <div className="relative" data-assign-dropdown>
            <button
              onClick={() => setShowAssignDropdown(v => !v)}
              disabled={isAssigning || ticket.status === 'CLOSED'}
              className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[12px] font-medium border transition-colors ${
                ticket.assignedToUsername
                  ? 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.88)] border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.06)]'
                  : 'bg-white text-[rgba(0,0,0,0.5)] border-[rgba(0,0,0,0.12)] hover:border-[rgba(0,0,0,0.18)] hover:text-[rgba(0,0,0,0.88)]'
              } disabled:opacity-50 disabled:cursor-not-allowed`}
            >
              {ticket.assignedToUsername ? (
                <>
                  <div className="w-4 h-4 rounded-md bg-[rgba(0,0,0,0.88)] flex items-center justify-center text-white text-[8px] font-semibold flex-shrink-0">
                    {ticket.assignedToUsername.charAt(0).toUpperCase()}
                  </div>
                  <span className="hidden xl:inline truncate max-w-[80px]">{ticket.assignedToUsername}</span>
                </>
              ) : (
                <><Icon icon="mdi:account-plus-outline" width={12} /><span className="hidden xl:inline ml-1">Assign</span></>
              )}
              {!isAssigning && ticket.status !== 'CLOSED' && <Icon icon="mdi:chevron-down" width={12} className="opacity-50 ml-0.5" />}
              {isAssigning && <Icon icon="mdi:loading" width={12} className="animate-spin ml-0.5" />}
            </button>

            {showAssignDropdown && adminUsers.length > 0 && (
              <div className="absolute top-full right-0 mt-1 w-48 bg-white rounded-md shadow-lg border border-[rgba(0,0,0,0.06)] py-1 z-50 overflow-hidden">
                <div className="px-3 py-1.5 text-[10px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest border-b border-[rgba(0,0,0,0.06)]">Assign to</div>
                {adminUsers.map(admin => (
                  <button
                    key={admin.id}
                    onClick={() => handleAssign(admin.id, admin.username)}
                    className={`w-full text-left flex items-center gap-2 px-3 py-2 text-[12px] hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.88)] transition-colors ${
                      ticket.assignedToId === admin.id ? 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.6)]'
                    }`}
                  >
                    <div className="w-5 h-5 rounded-md bg-[rgba(0,0,0,0.88)] flex items-center justify-center text-white text-[9px] font-semibold flex-shrink-0">
                      {admin.username.charAt(0).toUpperCase()}
                    </div>
                    {admin.username}
                    {ticket.assignedToId === admin.id && <Icon icon="mdi:check" width={14} className="text-[rgba(0,0,0,0.4)] ml-auto" />}
                  </button>
                ))}
                {ticket.assignedToUsername && (
                  <button
                    onClick={() => handleAssign(0, '')}
                    className="w-full text-left flex items-center gap-2 px-3 py-2 text-[12px] text-red-500 hover:bg-red-50 transition-colors border-t border-[rgba(0,0,0,0.06)]"
                  >
                    <Icon icon="mdi:account-remove-outline" width={12} />
                    Unassign
                  </button>
                )}
              </div>
            )}
          </div>
        )}

        {/* Actions dropdown */}
        <div className="relative" data-actions-dropdown>
          <button
            onClick={() => setShowActionsDropdown(v => !v)}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
          >
            <Icon icon="mdi:lightning-bolt" width={12} />
            <span className="hidden lg:inline">Actions</span>
            <Icon icon="mdi:chevron-down" width={12} className="opacity-70 ml-0.5" />
          </button>

          {showActionsDropdown && (
            <div className="absolute top-full right-0 mt-1 w-52 bg-white rounded-md shadow-lg border border-[rgba(0,0,0,0.06)] py-1 z-50 overflow-hidden">
              {isAdmin && ticket.status !== 'CLOSED' && (
                <button
                  onClick={() => { setShowRcaModal(true); setShowActionsDropdown(false); }}
                  className="w-full text-left flex items-center gap-3 px-4 py-2.5 text-[12px] text-[rgba(0,0,0,0.6)] hover:bg-purple-50 hover:text-purple-700 transition-colors"
                >
                  <Icon icon="mdi:file-document-edit-outline" width={16} className="text-purple-500 flex-shrink-0" />
                  Generate RCA
                </button>
              )}
              {isAdmin && ticket.status !== 'CLOSED' && (
                <button
                  onClick={() => { openMeetModal(); setShowActionsDropdown(false); }}
                  className="w-full text-left flex items-center gap-3 px-4 py-2.5 text-[12px] text-[rgba(0,0,0,0.6)] hover:bg-emerald-50 hover:text-emerald-700 transition-colors"
                >
                  <Icon icon="mdi:video-outline" width={16} className="text-emerald-500 flex-shrink-0" />
                  Schedule Meet
                </button>
              )}
              {isAdmin && (ticket.replies?.length ?? 0) > 0 && (
                <button
                  onClick={() => { setShowEmailModal(true); setShowActionsDropdown(false); }}
                  className="w-full text-left flex items-center gap-3 px-4 py-2.5 text-[12px] text-[rgba(0,0,0,0.6)] hover:bg-sky-50 hover:text-sky-700 transition-colors"
                >
                  <Icon icon="mdi:email-outline" width={16} className="text-sky-500 flex-shrink-0" />
                  Email Thread
                </button>
              )}
              {ticket.status !== 'CLOSED' && (
                <button
                  onClick={() => { handleCloseTicket(); setShowActionsDropdown(false); }}
                  className="w-full text-left flex items-center gap-3 px-4 py-2.5 text-[12px] text-red-600 hover:bg-red-50 transition-colors border-t border-[rgba(0,0,0,0.06)]"
                >
                  <Icon icon="mdi:close-circle-outline" width={16} className="text-red-500 flex-shrink-0" />
                  Close Ticket
                </button>
              )}
              {isAdmin && ticket.status === 'CLOSED' && (
                <button
                  onClick={() => { handleReopenTicket(); setShowActionsDropdown(false); }}
                  className="w-full text-left flex items-center gap-3 px-4 py-2.5 text-[12px] text-emerald-700 hover:bg-emerald-50 transition-colors"
                >
                  <Icon icon="mdi:lock-open-outline" width={16} className="text-emerald-500 flex-shrink-0" />
                  Reopen Ticket
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    );

    return () => clearActions();
  }, [setActions, clearActions, ticket, isAdmin, handleCloseTicket, handleReopenTicket, goBackToTickets,
      adminUsers, showAssignDropdown, isAssigning, handleAssign, showEmailModal, openMeetModal, showActionsDropdown]);

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
      await submitReply(html, [], false);
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
    if (!textContent && attachments.length === 0 && !hasEmbeddedImage) return;

    let htmlMessage = (textContent || hasEmbeddedImage) ? rawHtml : '';

    // Extract any base64 image from the HTML and convert to a file attachment.
    // This handles cases where Quill embeds a pasted image before our clipboard
    // matcher can capture it, or when the async fetch hasn't completed yet.
    const filesToSend = [...attachments];
    const b64Match = htmlMessage.match(/<img[^>]+src="(data:image\/[^;]+;base64,[^"]+)"[^>]*>/i);
    if (b64Match && filesToSend.length === 0) {
      const dataUrl = b64Match[1];
      const byteString = atob(dataUrl.split(',')[1]);
      const mimeType = dataUrl.match(/data:([^;]+)/)?.[1] || 'image/png';
      const ab = new ArrayBuffer(byteString.length);
      const ia = new Uint8Array(ab);
      for (let i = 0; i < byteString.length; i++) ia[i] = byteString.charCodeAt(i);
      filesToSend.push(new File([ab], 'pasted-image.png', { type: mimeType }));
    }
    // Always strip base64 images from the message HTML
    htmlMessage = htmlMessage.replace(/<img[^>]+src="data:[^"]*"[^>]*>/gi, '');

    const wasInternal = isInternalNote;

    // Snapshot for an optimistic bubble, and a label when there's no text (attachment-only send).
    const optimisticHtml = htmlMessage || (filesToSend.length > 0
      ? `<p class="italic opacity-70">Sending ${filesToSend.length} attachment${filesToSend.length > 1 ? 's' : ''}…</p>`
      : '');
    const tempId = `pending-${Date.now()}-${Math.random().toString(36).slice(2)}`;

    // ── Clear the editor and show the message in the thread immediately (optimistic send) ──
    quillInstance.current.root.innerHTML = '';
    setNewReplyHasContent(false);
    if (ticketId) localStorage.removeItem(`xamops_ticket_draft_${ticketId}`);
    setDraftRestored(false);
    clearFile();
    setIsInternalNote(false);
    setPendingReplies(prev => [...prev, { tempId, html: optimisticHtml, isInternal: wasInternal, failed: false }]);

    // Scroll to the new optimistic bubble.
    setTimeout(() => {
      if (chatContainerRef.current) {
        chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
      }
    }, 60);

    try {
      await submitReply(htmlMessage, filesToSend, wasInternal);
      // Success: the hook replaced `ticket` with the server copy that already contains this
      // reply, so drop the optimistic placeholder.
      setPendingReplies(prev => prev.filter(p => p.tempId !== tempId));
    } catch (err) {
      // Mark the bubble as failed so the user sees it didn't go through, then surface the error.
      setPendingReplies(prev => prev.map(p => p.tempId === tempId ? { ...p, failed: true } : p));
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
    const text = htmlToPlainText(noteHtml).trim();
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
    // Seen once someone OTHER than the author posts a later (higher-id) non-internal,
    // non-deleted reply. Uses reply id (insertion order) — reliable regardless of
    // timezone/createdAt formatting.
    return ticket.replies.some(r =>
      r.authorId !== reply.authorId &&
      !r.isInternal && !r.deleted &&
      r.id > reply.id
    );
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
    saveNoteFields({ tasksJson: JSON.stringify(updated) });
  };

  const toggleTask = (id: string) => {
    const updated = noteTasks.map(t => t.id === id ? { ...t, done: !t.done } : t);
    setNoteTasks(updated);
    saveNoteFields({ tasksJson: JSON.stringify(updated) });
  };

  const deleteTask = (id: string) => {
    const updated = noteTasks.filter(t => t.id !== id);
    setNoteTasks(updated);
    saveNoteFields({ tasksJson: JSON.stringify(updated) });
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
      await submitReply(html, [], true);
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
    saveNoteFields({ color });
  };

  const renderRcaSection = (arrayName: keyof RCAFormData, sections: RCAFormSection[]) => (
    <>
      {sections.map((section, index) => (
        <div key={index} className="mb-3 bg-[rgba(0,0,0,0.02)] p-4 rounded-lg border border-[rgba(0,0,0,0.12)] relative group">
          <button type="button" onClick={() => removeRcaSection(arrayName, index)} className="absolute top-2 right-2 text-[rgba(0,0,0,0.35)] hover:text-red-500 transition-colors">
            <Icon icon="mdi:close" width={14} />
          </button>
          <div className="mb-2">
            <input value={section.heading} onChange={e => updateRcaSection(arrayName, index, 'heading', e.target.value)} type="text" className="block w-full bg-transparent border-0 border-b border-[rgba(0,0,0,0.12)] focus:border-purple-500 focus:ring-0 text-sm font-semibold placeholder-[rgba(0,0,0,0.35)] px-0" placeholder="Sub-heading" />
          </div>
          <textarea value={section.content} onChange={e => updateRcaSection(arrayName, index, 'content', e.target.value)} className="w-full bg-white border-[rgba(0,0,0,0.12)] rounded-md shadow-sm focus:ring-purple-500 focus:border-purple-500 text-sm" rows={2} placeholder="Details..."></textarea>
        </div>
      ))}
      <button type="button" onClick={() => addRcaSection(arrayName)} className="text-sm text-purple-600 hover:text-purple-800 flex items-center gap-1 font-medium">
        <Icon icon="mdi:plus-circle-outline" width={14} /> Add Sub-section
      </button>
    </>
  );

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Icon icon="mdi:loading" width={40} className="animate-spin text-[rgba(0,0,0,0.3)]" />
      </div>
    );
  }

  if (error && !ticket) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <Icon icon="mdi:alert-circle-outline" width={40} className="text-red-500 mb-4 mx-auto block" />
          <p className="text-xl text-[rgba(0,0,0,0.5)]">{error}</p>
          <button onClick={goBackToTickets} className="mt-4 text-[rgba(0,0,0,0.88)] hover:text-[rgba(0,0,0,0.5)] underline">
            Back to Tickets
          </button>
        </div>
      </div>
    );
  }

  if (!ticket) return null;

  // SLA data
  const isClosed = ticket.status === 'CLOSED';
  const responseSlaLabel = getResponseSlaLabel(ticket.createdAt, ticket.severity, ticket.chatStartedAt);

  // System call summaries live in the reply list but are not conversation, so the
  // counter and the "messages only" filter both discount them.
  const allReplies = ticket.replies ?? [];
  const messageCount = allReplies.filter((r) => !parseCallEvent(r.message)).length;

  return (
    <div className="flex flex-col overflow-hidden bg-white" style={{ height: 'calc(100dvh - 104px)' }}>
      {/* Chat Container */}
      <div
        ref={chatContainerRef}
        className="flex-1 min-h-0 overflow-y-auto custom-scrollbar scroll-smooth bg-[rgba(0,0,0,0.02)]"
        onScroll={handleChatScroll}
      >
        <div className="w-full p-5 lg:p-8 pb-20 space-y-6">

          {/* Source mail — only on tickets that arrived by email */}
          {ticket.emailDetails && (
            <TicketEmailDetails details={ticket.emailDetails} isAdmin={isAdmin} />
          )}

          {/* Original Ticket + Metadata Card — the anchor of the page: black left
              edge + a slightly lifted shadow so it reads above the activity feed. */}
          <div id="msg-description" className="bg-white border border-[#e5e7eb] border-l-[3px] border-l-[rgba(0,0,0,0.88)] rounded-xl p-6 shadow-[0_1px_2px_rgba(0,0,0,0.04),0_10px_28px_-16px_rgba(0,0,0,0.18)]">
            <div className="flex gap-4">
              <div className="flex-shrink-0">
                <div className="w-10 h-10 rounded-lg bg-[rgba(0,0,0,0.88)] flex items-center justify-center text-white">
                  <Icon icon="mdi:ticket-outline" width={18} />
                </div>
              </div>
              <div className="flex-1 min-w-0 space-y-4">
                <div className="flex justify-between items-center gap-2">
                  <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest">Original Issue Description</span>
                  <span className="text-[11px] text-[rgba(0,0,0,0.35)]">Request #{ticket.id}</span>
                </div>
                {/* An email ticket's description is the mail body, which is HTML — already
                    sanitised server-side, and passed through the client sanitiser as well
                    because that is the rule for everything reaching dangerouslySetInnerHTML.
                    A web ticket's description is plain text typed into a form and must stay
                    escaped, with whitespace-pre-wrap doing the line breaks. */}
                {ticket.source === 'EMAIL' && ticket.description ? (
                  <div
                    className="text-[14px] text-[rgba(0,0,0,0.6)] leading-relaxed break-words prose-chat"
                    dangerouslySetInnerHTML={{ __html: sanitizeRichText(ticket.description) }}
                  />
                ) : (
                  <div className="text-[14px] text-[rgba(0,0,0,0.6)] leading-relaxed whitespace-pre-wrap break-words">
                    {ticket.description || <span className="text-[rgba(0,0,0,0.35)] italic text-sm">No description provided.</span>}
                  </div>
                )}

                <div className="flex flex-wrap gap-x-5 gap-y-2 pt-4 border-t border-[rgba(0,0,0,0.06)]">
                  {ticket.accountId && (
                    <div className="flex items-center gap-1.5">
                      <span className="text-[11px] text-[rgba(0,0,0,0.35)] uppercase tracking-widest">Account / Project:</span>
                      <span className="text-[11px] font-medium text-[rgba(0,0,0,0.88)] font-mono">{ticket.accountId}</span>
                    </div>
                  )}
                  {ticket.creatorUsername && (
                    <div className="flex items-center gap-1.5">
                      <span className="text-[11px] text-[rgba(0,0,0,0.35)] uppercase tracking-widest">Raised by:</span>
                      <span className="flex items-center gap-1.5 text-[11px] font-medium text-[rgba(0,0,0,0.6)]">
                        <span className="w-4 h-4 rounded-md bg-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.5)] flex items-center justify-center text-[8px] font-semibold">
                          {ticket.creatorUsername.charAt(0).toUpperCase()}
                        </span>
                        {ticket.creatorUsername}
                      </span>
                    </div>
                  )}
                  {isAdmin && ticket.assignedToUsername && (
                    <div className="flex items-center gap-1.5">
                      <span className="text-[11px] text-[rgba(0,0,0,0.35)] uppercase tracking-widest">Assigned:</span>
                      <span className="flex items-center gap-1.5 text-[11px] font-medium text-[rgba(0,0,0,0.88)]">
                        <span className="w-4 h-4 rounded-md bg-[rgba(0,0,0,0.88)] text-white flex items-center justify-center text-[8px] font-semibold">
                          {ticket.assignedToUsername.charAt(0).toUpperCase()}
                        </span>
                        {ticket.assignedToUsername}
                      </span>
                    </div>
                  )}
                  <div className="flex items-center gap-1.5">
                    <span className="text-[11px] text-[rgba(0,0,0,0.35)] uppercase tracking-widest">Service:</span>
                    <span className="text-[11px] font-medium text-[rgba(0,0,0,0.88)]">{ticket.service}</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <span className="text-[11px] text-[rgba(0,0,0,0.35)] uppercase tracking-widest">Submitted:</span>
                    <span className="text-[11px] text-[rgba(0,0,0,0.5)]">{ticket.createdAt ? new Date(ticket.createdAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' }) : '—'}</span>
                  </div>
                  {ticket.chatStartedAt && (
                    <div className="flex items-center gap-1.5">
                      <span className="text-[11px] text-[rgba(0,0,0,0.35)] uppercase tracking-widest">Chat Started:</span>
                      <span className="text-[11px] font-medium text-[rgba(0,0,0,0.88)]">{new Date(ticket.chatStartedAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })}</span>
                    </div>
                  )}
                  {ticket.closedAt && (
                    <div className="flex items-center gap-1.5">
                      <span className="text-[11px] text-[rgba(0,0,0,0.35)] uppercase tracking-widest">Closed At:</span>
                      <span className="text-[11px] text-[rgba(0,0,0,0.5)]">{new Date(ticket.closedAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })}</span>
                    </div>
                  )}
                </div>

                {/* SLA card (admin only) */}
                {isAdmin && (
                  <div className={`flex items-center gap-3 px-4 py-3 rounded-xl border ${
                    slaStatus.status === 'ok' ? 'bg-emerald-50 text-emerald-700 border-emerald-200' :
                    slaStatus.status === 'warning' ? 'bg-amber-50 text-amber-700 border-amber-200' :
                    slaStatus.status === 'paused' ? 'bg-slate-50 text-slate-600 border-slate-200' :
                    'bg-red-50 text-red-700 border-red-200'
                  }`}>
                    <span className={`w-2 h-2 rounded-full flex-shrink-0 ${
                      slaStatus.status === 'ok' ? 'bg-emerald-500' :
                      slaStatus.status === 'warning' ? 'bg-amber-500 animate-pulse' :
                      slaStatus.status === 'paused' ? 'bg-slate-400' :
                      'bg-red-500 animate-pulse'
                    }`}></span>
                    <div className="flex-1 min-w-0">
                      <div className="text-[11px] font-semibold uppercase tracking-wide leading-none mb-1">
                        Resolution SLA — {slaStatus.status === 'breached' ? 'Breached' : slaStatus.status === 'warning' ? 'At Risk' : slaStatus.status === 'paused' ? 'Paused' : 'On Track'}
                      </div>
                      <div className="text-[11px] font-mono tabular-nums">{slaBigLabel}</div>
                    </div>
                    <div className="text-[11px] opacity-70 whitespace-nowrap">{responseSlaLabel}</div>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Activity header — section label + "all activity vs messages only" filter */}
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-[11px] font-semibold uppercase tracking-widest whitespace-nowrap text-[rgba(0,0,0,0.35)]">
              Correspondence Log
            </span>
            <div className="h-px bg-[#e5e7eb] flex-1 min-w-[20px]"></div>

            <div className="flex items-center p-0.5 rounded-lg bg-[rgba(0,0,0,0.05)] border border-[#e5e7eb]">
              {([
                { key: 'all' as const, label: 'All activity', icon: 'mdi:timeline-text-outline' },
                { key: 'messages' as const, label: 'Messages only', icon: 'mdi:message-text-outline' },
              ]).map((opt) => (
                <button
                  key={opt.key}
                  onClick={() => setActivityFilter(opt.key)}
                  aria-pressed={activityFilter === opt.key}
                  className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11px] font-medium transition-all ${
                    activityFilter === opt.key
                      ? 'bg-white text-[rgba(0,0,0,0.88)] shadow-sm'
                      : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
                  }`}
                >
                  <Icon icon={opt.icon} width={12} />
                  {opt.label}
                </button>
              ))}
            </div>

            <span className="text-[11px] text-[rgba(0,0,0,0.35)] whitespace-nowrap tabular-nums">
              {messageCount} msg{messageCount !== 1 ? 's' : ''}
            </span>
          </div>

          {/* Calls section — hidden entirely in "messages only" mode */}
          {activityFilter === 'all' && <TicketCallHistory ticketId={ticket.id} />}

          {/* Messages section label — a hard break between call history and the thread */}
          {activityFilter === 'all' && (ticket.replies?.length ?? 0) > 0 && (
            <div className="flex items-center gap-3 pt-1">
              <span className="flex items-center gap-1.5 text-[10.5px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)] whitespace-nowrap">
                <Icon icon="mdi:message-text-outline" width={12} />
                Messages
              </span>
              <div className="h-px bg-[#e5e7eb] flex-1"></div>
            </div>
          )}

          {/* Replies */}
          {ticket.replies && ticket.replies.length > 0 ? (
            <div className="space-y-6">
              {ticket.replies.map((reply, index) => {
                const isMine = isMe(reply);
                const isInternalReply = reply.isInternal;
                const seenByOther = isMine && !isInternalReply && isReplySeen(reply);

                // ── System event card (call summaries) — one consistent style, never a bubble ──
                const callEvent = reply.deleted ? null : parseCallEvent(reply.message);
                if (callEvent) {
                  if (activityFilter === 'messages') return null;
                  return (
                    <div key={reply.id} id={`msg-${reply.id}`} className="flex justify-center">
                      <div className="flex items-center gap-2.5 max-w-full px-3 py-2 rounded-lg bg-[#f7f8f9] border border-[#e5e7eb]">
                        <span className="w-6 h-6 rounded-md bg-white border border-[#e5e7eb] flex items-center justify-center flex-shrink-0">
                          <Icon
                            icon={/video/i.test(callEvent.label) ? 'mdi:video-outline' : 'mdi:phone-outline'}
                            width={12}
                            className="text-[rgba(0,0,0,0.45)]"
                          />
                        </span>
                        <div className="min-w-0">
                          <div className="text-[11.5px] font-semibold text-[rgba(0,0,0,0.6)]">
                            {callEvent.label}
                          </div>
                          {callEvent.meta.length > 0 && (
                            <div className="flex items-center gap-1.5 text-[10.5px] text-[rgba(0,0,0,0.4)] flex-wrap">
                              {callEvent.meta.map((m, mi) => (
                                <React.Fragment key={mi}>
                                  {mi > 0 && <span className="text-[rgba(0,0,0,0.2)]">·</span>}
                                  <span className="truncate">{m}</span>
                                </React.Fragment>
                              ))}
                            </div>
                          )}
                        </div>
                        <span className="ml-1 text-[10px] text-[rgba(0,0,0,0.3)] whitespace-nowrap flex-shrink-0 tabular-nums">
                          {reply.createdAt
                            ? new Date(reply.createdAt).toLocaleTimeString('en-IN', {
                                timeZone: 'Asia/Kolkata',
                                hour: '2-digit',
                                minute: '2-digit',
                              })
                            : ''}
                        </span>
                      </div>
                    </div>
                  );
                }

                // Internal notes: amber styling, right-aligned (admin only)
                if (isInternalReply) {
                  return (
                    <React.Fragment key={reply.id}>
                      <div id={`msg-${reply.id}`} className="flex w-full justify-end chat-bubble-enter" style={{ animationDelay: `${Math.min(index, 6) * 40}ms` }}>
                        <div className="flex max-w-[85%] md:max-w-[70%] gap-3 flex-row-reverse min-w-0">
                          <div className="flex-shrink-0 mt-auto">
                            <div className="w-8 h-8 rounded-lg flex items-center justify-center text-sm shadow-sm bg-amber-500 text-white">
                              <Icon icon="mdi:lock-outline" width={14} />
                            </div>
                          </div>
                          <div className="space-y-1 text-right min-w-0 max-w-full">
                            <div className="flex items-center justify-end gap-2 px-1">
                              <span className="px-2 py-0.5 text-[10px] font-medium bg-amber-50 text-amber-600 border border-amber-200 rounded uppercase tracking-wide flex-shrink-0">Internal Note</span>
                              <span className="text-[10px] text-[rgba(0,0,0,0.35)] flex-shrink-0">{reply.createdAt ? new Date(reply.createdAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' }) : ''}</span>
                            </div>
                            <div className="inline-block text-left max-w-full px-3.5 py-2 rounded-xl rounded-tr-sm shadow-sm text-sm leading-relaxed overflow-hidden bg-amber-50 text-amber-900 border border-amber-200">
                              <div dangerouslySetInnerHTML={{ __html: sanitizeRichText(reply.message) }} className="prose-chat break-words" />
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
                        <div className="h-px bg-red-200 flex-1"></div>
                        <span className="flex items-center gap-1.5 text-[10px] font-medium text-red-600 px-2.5 py-0.5 bg-red-50 border border-red-200 rounded">
                          <span className="w-1.5 h-1.5 rounded-full bg-red-500 animate-pulse flex-shrink-0"></span>
                          Unread
                        </span>
                        <div className="h-px bg-red-200 flex-1"></div>
                      </div>
                    )}

                  <div id={`msg-${reply.id}`} className={`flex w-full ${isMine ? 'justify-end' : 'justify-start'} chat-bubble-enter`} style={{ animationDelay: `${Math.min(index, 6) * 40}ms` }}>
                    <div className={`flex max-w-[85%] md:max-w-[72%] gap-3 min-w-0 ${isMine ? 'flex-row-reverse' : ''}`}>
                      <div className="flex-shrink-0 mt-auto">
                        <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-sm font-semibold ${reply.admin ? 'bg-[#1e293b] text-white' : 'bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.5)] border border-[#e5e7eb]'}`}>
                          {reply.admin ? <Icon icon="mdi:shield-account" width={16} /> : <span>{getInitials(reply.authorUsername)}</span>}
                        </div>
                      </div>

                      <div className={`space-y-1.5 min-w-0 max-w-full ${isMine ? 'text-right' : 'text-left'}`}>
                        <div className={`flex items-center gap-1.5 px-1 ${isMine ? 'justify-end' : 'justify-start'}`}>
                          {!isMine && <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.88)]">{reply.authorUsername}</span>}
                          <span className="text-[10px] text-[rgba(0,0,0,0.35)]">{reply.createdAt ? new Date(reply.createdAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' }) : ''}</span>
                          {/* ── Read receipt ticks (only on my outgoing messages) ── */}
                          {isMine && (
                            <span
                              title={seenByOther ? 'Seen' : 'Delivered, not yet seen'}
                              className="flex items-center leading-none"
                            >
                              <Icon
                                icon={seenByOther ? 'mdi:check-all' : 'mdi:check'}
                                width={13}
                                className={seenByOther ? 'text-sky-500' : 'text-[rgba(0,0,0,0.28)]'}
                              />
                            </span>
                          )}
                          {isMine && (
                            <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.88)]">
                              {isAdmin ? reply.authorUsername : 'You'}
                            </span>
                          )}
                        </div>

                        {/* Outgoing bubbles use deep slate rather than solid black —
                            same hierarchy, far easier on the eyes over a long thread.
                            w-fit so the bubble hugs its text and scales with content; the
                            tighter px-3.5/py-2.5 padding keeps short messages compact. */}
                        <div className={`group/msg relative inline-block text-left max-w-full px-3.5 py-2 rounded-xl text-sm leading-relaxed overflow-visible align-top ${isMine ? 'bg-[#1e293b] text-white rounded-tr-none shadow-[0_1px_2px_rgba(15,23,42,0.18)]' : 'bg-white text-[rgba(0,0,0,0.88)] border border-[#e5e7eb] rounded-tl-none shadow-[0_1px_2px_rgba(0,0,0,0.04)]'}`}>

                          {/* ── Edit / Delete hover buttons ── */}
                          {isMine && !reply.deleted && ticket.status !== 'CLOSED' && (
                            <div className="absolute top-2 right-2 opacity-0 group-hover/msg:opacity-100 transition-opacity flex items-center gap-1 z-10">
                              {/* Edit — allowed until the other side has seen (replied after) this message */}
                              {!isReplySeen(reply) && (
                                <button
                                  onClick={() => {
                                    setEditingReplyId(reply.id);
                                    setEditWidth(null);
                                    setEditContent(htmlToPlainText(reply.message));
                                  }}
                                  className="p-1.5 rounded-lg bg-white/20 hover:bg-white/30 transition-colors"
                                  title="Edit (until seen by the other side)"
                                >
                                  <Icon icon="mdi:pencil-outline" width={12} className="text-white/70" />
                                </button>
                              )}
                              <button
                                onClick={() => { if (globalThis.confirm('Delete this message?')) deleteReply(reply.id); }}
                                className="p-1.5 rounded-lg bg-white/20 hover:bg-red-500/40 transition-colors"
                                title="Delete message"
                              >
                                <Icon icon="mdi:trash-can-outline" width={12} className="text-white/70" />
                              </button>
                            </div>
                          )}

                          {reply.deleted ? (
                            <div className={`flex items-center gap-2 italic ${isMine ? 'text-white/50' : 'text-[rgba(0,0,0,0.35)]'}`}>
                              <Icon icon="mdi:cancel" width={14} />
                              <span className="text-sm">This message was deleted</span>
                            </div>
                          ) : editingReplyId === reply.id ? (
                            <div
                              className="flex flex-col gap-2 min-w-[260px]"
                              style={{ width: editWidth ?? 'min(72vw, 480px)' }}
                            >
                              <div className="relative">
                                {/* Left-edge drag handle — widens the box leftward (right edge stays pinned) */}
                                <div
                                  onPointerDown={(e) => {
                                    e.preventDefault();
                                    const container = (e.currentTarget.parentElement?.parentElement as HTMLElement);
                                    const row = e.currentTarget.closest('.chat-bubble-enter') as HTMLElement | null;
                                    const startX = e.clientX;
                                    const startW = container.offsetWidth;
                                    // Cap to the right-aligned 72% column so growth stays on-screen (leftward).
                                    const rowWidth = row ? row.clientWidth : window.innerWidth;
                                    const maxW = Math.max(300, rowWidth * 0.72 - 44);
                                    const onMove = (ev: PointerEvent) => {
                                      const delta = startX - ev.clientX; // drag left → wider
                                      setEditWidth(Math.min(maxW, Math.max(260, startW + delta)));
                                    };
                                    const onUp = () => {
                                      window.removeEventListener('pointermove', onMove);
                                      window.removeEventListener('pointerup', onUp);
                                    };
                                    window.addEventListener('pointermove', onMove);
                                    window.addEventListener('pointerup', onUp);
                                  }}
                                  className="absolute left-0 top-0 h-full w-2 -ml-1 cursor-ew-resize rounded-l-lg bg-transparent hover:bg-[rgba(0,0,0,0.15)] z-10"
                                  title="Drag to widen"
                                />
                                <textarea
                                  value={editContent}
                                  onChange={e => setEditContent(e.target.value)}
                                  className="w-full rounded-lg border border-[rgba(0,0,0,0.18)] p-2 pl-3 text-sm text-[rgba(0,0,0,0.88)] bg-white resize-y max-h-[60vh] overflow-auto focus:outline-none focus:ring-2 focus:ring-[rgba(0,0,0,0.3)]"
                                  rows={Math.min(16, Math.max(5, editContent.split('\n').length + 1))}
                                  autoFocus
                                />
                              </div>
                              <div className="flex gap-2 justify-end">
                                <button
                                  onClick={() => setEditingReplyId(null)}
                                  className="text-xs px-3 py-1 rounded-lg bg-white/20 hover:bg-white/30 text-white border border-white/30"
                                >Cancel</button>
                                <button
                                  onClick={async () => {
                                    if (!editContent.trim()) return;
                                    try {
                                      const htmlMsg = editContent
                                        .split('\n')
                                        .map(line => `<p>${escapeHtml(line) || '<br>'}</p>`)
                                        .join('');
                                      await editReply(reply.id, htmlMsg);
                                      setEditingReplyId(null);
                                    } catch (err) { globalThis.alert('Failed to edit — ' + (err instanceof Error ? err.message : 'unknown')); }
                                  }}
                                  className="text-xs px-3 py-1 rounded-lg bg-white text-[rgba(0,0,0,0.88)] font-semibold hover:bg-[rgba(0,0,0,0.04)]"
                                >Save</button>
                              </div>
                            </div>
                          ) : (
                            <>
                              <div dangerouslySetInnerHTML={{ __html: sanitizeRichText(reply.message) }} className="prose-chat !p-0 break-words" />
                              {reply.edited && (
                                <span
                                  title={
                                    reply.editedAt
                                      ? `Edited ${new Date(reply.editedAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })}`
                                      : 'This message was edited after it was sent'
                                  }
                                  className={`inline-flex items-center gap-1 mt-1.5 px-1.5 py-0.5 rounded text-[9.5px] font-medium cursor-help ${isMine ? 'bg-white/10 text-white/55' : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.4)]'}`}
                                >
                                  <Icon icon="mdi:pencil-outline" width={9} />
                                  Edited
                                </span>
                              )}
                            </>
                          )}

                          {/* Attachments (only if not deleted) — supports multiple files */}
                          {!reply.deleted && (() => {
                            const atts = (reply.attachments && reply.attachments.length > 0)
                              ? reply.attachments
                              : ((reply.attachmentUrl || reply.attachmentName)
                                  ? [{ url: reply.attachmentUrl, name: reply.attachmentName, type: reply.attachmentType }]
                                  : []);
                            if (atts.length === 0) return null;
                            return (
                              <div className="mt-3 space-y-2">
                                {atts.map((att, ai) => (
                                  <React.Fragment key={ai}>
                                    {att.type?.startsWith('image/') && (
                                      <div
                                        className="rounded-lg overflow-hidden border border-white/20 shadow-sm max-w-xs cursor-pointer hover:opacity-90 transition-opacity"
                                        onClick={() => globalThis.open(att.url, '_blank')}
                                      >
                                        <img src={att.url} alt={att.name} className="w-full h-auto object-cover max-h-60" />
                                      </div>
                                    )}

                                    {att.name?.startsWith('rca_ticket_') && (
                                      <div
                                        className="cursor-pointer rounded-lg overflow-hidden border border-white/20 hover:border-purple-300 transition-colors"
                                        onClick={() => att.url ? globalThis.open(att.url, '_blank') : globalThis.alert('File URL not found')}
                                      >
                                        <div className={`flex items-center p-3 ${isMine ? 'bg-white/10' : 'bg-purple-50'}`}>
                                          <div className="p-2 bg-purple-600 text-white rounded-md mr-3">
                                            <Icon icon="mdi:file-document-edit-outline" width={16} />
                                          </div>
                                          <div className="flex-1">
                                            <h4 className={`text-sm font-semibold leading-tight ${isMine ? 'text-white' : 'text-purple-900'}`}>RCA Report Generated</h4>
                                            <p className={`text-[10px] mt-0.5 ${isMine ? 'text-white/70' : 'text-purple-600'}`}>Download PDF Report</p>
                                          </div>
                                          <Icon icon="mdi:download-outline" width={16} className={isMine ? 'text-white/50' : 'text-purple-400'} />
                                        </div>
                                      </div>
                                    )}

                                    {(!att.name || !att.name.startsWith('rca_ticket_')) && (
                                      <div
                                        className={`flex items-center p-3 rounded-lg transition-colors cursor-pointer ${isMine ? 'bg-white/10 border border-white/20 hover:bg-white/20' : 'bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] hover:bg-[rgba(0,0,0,0.04)]'}`}
                                        onClick={() => att.url ? globalThis.open(att.url, '_blank') : alert('File URL not found')}
                                      >
                                        <div className={`w-8 h-8 flex items-center justify-center rounded-md mr-3 ${isMine ? 'bg-white/20 text-white' : 'bg-white border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.4)]'}`}>
                                          {att.type?.startsWith('image/') ? <Icon icon="mdi:file-image-outline" width={16} /> : att.type?.includes('pdf') ? <Icon icon="mdi:file-pdf-box" width={16} /> : <Icon icon="mdi:file-outline" width={16} />}
                                        </div>
                                        <div className="flex-1 min-w-0">
                                          <p className={`text-xs font-medium truncate ${isMine ? 'text-white' : 'text-[rgba(0,0,0,0.88)]'}`}>{att.name || 'Attached File'}</p>
                                          <p className={`text-[10px] mt-0.5 ${isMine ? 'text-white/60' : 'text-[rgba(0,0,0,0.35)]'}`}>Click to view</p>
                                        </div>
                                        <Icon icon="mdi:download-outline" width={14} className={isMine ? 'text-white/40' : 'text-[rgba(0,0,0,0.2)]'} />
                                      </div>
                                    )}
                                  </React.Fragment>
                                ))}
                              </div>
                            );
                          })()}
                        </div>
                      </div>
                    </div>
                  </div>
                  </React.Fragment>
                );
              })}

              {/* Optimistic "sending" bubbles — right-aligned like outgoing messages, shown the
                  instant Send is pressed and removed once the server confirms the reply. */}
              {pendingReplies.map((p) => (
                <div key={p.tempId} className="flex w-full justify-end chat-bubble-enter">
                  <div className="flex max-w-[85%] md:max-w-[72%] gap-3 flex-row-reverse min-w-0">
                    <div className="flex-shrink-0 mt-auto">
                      <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-sm font-semibold ${p.isInternal ? 'bg-amber-500 text-white' : 'bg-[#1e293b] text-white'}`}>
                        <Icon icon={p.isInternal ? 'mdi:lock-outline' : 'mdi:shield-account'} width={14} />
                      </div>
                    </div>
                    <div className="space-y-1.5 min-w-0 max-w-full text-right">
                      <div className="flex items-center justify-end gap-1.5 px-1">
                        <span className={`text-[10px] flex items-center gap-1 ${p.failed ? 'text-red-500' : 'text-[rgba(0,0,0,0.35)]'}`}>
                          {p.failed ? (
                            <><Icon icon="mdi:alert-circle-outline" width={11} /> Failed to send</>
                          ) : (
                            <><Icon icon="mdi:loading" width={11} className="animate-spin" /> Sending…</>
                          )}
                        </span>
                      </div>
                      <div className={`inline-block text-left max-w-full px-3.5 py-2 rounded-xl rounded-tr-none text-sm leading-relaxed align-top ${p.isInternal ? 'bg-amber-50 text-amber-900 border border-amber-200' : 'bg-[#1e293b] text-white'} ${p.failed ? 'opacity-60' : 'opacity-80'}`}>
                        <div dangerouslySetInnerHTML={{ __html: sanitizeRichText(p.html) }} className="prose-chat !p-0 break-words" />
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            pendingReplies.length > 0 ? (
              <div className="space-y-6">
                {pendingReplies.map((p) => (
                  <div key={p.tempId} className="flex w-full justify-end chat-bubble-enter">
                    <div className="flex max-w-[85%] md:max-w-[72%] gap-3 flex-row-reverse min-w-0">
                      <div className="flex-shrink-0 mt-auto">
                        <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-sm font-semibold ${p.isInternal ? 'bg-amber-500 text-white' : 'bg-[#1e293b] text-white'}`}>
                          <Icon icon={p.isInternal ? 'mdi:lock-outline' : 'mdi:shield-account'} width={14} />
                        </div>
                      </div>
                      <div className="space-y-1.5 min-w-0 max-w-full text-right">
                        <div className="flex items-center justify-end gap-1.5 px-1">
                          <span className={`text-[10px] flex items-center gap-1 ${p.failed ? 'text-red-500' : 'text-[rgba(0,0,0,0.35)]'}`}>
                            {p.failed ? (
                              <><Icon icon="mdi:alert-circle-outline" width={11} /> Failed to send</>
                            ) : (
                              <><Icon icon="mdi:loading" width={11} className="animate-spin" /> Sending…</>
                            )}
                          </span>
                        </div>
                        <div className={`inline-block text-left max-w-full px-3.5 py-2 rounded-xl rounded-tr-none text-sm leading-relaxed align-top ${p.isInternal ? 'bg-amber-50 text-amber-900 border border-amber-200' : 'bg-[#1e293b] text-white'} ${p.failed ? 'opacity-60' : 'opacity-80'}`}>
                          <div dangerouslySetInnerHTML={{ __html: sanitizeRichText(p.html) }} className="prose-chat !p-0 break-words" />
                        </div>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="text-center py-10">
                <p className="text-[rgba(0,0,0,0.35)] text-sm italic">No replies yet. Start the conversation below.</p>
              </div>
            )
          )}

          {/* "Messages only" with nothing but system events left to show */}
          {activityFilter === 'messages' && allReplies.length > 0 && messageCount === 0 && (
            <div className="flex flex-col items-center gap-2 py-10 text-center">
              <Icon icon="mdi:message-outline" width={22} className="text-[rgba(0,0,0,0.2)]" />
              <p className="text-[12.5px] text-[rgba(0,0,0,0.4)]">
                No messages yet — only call activity on this ticket.
              </p>
              <button
                onClick={() => setActivityFilter('all')}
                className="text-[11.5px] font-medium text-[rgba(0,0,0,0.55)] underline underline-offset-2 hover:text-[rgba(0,0,0,0.88)] transition-colors"
              >
                Show all activity
              </button>
            </div>
          )}

          {/* Closed banner — shown inline at bottom of chat */}
          {isClosed && (
            <div className="flex items-center justify-between gap-3 px-5 py-3.5 bg-[rgba(0,0,0,0.03)] rounded-xl border border-[rgba(0,0,0,0.08)] text-[12px] text-[rgba(0,0,0,0.5)]">
              <div className="flex items-center gap-2.5">
                <Icon icon="mdi:lock-outline" width={14} className="text-[rgba(0,0,0,0.35)]" />
                This ticket was closed
                {ticket.closedAt && <span className="text-[rgba(0,0,0,0.35)]">· {new Date(ticket.closedAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })}</span>}
              </div>
              {isAdmin && (
                <button
                  onClick={handleReopenTicket}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-emerald-50 text-emerald-700 rounded-md text-[11px] font-medium border border-emerald-200 hover:bg-emerald-100 transition-colors"
                >
                  <Icon icon="mdi:lock-open-outline" width={14} />
                  Reopen
                </button>
              )}
            </div>
          )}
        </div>

        {/* Scroll-to-bottom pill — fades in only when scrolled up. The feed carries
            pb-20 so this never lands on top of a row. */}
        {showScrollBtn && (
          <div className="sticky bottom-4 z-30 flex justify-center pointer-events-none px-5 chat-bubble-enter">
            <button
              onClick={scrollToBottom}
              className="pointer-events-auto flex items-center gap-1.5 pl-2.5 pr-3 py-1.5 bg-white/95 backdrop-blur-sm border border-[#e5e7eb] text-[rgba(0,0,0,0.7)] hover:text-[rgba(0,0,0,0.88)] hover:border-[rgba(0,0,0,0.35)] text-[11px] font-medium rounded-full shadow-[0_4px_16px_-4px_rgba(0,0,0,0.25)] transition-colors"
            >
              <Icon icon="mdi:arrow-down" width={13} />
              Jump to latest
            </button>
          </div>
        )}
      </div>

      {/* Footer - Reply Form */}
      {ticket.status !== 'CLOSED' ? (
        <div className="px-5 py-4 bg-white border-t border-[rgba(0,0,0,0.08)]">
          <form onSubmit={handleSubmitReply}>
            <div className="bg-white rounded-xl border border-[rgba(0,0,0,0.12)] overflow-hidden focus-within:ring-2 focus-within:ring-[rgba(0,0,0,0.1)] focus-within:border-[rgba(0,0,0,0.3)] transition-all">
              <div className="px-4 py-2.5 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between bg-[rgba(0,0,0,0.02)]">
                <div className="flex items-center gap-2 text-[11px] font-medium text-[rgba(0,0,0,0.5)]">
                  <Icon icon="mdi:reply" width={12} className="text-[rgba(0,0,0,0.4)]" />
                  Official Ticket Response
                  {draftRestored && (
                    <span className="flex items-center gap-1 px-2 py-0.5 bg-amber-50 text-amber-700 border border-amber-200 rounded-full text-[10px] font-medium">
                      <Icon icon="mdi:content-save-edit-outline" width={11} />
                      Draft restored
                      <button
                        type="button"
                        title="Discard draft"
                        onClick={() => {
                          if (quillInstance.current) quillInstance.current.root.innerHTML = '';
                          setNewReplyHasContent(false);
                          if (ticketId) localStorage.removeItem(`xamops_ticket_draft_${ticketId}`);
                          setDraftRestored(false);
                        }}
                        className="ml-0.5 hover:text-red-500 transition-colors"
                      >
                        <Icon icon="mdi:close" width={11} />
                      </button>
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-2 flex-wrap justify-end">
                  {attachments.map((f, i) => (
                    <div key={i} className="flex items-center gap-1.5 px-2.5 py-1 bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.88)] rounded-md border border-[rgba(0,0,0,0.06)] text-[11px] font-medium">
                      <Icon icon="mdi:paperclip" width={12} />
                      <span className="max-w-[140px] truncate">{f.name}</span>
                      <button type="button" onClick={() => removeAttachment(i)} className="hover:text-red-500 transition-colors ml-0.5">
                        <Icon icon="mdi:close" width={12} />
                      </button>
                    </div>
                  ))}
                  {isAdmin && <CannedResponsesPicker onInsert={insertCannedResponse} />}
                  {isAdmin && (
                    <button
                      type="button"
                      onClick={() => setShowNotePanel(true)}
                      title={noteFirstLine ? `Note: ${noteFirstLine}` : 'Internal note pad (Ctrl/⌘+Shift+N)'}
                      className="relative flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11px] font-medium border transition-colors bg-amber-50 text-amber-600 border-amber-200 hover:bg-amber-100"
                    >
                      <Icon icon="mdi:note-outline" width={12} />
                      Note
                      {hasNoteContent && (
                        <span className="absolute -top-1 -right-1 w-2 h-2 rounded-full bg-amber-500 border border-white"></span>
                      )}
                      {noteTasks.filter(t => !t.done).length > 0 && (
                        <span className="absolute -top-1.5 -left-1 min-w-[14px] h-[14px] flex items-center justify-center rounded-full bg-[rgba(0,0,0,0.04)]0 border border-white text-white text-[8px] font-semibold px-0.5">
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

              <div className="px-4 py-2.5 border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between bg-[rgba(0,0,0,0.02)]">
                <label className="flex items-center gap-1.5 px-3 py-1.5 bg-white border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)] rounded-md text-[11px] font-medium cursor-pointer transition-colors">
                  <Icon icon="mdi:paperclip" width={14} />
                  Attach Files
                  <input ref={fileInputRef} type="file" multiple className="hidden" onChange={handleFileSelect} />
                </label>

                <button
                  type="submit"
                  disabled={!newReplyHasContent && attachments.length === 0}
                  className="flex items-center gap-1.5 px-5 py-2 text-white rounded-md text-[12px] font-medium transition-all disabled:opacity-50 bg-[rgba(0,0,0,0.88)] hover:bg-black hover:scale-[1.02] active:scale-95"
                >
                  {/* The in-flight "sending" state now lives on the optimistic bubble in the thread,
                      so the button stays "Send" and ready for the next message rather than locking. */}
                  <Icon icon="mdi:send-outline" width={14} /> Send
                </button>
              </div>
            </div>
          </form>
        </div>
      ) : null}

      {/* Email Thread Modal — rendered via portal so fixed/backdrop works regardless of scroll/transforms */}
      {showEmailModal && ReactDOM.createPortal(
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="fixed inset-0 bg-black/40" onClick={closeEmailModal}></div>
          <div className="relative bg-white rounded-xl shadow-xl w-full max-w-lg overflow-hidden z-10" onClick={e => e.stopPropagation()}>

              {/* Header */}
              <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)]">
                <div className="flex items-center justify-between">
                  <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] flex items-center gap-2.5">
                    <div className="w-8 h-8 rounded-lg bg-sky-50 border border-sky-100 flex items-center justify-center flex-shrink-0">
                      <Icon icon="mdi:email-outline" width={16} className="text-sky-600" />
                    </div>
                    Email Ticket Thread
                  </h3>
                  <button onClick={closeEmailModal} className="w-8 h-8 flex items-center justify-center rounded-lg bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.08)] transition-colors">
                    <Icon icon="mdi:close" width={16} />
                  </button>
                </div>
                <p className="mt-1 text-xs text-[rgba(0,0,0,0.35)] pl-10">Send the full conversation history as a formatted email.</p>
              </div>

              <div className="px-5 py-4 space-y-4 max-h-[65vh] overflow-y-auto custom-scrollbar">

                {/* Chip-based recipients input */}
                <div>
                  <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.35)] mb-1.5 uppercase tracking-[0.08em]">
                    Recipients <span className="text-red-500">*</span>
                  </label>
                  <div className="min-h-[44px] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 flex flex-wrap gap-1.5 items-center focus-within:ring-2 focus-within:ring-[rgba(0,0,0,0.1)] focus-within:border-[rgba(0,0,0,0.3)] transition-all bg-[rgba(0,0,0,0.04)] cursor-text">
                    {emailRecipients.map(email => (
                      <span key={email} className="flex items-center gap-1 pl-2.5 pr-1 py-0.5 bg-sky-100 text-sky-800 rounded-full text-[11px] font-semibold border border-sky-200 flex-shrink-0">
                        {email}
                        <button type="button" onClick={() => removeEmailRecipient(email)} className="w-4 h-4 flex items-center justify-center rounded-full hover:bg-sky-200 transition-colors ml-0.5 text-sky-500">
                          <Icon icon="mdi:close" width={10} />
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
                      className="flex-1 min-w-[160px] outline-none text-[13px] text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.25)] bg-transparent py-0.5"
                    />
                  </div>
                  <p className="mt-1 text-[10px] text-[rgba(0,0,0,0.35)]">Press Enter or comma to add • Backspace to remove last</p>
                </div>

                {/* Quick-add: known users */}
                {(adminUsers.filter(a => a.email).length > 0 || ticket?.creatorEmail) && (
                  <div>
                    <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.35)] mb-1.5 uppercase tracking-[0.08em]">Quick Add Users</label>
                    <div className="space-y-0 border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden divide-y divide-[rgba(0,0,0,0.04)]">

                      {/* Ticket creator */}
                      {ticket?.creatorEmail && (
                        <div className="flex items-center justify-between px-3.5 py-3 bg-white hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                          <div className="flex items-center gap-2.5 min-w-0">
                            <div className="w-7 h-7 rounded-full bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.5)] flex items-center justify-center text-[10px] font-semibold flex-shrink-0">
                              {(ticket.creatorUsername || 'U').charAt(0).toUpperCase()}
                            </div>
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5">
                                <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.88)] leading-none">{ticket.creatorUsername}</p>
                                <span className="px-1.5 py-0.5 text-[8px] font-semibold bg-emerald-50 text-emerald-700 rounded-full uppercase tracking-widest border border-emerald-200">Creator</span>
                              </div>
                              <p className="text-[10px] text-[rgba(0,0,0,0.35)] mt-0.5 truncate">{ticket.creatorEmail}</p>
                            </div>
                          </div>
                          <button
                            type="button"
                            onClick={() => addEmailRecipient(ticket.creatorEmail!)}
                            disabled={emailRecipients.includes(ticket.creatorEmail!.toLowerCase())}
                            className="flex items-center gap-1 px-3 py-1.5 text-[11px] font-medium rounded-lg border transition-all disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0 ml-3 bg-white text-[rgba(0,0,0,0.5)] border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.04)]"
                          >
                            {emailRecipients.includes(ticket.creatorEmail.toLowerCase())
                              ? <><Icon icon="mdi:check" width={12} className="text-emerald-500" /> Added</>
                              : <><Icon icon="mdi:plus" width={12} /> Add</>
                            }
                          </button>
                        </div>
                      )}

                      {/* Admin users */}
                      {adminUsers.filter(a => a.email).map(admin => (
                        <div key={admin.id} className="flex items-center justify-between px-3.5 py-3 bg-white hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                          <div className="flex items-center gap-2.5 min-w-0">
                            <div className="w-7 h-7 rounded-full bg-[rgba(0,0,0,0.88)] text-white flex items-center justify-center text-[10px] font-semibold flex-shrink-0">
                              {admin.username.charAt(0).toUpperCase()}
                            </div>
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5">
                                <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.88)] leading-none">{admin.username}</p>
                                <span className="px-1.5 py-0.5 text-[8px] font-semibold bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.88)] rounded-full uppercase tracking-widest border border-[rgba(0,0,0,0.12)]">Admin</span>
                              </div>
                              <p className="text-[10px] text-[rgba(0,0,0,0.35)] mt-0.5 truncate">{admin.email}</p>
                            </div>
                          </div>
                          <button
                            type="button"
                            onClick={() => addEmailRecipient(admin.email!)}
                            disabled={emailRecipients.includes(admin.email!.toLowerCase())}
                            className="flex items-center gap-1 px-3 py-1.5 text-[11px] font-medium rounded-lg border transition-all disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0 ml-3 bg-white text-[rgba(0,0,0,0.5)] border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.04)]"
                          >
                            {emailRecipients.includes(admin.email!.toLowerCase())
                              ? <><Icon icon="mdi:check" width={12} className="text-emerald-500" /> Added</>
                              : <><Icon icon="mdi:plus" width={12} /> Add</>
                            }
                          </button>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* Send mode toggle */}
                <div>
                  <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.35)] mb-1.5 uppercase tracking-[0.08em]">Send Mode</label>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => setSendMode('separate')}
                      className={`flex-1 flex items-center gap-2.5 px-4 py-3 rounded-xl border text-left transition-all ${
                        sendMode === 'separate'
                          ? 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.18)] text-[rgba(0,0,0,0.88)]'
                          : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] hover:border-[rgba(0,0,0,0.2)]'
                      }`}
                    >
                      <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${sendMode === 'separate' ? 'border-[rgba(0,0,0,0.88)]' : 'border-[rgba(0,0,0,0.2)]'}`}>
                        {sendMode === 'separate' && <div className="w-2 h-2 rounded-full bg-[rgba(0,0,0,0.04)]0"></div>}
                      </div>
                      <div>
                        <div className="text-[11px] font-semibold uppercase tracking-widest leading-none">Separate</div>
                        <div className="text-[9px] font-medium text-[rgba(0,0,0,0.35)] mt-0.5 leading-snug">One email per recipient</div>
                      </div>
                    </button>
                    <button
                      type="button"
                      onClick={() => setSendMode('combined')}
                      className={`flex-1 flex items-center gap-2.5 px-4 py-3 rounded-xl border text-left transition-all ${
                        sendMode === 'combined'
                          ? 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.18)] text-[rgba(0,0,0,0.88)]'
                          : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] hover:border-[rgba(0,0,0,0.2)]'
                      }`}
                    >
                      <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${sendMode === 'combined' ? 'border-[rgba(0,0,0,0.88)]' : 'border-[rgba(0,0,0,0.2)]'}`}>
                        {sendMode === 'combined' && <div className="w-2 h-2 rounded-full bg-[rgba(0,0,0,0.04)]0"></div>}
                      </div>
                      <div>
                        <div className="text-[11px] font-semibold uppercase tracking-widest leading-none">Combined</div>
                        <div className="text-[9px] font-medium text-[rgba(0,0,0,0.35)] mt-0.5 leading-snug">All in one email (CC visible)</div>
                      </div>
                    </button>
                  </div>
                </div>

                {/* Info banner */}
                <div className="bg-sky-50 border border-sky-100 rounded-xl px-4 py-3 text-xs text-sky-700 space-y-1">
                  <div className="font-semibold flex items-center gap-1.5"><Icon icon="mdi:information-outline" width={14} /> What will be sent:</div>
                  <ul className="list-disc list-inside space-y-0.5 pl-1 text-sky-600">
                    <li>Ticket #{ticket?.id} — {ticket?.subject}</li>
                    <li>All public replies (internal notes excluded)</li>
                    <li>Admin replies shown as "Support Team"</li>
                  </ul>
                </div>
              </div>

              {/* Footer */}
              <div className="px-5 py-4 flex justify-between items-center border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
                <span className="text-[10px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-widest">
                  {emailRecipients.length + (emailInput.trim() ? 1 : 0)} recipient{(emailRecipients.length + (emailInput.trim() ? 1 : 0)) !== 1 ? 's' : ''} selected
                </span>
                <div className="flex gap-3">
                  <button
                    type="button"
                    onClick={closeEmailModal}
                    className="px-4 py-2 text-[13px] font-medium text-[rgba(0,0,0,0.5)] bg-white border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors"
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    onClick={handleSendEmail}
                    disabled={isSendingEmail || (emailRecipients.length === 0 && !emailInput.trim())}
                    className="flex items-center gap-2 px-5 py-2 text-[13px] font-medium text-white bg-[rgba(0,0,0,0.88)] hover:bg-black rounded-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {isSendingEmail
                      ? <><Icon icon="mdi:loading" width={14} className="animate-spin" /> Sending...</>
                      : <><Icon icon="mdi:send-outline" width={14} /> Send Email</>
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
            <span className="flex items-center gap-2 text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">
              <Icon icon="mdi:file-document-edit-outline" width={18} className="text-purple-600" /> Generate Root Cause Analysis
            </span>
          }
          onClose={() => setShowRcaModal(false)}
          footer={
            <>
              <button type="button" onClick={() => setShowRcaModal(false)} className="inline-flex justify-center rounded-md border border-[rgba(0,0,0,0.12)] shadow-sm px-4 py-2 bg-white text-sm font-medium text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.02)]">
                Cancel
              </button>
              <button type="button" onClick={handleSubmitRca} disabled={rcaSubmitting} className="inline-flex justify-center rounded-md border border-transparent shadow-sm px-4 py-2 bg-purple-600 text-sm font-medium text-white hover:bg-purple-700 disabled:opacity-50">
                {rcaSubmitting ? <><Icon icon="mdi:loading" width={14} className="animate-spin mr-2" /> Generating PDF...</> : 'Submit & Close Ticket'}
              </button>
            </>
          }
        >
          <div className="overflow-y-auto max-h-[60vh] pr-2 custom-scrollbar">
            <form className="space-y-6">
              <div>
                <label className="block text-sm font-bold text-[rgba(0,0,0,0.88)] mb-2">1. Problem Statement <span className="text-red-500">*</span></label>
                <textarea value={rcaFormData.problemStatement} onChange={e => setRcaFormData({ ...rcaFormData, problemStatement: e.target.value })} className="w-full shadow-sm border-[rgba(0,0,0,0.12)] rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm" rows={3} placeholder="Describe the problem briefly..."></textarea>
              </div>
              <div className="border-t border-[rgba(0,0,0,0.12)] pt-4">
                <label className="block text-sm font-bold text-[rgba(0,0,0,0.88)] mb-2">2. Incident Overview</label>
                {renderRcaSection('incidentOverview', rcaFormData.incidentOverview)}
              </div>
              <div className="border-t border-[rgba(0,0,0,0.12)] pt-4">
                <label className="block text-sm font-bold text-[rgba(0,0,0,0.88)] mb-2">3. Description of the Issue</label>
                {renderRcaSection('descriptionSections', rcaFormData.descriptionSections)}
              </div>
              <div className="border-t border-[rgba(0,0,0,0.12)] pt-4">
                <label className="block text-sm font-bold text-[rgba(0,0,0,0.88)] mb-2">4. Root Cause Identification</label>
                {renderRcaSection('rootCauseSections', rcaFormData.rootCauseSections)}
              </div>
              <div className="border-t border-[rgba(0,0,0,0.12)] pt-4">
                <label className="block text-sm font-bold text-[rgba(0,0,0,0.88)] mb-2">5. Resolution</label>
                {renderRcaSection('resolutionSections', rcaFormData.resolutionSections)}
              </div>
              <div className="border-t border-[rgba(0,0,0,0.12)] pt-4">
                <label className="block text-sm font-bold text-[rgba(0,0,0,0.88)] mb-2">6. Prevention / Future Mitigation</label>
                {renderRcaSection('preventionSections', rcaFormData.preventionSections)}
              </div>
              <div className="border-t border-[rgba(0,0,0,0.12)] pt-4">
                <label className="block text-sm font-bold text-[rgba(0,0,0,0.88)] mb-2">7. Conclusion</label>
                <textarea value={rcaFormData.conclusion} onChange={e => setRcaFormData({ ...rcaFormData, conclusion: e.target.value })} className="w-full shadow-sm border-[rgba(0,0,0,0.12)] rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm" rows={3} placeholder="Final summary..."></textarea>
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
            className="absolute inset-0 bg-black/40"
            onClick={closeMeetModal}
          />

          <div className="relative w-full max-w-lg bg-white rounded-xl shadow-xl overflow-hidden flex flex-col max-h-[90vh]">
            {/* Header */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex-shrink-0">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-lg bg-emerald-50 border border-emerald-100 flex items-center justify-center">
                  <Icon icon="mdi:video-outline" width={18} className="text-emerald-600" />
                </div>
                <div>
                  <h2 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Schedule Google Meet</h2>
                  <p className="text-[10px] text-[rgba(0,0,0,0.35)] mt-0.5">Admin only · Ticket #{ticket?.id}</p>
                </div>
              </div>
              <button
                onClick={closeMeetModal}
                className="w-8 h-8 flex items-center justify-center rounded-lg bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.08)] transition-colors"
              >
                <Icon icon="mdi:close" width={16} />
              </button>
            </div>

            {/* Body */}
            <div className="overflow-y-auto flex-1 custom-scrollbar">
              <div className="px-5 py-4 space-y-4">

                {/* Meeting Title */}
                <div>
                  <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.35)] mb-1.5 uppercase tracking-[0.08em]">Meeting Title</label>
                  <input
                    type="text"
                    value={meetTitle}
                    onChange={e => setMeetTitle(e.target.value)}
                    placeholder={`Support Meeting — Ticket #${ticket?.id}`}
                    className="w-full px-3 py-2 text-[13px] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:ring-2 focus:ring-[rgba(0,0,0,0.1)] focus:border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.04)]"
                  />
                </div>

                {/* Date + Time (IST) */}
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.35)] mb-1.5 uppercase tracking-[0.08em]">Date (IST)</label>
                    <input
                      type="date"
                      value={meetDate}
                      onChange={e => setMeetDate(e.target.value)}
                      min={new Date().toISOString().substring(0, 10)}
                      className="w-full px-3 py-2 text-[13px] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:ring-2 focus:ring-[rgba(0,0,0,0.1)] focus:border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.04)]"
                    />
                  </div>
                  <div>
                    <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.35)] mb-1.5 uppercase tracking-[0.08em]">Time (IST)</label>
                    <input
                      type="time"
                      value={meetTime}
                      onChange={e => setMeetTime(e.target.value)}
                      className="w-full px-3 py-2 text-[13px] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:ring-2 focus:ring-[rgba(0,0,0,0.1)] focus:border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.04)]"
                    />
                  </div>
                </div>

                {/* Duration */}
                <div>
                  <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.35)] mb-1.5 uppercase tracking-[0.08em]">Duration</label>
                  <div className="flex gap-2 flex-wrap">
                    {[{ v: '30', l: '30 min' }, { v: '45', l: '45 min' }, { v: '60', l: '1 hour' }, { v: '90', l: '1.5 hrs' }, { v: '120', l: '2 hrs' }].map(opt => (
                      <button
                        key={opt.v}
                        type="button"
                        onClick={() => setMeetDuration(opt.v)}
                        className={`px-3 py-1.5 rounded-lg text-[12px] font-medium border transition-all ${
                          meetDuration === opt.v
                            ? 'bg-emerald-500 text-white border-emerald-500'
                            : 'bg-white text-[rgba(0,0,0,0.5)] border-[rgba(0,0,0,0.12)] hover:border-emerald-300 hover:text-emerald-600'
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
                      className="inline-flex items-center gap-2 px-4 py-2 bg-emerald-600 text-white rounded-xl text-xs font-bold hover:bg-emerald-700 transition-colors"
                    >
                      <Icon icon="mdi:video-outline" width={14} />
                      Open Google Meet
                    </a>
                  </div>

                  {/* Step 2 — Paste the link */}
                  <div>
                    <label className="block text-[10px] font-semibold text-[rgba(0,0,0,0.35)] mb-1.5 uppercase tracking-[0.08em]">
                      Step 2 — Paste the Meet Link
                    </label>
                    <div className="flex gap-2 items-center">
                      <input
                        type="url"
                        value={meetLink}
                        onChange={e => setMeetLink(e.target.value)}
                        placeholder="https://meet.google.com/xxx-xxxx-xxx"
                        className="flex-1 px-3 py-2 text-[13px] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:ring-2 focus:ring-[rgba(0,0,0,0.1)] focus:border-[rgba(0,0,0,0.3)] bg-[rgba(0,0,0,0.04)] font-mono"
                      />
                      {meetLink && (
                        <button type="button" onClick={() => setMeetLink('')}
                          className="px-2 py-2 text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.88)] transition-colors">
                          <Icon icon="mdi:close" width={14} />
                        </button>
                      )}
                    </div>
                    {meetLink && !/^https:\/\/meet\.google\.com\//.test(meetLink) && (
                      <p className="text-[10px] text-amber-600 mt-1 font-medium flex items-center gap-1">
                        <Icon icon="mdi:alert-outline" width={12} />
                        Should start with https://meet.google.com/
                      </p>
                    )}
                  </div>

                  <div className="flex justify-end">
                    <a
                      href={generateGoogleCalendarUrl()}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-[10px] text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.5)] font-medium underline underline-offset-2 transition-colors"
                    >
                      Add to Google Calendar ↗
                    </a>
                  </div>
                </div>

                {/* Share options — always visible once link exists */}
                {meetLink && (
                  <div className="space-y-3 pt-1">
                    <p className="text-[10px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-[0.08em] flex items-center gap-1.5">
                      <Icon icon="mdi:share-variant-outline" width={14} className="text-[rgba(0,0,0,0.35)]" />
                      Share the Link
                    </p>

                    {/* Share in Chat */}
                    {ticket?.status !== 'CLOSED' && (
                      <button
                        type="button"
                        onClick={handleShareMeetInChat}
                        disabled={isSharingMeet || !meetDate || !meetTime}
                        className="w-full flex items-center gap-3 px-4 py-3 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.88)] rounded-xl text-[13px] font-medium hover:bg-[rgba(0,0,0,0.06)] transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {isSharingMeet
                          ? <Icon icon="mdi:loading" width={16} className="animate-spin text-[rgba(0,0,0,0.4)]" />
                          : <Icon icon="mdi:message-text-outline" width={16} className="text-[rgba(0,0,0,0.4)]" />
                        }
                        <div className="text-left">
                          <div className="text-xs font-black">{isSharingMeet ? 'Sharing…' : 'Share in Chat'}</div>
                          <div className="text-[10px] font-medium text-[rgba(0,0,0,0.4)]">Posts meeting card in ticket thread</div>
                        </div>
                      </button>
                    )}

                    {/* Email recipients */}
                    <div className="border border-[rgba(0,0,0,0.08)] rounded-xl overflow-hidden">
                      <div className="px-4 py-2.5 bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                        <p className="text-[10px] font-semibold text-[rgba(0,0,0,0.35)] uppercase tracking-[0.08em] flex items-center gap-1.5">
                          <Icon icon="mdi:email-outline" width={14} className="text-[rgba(0,0,0,0.35)]" />
                          Send Invite via Email
                        </p>
                      </div>
                      <div className="p-4 space-y-3">
                        {/* Recipient pills */}
                        <div className="flex flex-wrap gap-1.5 min-h-[36px] px-3 py-2 border border-[rgba(0,0,0,0.08)] rounded-lg bg-[rgba(0,0,0,0.04)] cursor-text"
                          onClick={() => document.getElementById('meet-email-input')?.focus()}
                        >
                          {meetEmailRecipients.map(email => (
                            <span key={email} className="inline-flex items-center gap-1 px-2 py-0.5 bg-white border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.88)] rounded-lg text-[11px] font-medium">
                              {email}
                              <button
                                type="button"
                                onClick={e => { e.stopPropagation(); setMeetEmailRecipients(prev => prev.filter(r => r !== email)); }}
                                className="text-[rgba(0,0,0,0.35)] hover:text-red-500 transition-colors ml-0.5"
                              >
                                <Icon icon="mdi:close" width={12} />
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
                            className="flex-1 min-w-[160px] bg-transparent text-[12px] outline-none text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.25)]"
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
                                className="flex items-center gap-1 px-2.5 py-1 text-[11px] font-medium border rounded-lg transition-all disabled:opacity-40 disabled:cursor-not-allowed bg-white text-[rgba(0,0,0,0.5)] border-[rgba(0,0,0,0.12)] hover:border-emerald-300 hover:text-emerald-600"
                              >
                                {meetEmailRecipients.includes(admin.email!.toLowerCase())
                                  ? <><Icon icon="mdi:check" width={12} className="text-emerald-500" /> {admin.username}</>
                                  : <><Icon icon="mdi:plus" width={12} /> {admin.username}</>
                                }
                              </button>
                            ))}
                          </div>
                        )}

                        <button
                          type="button"
                          onClick={handleSendMeetEmail}
                          disabled={isSendingMeetEmail || (meetEmailRecipients.length === 0 && !meetEmailInput.trim())}
                          className="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-[rgba(0,0,0,0.88)] text-white rounded-lg text-[12px] font-medium hover:bg-black transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                          {isSendingMeetEmail
                            ? <><Icon icon="mdi:loading" width={14} className="animate-spin" /> Sending…</>
                            : <><Icon icon="mdi:send-outline" width={14} /> Send Meeting Invite</>
                          }
                        </button>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* Footer */}
            <div className="px-5 py-3 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)] flex justify-end flex-shrink-0">
              <button
                type="button"
                onClick={closeMeetModal}
                className="px-4 py-2 text-[13px] font-medium text-[rgba(0,0,0,0.5)] bg-white border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors"
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
                    <Icon icon="mdi:lock-outline" width={14} />
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
                    <Icon icon="mdi:close" width={16} />
                  </button>
                </div>
              </div>

              {/* ── Tab bar ────────────────────────────────────────────── */}
              <div className="flex border-b border-current/10 pl-5 gap-1 flex-shrink-0">
                <button
                  onClick={() => setNoteTab('note')}
                  className={`px-3 py-2.5 text-[10px] font-black uppercase tracking-widest transition-all border-b-2 flex items-center gap-1.5 ${noteTab === 'note' ? 'border-current/60 opacity-100' : 'border-transparent opacity-45 hover:opacity-70'}`}
                >
                  <Icon icon="mdi:pencil-outline" width={12} /> Note
                </button>
                <button
                  onClick={() => setNoteTab('tasks')}
                  className={`px-3 py-2.5 text-[10px] font-black uppercase tracking-widest transition-all border-b-2 flex items-center gap-1.5 ${noteTab === 'tasks' ? 'border-current/60 opacity-100' : 'border-transparent opacity-45 hover:opacity-70'}`}
                >
                  <Icon icon="mdi:checkbox-marked-outline" width={12} /> Tasks
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
                    <Icon icon="mdi:plus" width={16} />
                  </button>
                </div>
                <div className="flex-1 overflow-y-auto px-5 pb-4 space-y-2">
                  {noteTasks.length === 0 ? (
                    <div className="py-12 text-center opacity-40">
                      <Icon icon="mdi:clipboard-list-outline" width={36} className="mb-3 mx-auto block" />
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
                        {task.done && <Icon icon="mdi:check" width={12} />}
                      </button>
                      <span className={`flex-1 text-sm font-medium leading-snug ${task.done ? 'line-through opacity-60' : ''}`}>{task.text}</span>
                      <button onClick={() => deleteTask(task.id)} className="opacity-0 group-hover:opacity-60 hover:!opacity-100 transition-opacity">
                        <Icon icon="mdi:close" width={12} />
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
                      <Icon icon="mdi:check-circle-outline" width={12} /> Saved
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
                        <Icon icon="mdi:history" width={14} />
                      </button>
                      {showNoteHistory && (
                        <div className="absolute bottom-9 right-0 w-64 bg-white rounded-xl shadow-2xl border border-[rgba(0,0,0,0.12)] overflow-hidden z-30">
                          <div className="px-3 py-2 text-[9px] font-black text-[rgba(0,0,0,0.35)] uppercase tracking-widest border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
                            <span>Restore version</span>
                            <button onClick={() => setShowNoteHistory(false)} className="text-[rgba(0,0,0,0.25)] hover:text-[rgba(0,0,0,0.5)]"><Icon icon="mdi:close" width={12} /></button>
                          </div>
                          {noteHistory.map((entry, i) => (
                            <button
                              key={i}
                              onClick={() => {
                                if (noteQuillInstance.current) {
                                  noteQuillInstance.current.root.innerHTML = sanitizeRichText(entry.html);
                                  setNoteHtml(entry.html);
                                }
                                setShowNoteHistory(false);
                              }}
                              className="w-full text-left px-3 py-2.5 hover:bg-[rgba(0,0,0,0.02)] transition-colors border-b border-[rgba(0,0,0,0.04)] last:border-0"
                            >
                              <div className="text-[9px] font-black text-[rgba(0,0,0,0.4)] uppercase tracking-widest mb-1">
                                {new Date(entry.savedAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', dateStyle: 'short', timeStyle: 'short' })}
                              </div>
                              <div className="text-xs text-[rgba(0,0,0,0.5)] font-medium line-clamp-2 leading-snug">
                                {htmlToPlainText(entry.html)}
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
                    <Icon icon="mdi:content-copy" width={14} />
                  </button>

                  {/* Post as internal note in thread */}
                  {noteTab === 'note' && ticket.status !== 'CLOSED' && noteWordCount > 0 && (
                    <button
                      onClick={handlePostNoteAsInternal}
                      disabled={isPostingNote}
                      title="Post as internal message in ticket thread"
                      className="flex items-center gap-1 px-2.5 py-1.5 text-[9px] font-black uppercase tracking-widest rounded-lg border border-current/20 transition-all active:scale-95 disabled:opacity-40 hover:bg-current/10"
                    >
                      {isPostingNote ? <Icon icon="mdi:loading" width={12} className="animate-spin" /> : <Icon icon="mdi:share-variant" width={12} />}
                      Post
                    </button>
                  )}

                  {/* Clear */}
                  {((noteTab === 'note' && noteWordCount > 0) || (noteTab === 'tasks' && noteTasks.length > 0)) && (
                    <button
                      onClick={() => {
                        if (noteTab === 'note') {
                          if (noteQuillInstance.current) noteQuillInstance.current.root.innerHTML = '';
                          noteHtmlRef.current = '';
                          setNoteHtml('');
                          setNoteWordCount(0);
                          saveNoteFields({ noteHtml: '' });
                        } else {
                          setNoteTasks([]);
                          saveNoteFields({ tasksJson: '[]' });
                        }
                      }}
                      className="flex items-center gap-1 px-2.5 py-1.5 text-[9px] font-black uppercase tracking-widest rounded-lg border border-current/20 transition-all active:scale-95 opacity-50 hover:opacity-100 hover:bg-red-50 hover:text-red-600 hover:border-red-200"
                    >
                      <Icon icon="mdi:trash-can-outline" width={12} /> Clear
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
