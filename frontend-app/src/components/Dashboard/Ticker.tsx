/**
 * Ticker Component
 *
 * Displays scrolling ticker messages at the top of the dashboard
 * Fetches messages from /api/xamops/dashboard/unified/ticker
 */

import React, { useEffect, useState } from 'react';

interface TickerProps {
  className?: string;
}

export const Ticker: React.FC<TickerProps> = ({ className = '' }) => {
  const [messages, setMessages] = useState<string[]>([]);

  useEffect(() => {
    const fetchMessages = async () => {
      try {
        const response = await fetch('/api/xamops/dashboard/unified/ticker');
        if (response.ok) {
          const data = await response.json();
          setMessages(data);
        }
      } catch (error) {
        console.error('Failed to fetch ticker messages:', error);
      }
    };

    fetchMessages();
  }, []);

  const displayMessages = messages.length > 0
    ? messages
    : ['Unified Command Center Active - Monitoring Systems...'];

  return (
    <div className={`ticker-wrap ${className}`}>
      <div className="ticker">
        {displayMessages.map((msg, index) => (
          <span key={index} className="ticker-item">
            <i className="fas fa-circle text-[8px] text-green-400 mr-2 align-middle"></i>
            <span>{msg}</span>
          </span>
        ))}
      </div>
    </div>
  );
};
