import React, { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { xampulseSetupApi } from '../../../api/xampulseSetupApi';
import { ErrorState, Modal } from '../ui/ui';

/** Disconnect requires typing DISCONNECT. It removes the connection; stack deletion links are shown afterwards. */
export const DisconnectDialog: React.FC<{ accountId: number; onClose: () => void; onDone: () => void }> = ({ accountId, onClose, onDone }) => {
  const [typed, setTyped] = useState('');
  const [purge, setPurge] = useState(false);
  const m = useMutation({ mutationFn: () => xampulseSetupApi.disconnect(accountId, { purgeHotData: purge, confirm: 'DISCONNECT' }), onSuccess: onDone });
  return (
    <Modal title="Disconnect this account" onClose={onClose}>
      {m.data ? (
        <>
          <p>Disconnect started. Agents stop sending data. Your archive bucket and its contents are not touched.</p>
          {m.data.stackDeletionLinks.length > 0 && (
            <ul>{m.data.stackDeletionLinks.map((l) => <li key={l.region}><a href={l.url} target="_blank" rel="noopener noreferrer">Delete the stack in {l.region}</a></li>)}</ul>
          )}
          <button type="button" className="xp-btn" onClick={onClose}>Close</button>
        </>
      ) : (
        <>
          <p>XamPulse stops collecting from this account. You can reconnect later.</p>
          <label style={{ display: 'flex', gap: 8 }}><input type="checkbox" checked={purge} onChange={(e) => setPurge(e.target.checked)} />Also delete recent data stored by XamPulse</label>
          <div className="xp-field" style={{ marginTop: 12 }}>
            <label htmlFor="dc-type">Type DISCONNECT to confirm</label>
            <input id="dc-type" className="xp-input xp-input--mono" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
          </div>
          {m.error && <div style={{ marginTop: 8 }}><ErrorState error={m.error} compact /></div>}
          <div className="xp-row" style={{ marginTop: 16 }}>
            <button type="button" className="xp-btn xp-btn--danger" disabled={typed !== 'DISCONNECT' || m.isPending} onClick={() => m.mutate()}>{m.isPending ? 'Disconnecting' : 'Disconnect'}</button>
            <button type="button" className="xp-btn" onClick={onClose}>Cancel</button>
          </div>
        </>
      )}
    </Modal>
  );
};
