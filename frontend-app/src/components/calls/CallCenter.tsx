/**
 * Everything a call renders, mounted once for the whole authenticated app.
 *
 * Portalled to document.body so the ring and the call panel are never trapped
 * inside a page's stacking or overflow context — a drawer with `overflow:hidden`
 * would otherwise be able to clip an incoming call off screen.
 */

import React from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useCalls } from '../../contexts/CallContext';
import IncomingCallSheet from './IncomingCallSheet';
import CallRoom from './CallRoom';
import WaitingCallsStack from './WaitingCallsStack';

const CallCenter: React.FC = () => {
  const {
    phase,
    incoming,
    joinable,
    waitingCalls,
    activeToken,
    activeCall,
    activeMediaKind,
    acceptIncoming,
    declineIncoming,
    joinExisting,
    hangUp,
    dismissJoinable,
    answerWaiting,
    declineWaiting,
  } = useCalls();

  if (typeof document === 'undefined') return null;

  const busy = phase === 'connecting';

  return createPortal(
    <>
      {activeToken && (
        <CallRoom
          token={activeToken}
          call={activeCall}
          ringing={phase === 'outgoing'}
          startWithVideo={activeMediaKind === 'VIDEO'}
          onHangUp={hangUp}
        />
      )}

      <AnimatePresence>
        {incoming && (
          <IncomingCallSheet
            call={incoming}
            busy={busy}
            onAccept={acceptIncoming}
            onDecline={declineIncoming}
          />
        )}
      </AnimatePresence>

      {/* Calls ringing at us that we cannot take yet. Top-right, away from the ring
          sheet and the call panel, so a waiting call is never mistaken for either. */}
      <AnimatePresence>
        <WaitingCallsStack
          calls={waitingCalls}
          onAnotherCall={!!activeToken}
          busy={busy}
          onAnswer={answerWaiting}
          onDecline={declineWaiting}
        />
      </AnimatePresence>

      {/* Calls someone else answered that we can still drop into. Stacked above
          the call panel so they stay reachable while already on a call. */}
      {joinable.length > 0 && !incoming && (
        <div className="fixed bottom-4 left-4 z-[75] flex flex-col gap-2">
          {joinable.map(call => (
            <div
              key={call.callId}
              className="flex items-center gap-2 rounded-lg bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 shadow-lg px-3 py-2"
            >
              <Icon icon="mdi:phone-in-talk" width={15} className="text-emerald-500 flex-shrink-0" />
              <div className="min-w-0">
                <div className="text-[11px] font-medium text-slate-900 dark:text-white truncate">
                  Call in progress
                  {call.ticketId != null ? ` · Ticket #${call.ticketId}` : ''}
                </div>
                {call.answeredByName && (
                  <div className="text-[10px] text-slate-500 dark:text-slate-400 truncate">
                    {call.answeredByName} answered
                  </div>
                )}
              </div>
              <button
                onClick={() => joinExisting(call.callId)}
                disabled={busy || !!activeToken}
                className="text-[11px] font-medium text-white bg-emerald-600 hover:bg-emerald-700 rounded px-2 py-1 disabled:opacity-40 flex-shrink-0"
              >
                Join
              </button>
              <button
                onClick={() => dismissJoinable(call.callId)}
                className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800 flex-shrink-0"
                aria-label="Dismiss"
              >
                <Icon icon="mdi:close" width={13} className="text-slate-400" />
              </button>
            </div>
          ))}
        </div>
      )}
    </>,
    document.body
  );
};

export default CallCenter;
