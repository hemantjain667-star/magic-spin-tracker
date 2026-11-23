const HINDI_MELODY_HIT = [
  { f: 523.25, d: 0.14 },
  { f: 659.25, d: 0.14 },
  { f: 783.99, d: 0.16 },
  { f: 659.25, d: 0.14 },
  { f: 783.99, d: 0.14 },
  { f: 880, d: 0.24 },
];
const HINDI_MELODY_MISS = [
  { f: 587.33, d: 0.15 },
  { f: 523.25, d: 0.15 },
  { f: 440, d: 0.16 },
  { f: 392, d: 0.24 },
];

let audioCtx: AudioContext | null = null;
let enabled = true;

export function setSoundEnabled(on: boolean) {
  enabled = on;
}

function getCtx(): AudioContext | null {
  try {
    if (!audioCtx) {
      const Ctor = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      audioCtx = new Ctor();
    }
    if (audioCtx.state === 'suspended') audioCtx.resume();
    return audioCtx;
  } catch {
    return null;
  }
}

function playNotes(notes: { f: number; d: number }[], type: OscillatorType, volume: number) {
  if (!enabled) return;
  const ctx = getCtx();
  if (!ctx) return;
  let cursor = ctx.currentTime;
  for (const n of notes) {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = type;
    osc.frequency.setValueAtTime(n.f, cursor);
    gain.gain.setValueAtTime(0.001, cursor);
    gain.gain.linearRampToValueAtTime(volume, cursor + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.001, cursor + n.d);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(cursor);
    osc.stop(cursor + n.d + 0.02);
    cursor += n.d * 0.92;
  }
}

export function playClick() {
  if (!enabled) return;
  const ctx = getCtx();
  if (!ctx) return;
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = 'square';
  osc.frequency.setValueAtTime(660, ctx.currentTime);
  osc.frequency.exponentialRampToValueAtTime(440, ctx.currentTime + 0.08);
  gain.gain.setValueAtTime(0.001, ctx.currentTime);
  gain.gain.linearRampToValueAtTime(0.12, ctx.currentTime + 0.01);
  gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.09);
  osc.connect(gain);
  gain.connect(ctx.destination);
  osc.start(ctx.currentTime);
  osc.stop(ctx.currentTime + 0.1);
}

export function playMelody(type: 'HIT' | 'MISS') {
  playNotes(type === 'HIT' ? HINDI_MELODY_HIT : HINDI_MELODY_MISS, 'triangle', 0.18);
}
