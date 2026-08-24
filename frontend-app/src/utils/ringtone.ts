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
  /**
   * Single soft double-beep for a call arriving while you are already talking.
   *
   * A looping ringtone over a live conversation is unusable — you cannot hear the
   * person you are with. But silence is what made a waiting call invisible, so this
   * is the middle ground: quiet, brief, and audible enough to make you glance at the
   * waiting card.
   */
  chime: () => void;
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

  /**
   * Unlocks audio on the first user gesture, long before any call arrives.
   *
   * "By the time a call arrives the user has almost always clicked something" was
   * an assumption, not a guarantee. An agent who opens the app in a background tab
   * and leaves it there has produced no gesture at all, so the first ring of the
   * day was the one most likely to be silent — precisely the ring that matters,
   * since a silent ring is indistinguishable from no call.
   *
   * Creating and resuming the context inside a real gesture handler is what
   * browsers require; once unlocked it stays unlocked for the page's lifetime, so
   * this runs once and then removes itself.
   */
  const primeOnFirstGesture = () => {
    if (typeof window === 'undefined') return;

    const events: Array<keyof WindowEventMap> = ['pointerdown', 'keydown', 'touchstart'];
    const unlock = () => {
      events.forEach(evt => window.removeEventListener(evt, unlock));
      const ctx = ensureContext();
      if (!ctx) return;
      // Resumed then suspended again: this leaves the context in the "already
      // unlocked" state without leaving an oscillator-free context running and
      // burning a little battery until the first call.
      void ctx
        .resume()
        .then(() => {
          if (!playing && ctx.state === 'running') void ctx.suspend().catch(() => undefined);
        })
        .catch(() => undefined);
    };
    // Passive: this must never delay or interfere with the gesture it observes.
    events.forEach(evt => window.addEventListener(evt, unlock, { once: true, passive: true }));
  };

  primeOnFirstGesture();

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

  /**
   * Two short blips at a lower level than the ring.
   *
   * Deliberately does not touch `playing`, so it can sound while a call is in
   * progress without the ring state machine thinking a ringtone started — and
   * without `stop()` on the live call silencing it.
   */
  const chime = () => {
    const ctx = ensureContext();
    if (!ctx || ctx.state === 'closed') return;
    void ctx.resume().catch(() => undefined);

    const now = ctx.currentTime;
    const peak = PEAK_GAIN * 0.55;

    [0, 0.22].forEach(offset => {
      const at = now + offset;
      const gain = ctx.createGain();
      gain.connect(ctx.destination);
      gain.gain.setValueAtTime(0.0001, at);
      gain.gain.exponentialRampToValueAtTime(peak, at + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.14);

      const osc = ctx.createOscillator();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(TONE_B_HZ, at);
      osc.connect(gain);
      osc.start(at);
      osc.stop(at + 0.16);
    });
  };

  return { start, stop, isPlaying: () => playing, chime };
};

/** Single shared ringtone — two overlapping rings would beat against each other. */
export const ringtone = createRingtone();
