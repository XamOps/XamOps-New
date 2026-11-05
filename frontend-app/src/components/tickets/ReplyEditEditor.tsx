import React, { useEffect, useRef } from 'react';
import Quill from 'quill';
import 'quill/dist/quill.snow.css';
import { sanitizeRichText } from '../../utils/sanitizeHtml';

/** The editor a reply edit hands back: current HTML, and whether the user changed anything. */
export interface ReplyEditValue {
  html: string;
  text: string;
  changed: boolean;
}

interface ReplyEditEditorProps {
  /** The reply's stored HTML. Loaded as-is so line breaks, lists and emphasis survive. */
  initialHtml: string;
  onChange: (value: ReplyEditValue) => void;
  /** Ctrl/Cmd+Enter saves, Escape cancels. */
  onSubmit?: () => void;
  onCancel?: () => void;
}

/**
 * Rich-text editor for editing an existing ticket reply.
 *
 * Replies are stored as the HTML the composer produced. The previous edit box was a plain
 * textarea fed by htmlToPlainText, which reads textContent: every paragraph and <br> collapsed
 * into one line, and bold/lists/quotes were lost. Editing in the same editor the reply was
 * written in keeps the message exactly as it looked.
 */
const ReplyEditEditor: React.FC<ReplyEditEditorProps> = ({ initialHtml, onChange, onSubmit, onCancel }) => {
  const hostRef = useRef<HTMLDivElement>(null);
  const onChangeRef = useRef(onChange);
  const onSubmitRef = useRef(onSubmit);
  const onCancelRef = useRef(onCancel);
  onChangeRef.current = onChange;
  onSubmitRef.current = onSubmit;
  onCancelRef.current = onCancel;

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    // Quill inserts its toolbar as a sibling of the host, so mount into a fresh child that can be
    // removed wholesale on unmount (StrictMode mounts twice).
    const mount = document.createElement('div');
    host.appendChild(mount);

    const quill = new Quill(mount, {
      theme: 'snow',
      modules: {
        toolbar: [
          ['bold', 'italic', 'underline', 'strike'],
          ['blockquote', 'code-block'],
          [{ list: 'ordered' }, { list: 'bullet' }],
          ['clean'],
        ],
      },
    });

    // Same seeding the composer uses for drafts: the sanitized HTML goes straight into the root.
    quill.root.innerHTML = sanitizeRichText(initialHtml);
    // Ignore Quill's own normalisation of the seeded HTML; only a user edit counts as a change.
    quill.history.clear();
    let changed = false;

    quill.on('text-change', (_delta, _old, source) => {
      if (source === 'user') changed = true;
      onChangeRef.current({ html: quill.root.innerHTML, text: quill.getText().trim(), changed });
    });

    quill.root.addEventListener('keydown', (e: KeyboardEvent) => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        onSubmitRef.current?.();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        onCancelRef.current?.();
      }
    });

    onChangeRef.current({ html: quill.root.innerHTML, text: quill.getText().trim(), changed: false });

    // Cursor at the end, like the old textarea's autofocus.
    quill.setSelection(quill.getLength(), 0);

    return () => {
      host.innerHTML = '';
    };
    // Seeded once per edit session; later prop changes must not reset what the user typed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      ref={hostRef}
      className="reply-edit-editor w-full rounded-lg overflow-hidden bg-white text-[rgba(0,0,0,0.88)] [&_.ql-toolbar]:border-0 [&_.ql-toolbar]:border-b [&_.ql-toolbar]:border-[rgba(0,0,0,0.08)] [&_.ql-container]:border-0 [&_.ql-editor]:min-h-[96px] [&_.ql-editor]:max-h-[60vh] [&_.ql-editor]:overflow-auto [&_.ql-editor]:text-sm"
    />
  );
};

export default ReplyEditEditor;
