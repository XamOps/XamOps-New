import React from 'react';

/**
 * Prowler descriptions carry inline markdown (**bold**, `code`). Rendering it beats
 * showing raw asterisks and backticks in the middle of a remediation instruction.
 *
 * Deliberately not a full markdown parser: the input is scanner output, not user content,
 * and these two constructs are the only ones it uses. A parser here would be a larger
 * dependency and a larger surface for the same result.
 */
const MdText: React.FC<{ text: string | null | undefined; className?: string }> = ({
  text, className,
}) => (
  <span className={className}>
    {(text ?? '').split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, i) => {
      if (part.startsWith('**') && part.endsWith('**') && part.length >= 5) {
        return <strong key={i} className="font-semibold">{part.slice(2, -2)}</strong>;
      }
      if (part.startsWith('`') && part.endsWith('`') && part.length >= 3) {
        return (
          <code
            key={i}
            className="font-mono text-[0.92em] bg-[rgba(0,0,0,0.05)] px-1 py-[1px] rounded"
          >
            {part.slice(1, -1)}
          </code>
        );
      }
      return <React.Fragment key={i}>{part}</React.Fragment>;
    })}
  </span>
);

export default MdText;
