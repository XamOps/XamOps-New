/**
 * Microphone / camera / speaker selection for an active call.
 *
 * Worth having because the default device is so often the wrong one: people
 * join from a laptop, then plug in a headset, and "I can't hear you" becomes the
 * first two minutes of the call. Switching mid-call is exactly when it is needed.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import { Room } from 'livekit-client';
import { gooeyToast } from 'goey-toast';

interface CallDeviceMenuProps {
  room: Room | null;
  compact: boolean;
}

interface DeviceGroup {
  kind: MediaDeviceKind;
  label: string;
  icon: string;
  devices: MediaDeviceInfo[];
  active?: string;
}

/**
 * Output selection depends on HTMLMediaElement.setSinkId, which Chrome and Edge
 * support and Firefox and Safari largely do not. Offering a speaker picker that
 * silently does nothing is worse than not offering one.
 */
const supportsOutputSelection = (): boolean =>
  typeof window !== 'undefined' &&
  typeof HTMLMediaElement !== 'undefined' &&
  'setSinkId' in HTMLMediaElement.prototype;

const CallDeviceMenu: React.FC<CallDeviceMenuProps> = ({ room, compact }) => {
  const [open, setOpen] = useState(false);
  const [groups, setGroups] = useState<DeviceGroup[]>([]);

  const loadDevices = useCallback(async () => {
    if (!room) return;
    try {
      const wanted: Array<{ kind: MediaDeviceKind; label: string; icon: string }> = [
        { kind: 'audioinput', label: 'Microphone', icon: 'mdi:microphone' },
        { kind: 'videoinput', label: 'Camera', icon: 'mdi:video' },
      ];
      if (supportsOutputSelection()) {
        wanted.push({ kind: 'audiooutput', label: 'Speaker', icon: 'mdi:volume-high' });
      }

      const loaded = await Promise.all(
        wanted.map(async w => ({
          ...w,
          // Labels stay blank until media permission is granted, which by this
          // point in a call it already is.
          devices: (await Room.getLocalDevices(w.kind, false)).filter(d => d.deviceId),
          active: room.getActiveDevice(w.kind),
        }))
      );
      setGroups(loaded.filter(g => g.devices.length > 0));
    } catch (err) {
      console.error('[calls] Could not enumerate devices', err);
    }
  }, [room]);

  useEffect(() => {
    if (open) void loadDevices();
  }, [open, loadDevices]);

  // Headsets get plugged in mid-call; without this the list would be whatever
  // was attached when the menu first opened.
  useEffect(() => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices) return;
    const onChange = () => {
      if (open) void loadDevices();
    };
    navigator.mediaDevices.addEventListener('devicechange', onChange);
    return () => navigator.mediaDevices.removeEventListener('devicechange', onChange);
  }, [open, loadDevices]);

  const switchDevice = useCallback(
    async (kind: MediaDeviceKind, deviceId: string) => {
      if (!room) return;
      try {
        await room.switchActiveDevice(kind, deviceId);
        await loadDevices();
      } catch (err) {
        gooeyToast.error('Could not switch device', {
          description: err instanceof Error ? err.message : undefined,
        });
      }
    },
    [room, loadDevices]
  );

  if (!room) return null;

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(o => !o)}
        className={`rounded-full transition-colors bg-slate-700 hover:bg-slate-600 ${
          compact ? 'p-2.5' : 'p-4'
        }`}
        aria-label="Audio and video settings"
        aria-expanded={open}
        title="Devices"
      >
        <Icon icon="mdi:cog" width={compact ? 18 : 24} />
      </button>

      {open && (
        <>
          {/* Click-away catcher. Sits below the menu but above the call so a
              stray click closes it instead of hitting a control behind. */}
          <div className="fixed inset-0 z-[71]" onClick={() => setOpen(false)} />
          <div className="absolute bottom-full mb-2 left-1/2 -translate-x-1/2 z-[72] w-64 rounded-lg bg-slate-800 border border-slate-600 shadow-2xl p-2.5 space-y-2.5">
            {groups.length === 0 && (
              <div className="text-[11px] text-slate-400 px-1 py-2">No devices found.</div>
            )}
            {groups.map(group => (
              <label key={group.kind} className="block">
                <span className="flex items-center gap-1.5 text-[11px] font-medium text-slate-300 mb-1">
                  <Icon icon={group.icon} width={12} />
                  {group.label}
                </span>
                <select
                  value={group.active ?? group.devices[0]?.deviceId ?? ''}
                  onChange={e => switchDevice(group.kind, e.target.value)}
                  className="w-full text-[11px] rounded bg-slate-900 border border-slate-600 text-white px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-indigo-400"
                >
                  {group.devices.map((d, i) => (
                    <option key={d.deviceId} value={d.deviceId}>
                      {d.label || `${group.label} ${i + 1}`}
                    </option>
                  ))}
                </select>
              </label>
            ))}
          </div>
        </>
      )}
    </div>
  );
};

export default CallDeviceMenu;
