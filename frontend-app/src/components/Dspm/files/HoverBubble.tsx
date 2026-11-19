import React, { useId, useState } from 'react';

interface Props {
  /** The bubble's text. */
  text: string;
  /** One element; it gets `aria-describedby` pointing at the bubble. */
  children: React.ReactElement<{ 'aria-describedby'?: string }>;
}

/**
 * A small dark bubble above its trigger, shown on hover and on focus, with `ConsoleOnly`'s tooltip classes.
 * Local markup rather than the shared react-aria tooltip, which needs a react-aria trigger. No click action.
 */
const HoverBubble: React.FC<Props> = ({ text, children }) => {
  const id = useId();
  const [visible, setVisible] = useState(false);
  return (
    <span
      className="relative inline-flex"
      onMouseEnter={() => setVisible(true)}
      onMouseLeave={() => setVisible(false)}
      onFocus={() => setVisible(true)}
      onBlur={() => setVisible(false)}
    >
      {React.cloneElement(children, { 'aria-describedby': id })}
      <span
        id={id}
        role="tooltip"
        hidden={!visible}
        className="pointer-events-none absolute bottom-full left-1/2 z-50 mb-1.5 -translate-x-1/2 whitespace-nowrap rounded-md bg-gray-900 px-2 py-1 text-[11px] font-normal normal-case tracking-normal text-white shadow-lg"
      >
        {text}
      </span>
    </span>
  );
};

export default HoverBubble;
