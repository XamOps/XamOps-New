/**
 * Ringtone for incoming calls.
 *
 * Synthesised with WebAudio rather than shipped as an asset: a ring has to loop
 * for tens of seconds, and the existing notification.mp3 is a one-shot blip that
 * grates badly on repeat. Two soft tones on a ring/pause cycle read as a phone
 * without being shrill.
 *
 * Browsers refuse to start audio before a user gesture. That is fine here — by
 * the time a call arrives the user has almost always clicked something, and if
 * the context is still blocked we fail quietly rather than throwing, since a
 * silent ring is far better than a broken call sheet.
 */

type Ringtone = {
  start: () => void;
  stop: () => void;
  isPlaying: () => boolean;
};

const RING_ON_SECONDS = 1.6;
const RING_CYCLE_SECONDS = 4.0;
const TONE_A_HZ = 440;
const TONE_B_HZ = 480;
const PEAK_GAIN = 0.16;

const createRingtone = (): Ringtone => {
  let context: AudioContext | null = null;
  let cycleTimer: ReturnType<typeof setInterval> | null = null;
  let playing = false;

  const ensureContext = (): AudioContext | null => {
    if (context) return context;
    try {
      const Ctor =
        window.AudioContext ||
        (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (!Ctor) return null;
      context = new Ctor();
      return context;
    } catch {
      return null;
    }
  };

  /** One ring burst: two detuned tones with a soft attack and release. */
  const ringOnce = () => {
    const ctx = context;
    if (!ctx || ctx.state === 'closed') return;

    const now = ctx.currentTime;
    const gain = ctx.createGain();
    gain.connect(ctx.destination);

    // Ramped rather than gated — an instant square edge clicks audibly.
    gain.gain.setValueAtTime(0.0001, now);
    gain.gain.exponentialRampToValueAtTime(PEAK_GAIN, now + 0.08);
    gain.gain.setValueAtTime(PEAK_GAIN, now + RING_ON_SECONDS - 0.12);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + RING_ON_SECONDS);

    [TONE_A_HZ, TONE_B_HZ].forEach(freq => {
      const osc = ctx.createOscillator();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, now);
      osc.connect(gain);
      osc.start(now);
      osc.stop(now + RING_ON_SECONDS + 0.05);
    });
  };

  const start = () => {
    if (playing) return;
    const ctx = ensureContext();
    if (!ctx) return;

    playing = true;
    // Autoplay policy may leave the context suspended until a gesture; resuming
    // is a no-op when it is already running.
    void ctx.resume().catch(() => undefined);

    ringOnce();
    cycleTimer = setInterval(ringOnce, RING_CYCLE_SECONDS * 1000);
  };

  const stop = () => {
    playing = false;
    if (cycleTimer) {
      clearInterval(cycleTimer);
      cycleTimer = null;
    }
    // The context is kept alive deliberately: repeatedly closing and recreating
    // one costs a fresh autoplay unlock on some browsers.
    if (context && context.state === 'running') {
      void context.suspend().catch(() => undefined);
    }
  };

  return { start, stop, isPlaying: () => playing };
};

/** Single shared ringtone — two overlapping rings would beat against each other. */
export const ringtone = createRingtone();
