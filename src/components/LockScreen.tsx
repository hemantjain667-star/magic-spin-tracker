import { useState } from 'react';
import { Delete, Sparkles, Lock } from 'lucide-react';
import { vibrate } from '@/lib/haptics';

const PIN_KEY = 'magic_pin_v1';

export function getStoredPin(): string | null {
  try {
    return localStorage.getItem(PIN_KEY);
  } catch {
    return null;
  }
}

export default function LockScreen({ onUnlock }: { onUnlock: () => void }) {
  const [pin, setPin] = useState('');
  const [mode, setMode] = useState<'unlock' | 'set' | 'confirm'>(getStoredPin() ? 'unlock' : 'set');
  const [firstPin, setFirstPin] = useState('');
  const [error, setError] = useState(false);

  const press = (d: string) => {
    if (pin.length >= 4) return;
    const next = pin + d;
    setPin(next);
    setError(false);
    vibrate(15);
    if (next.length === 4) {
      setTimeout(() => handleComplete(next), 120);
    }
  };

  const backspace = () => {
    setPin((p) => p.slice(0, -1));
    setError(false);
  };

  const handleComplete = (code: string) => {
    if (mode === 'set') {
      setFirstPin(code);
      setMode('confirm');
      setPin('');
      return;
    }
    if (mode === 'confirm') {
      if (code === firstPin) {
        try {
          localStorage.setItem(PIN_KEY, code);
        } catch {
          /* storage unavailable */
        }
        onUnlock();
        return;
      }
      setError(true);
      setPin('');
      setMode('set');
      return;
    }
    if (code === getStoredPin()) {
      onUnlock();
      return;
    }
    setError(true);
    setPin('');
  };

  const title = mode === 'set' ? 'NAYA 4-DIGIT PIN BANAYEIN' : mode === 'confirm' ? 'PIN DOBARA DAREIN' : 'PIN DAREIN';
  const subtitle =
    mode === 'set'
      ? 'Agle baar app khulne ke liye yehi PIN lagega'
      : mode === 'confirm'
        ? 'Confirm karne ke liye wahi 4 number darein'
        : 'Magic ko kholne ke liye 4-digit PIN';

  return (
    <div
      className="min-h-screen bg-gradient-to-b from-[#172541] to-[#090d18] flex flex-col items-center justify-center p-6"
      style={{ fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif' }}
    >
      <div className={`bg-[#111a2b]/80 border rounded-3xl p-6 w-full max-w-[320px] text-center shadow-[0_20px_60px_rgba(0,0,0,0.6)] ${error ? 'border-[#ff6474] shake' : 'border-[#2b3b5b]'}`}>
        <div className="flex justify-center mb-3">
          <span className="inline-flex items-center gap-1.5 bg-[#090d18] border border-[#f6c85f]/40 rounded-full px-4 py-2">
            <Sparkles className="w-4 h-4 text-[#f6c85f]" />
            <span className="text-[#ffdf91] font-black tracking-[0.25em] text-sm">MAGIC</span>
          </span>
        </div>
        <div className="flex justify-center mb-1">
          <Lock className={`w-4 h-4 ${error ? 'text-[#ff6474]' : 'text-[#9baac2]'}`} />
        </div>
        <h1 className={`text-xs font-black tracking-widest ${error ? 'text-[#ff6474]' : 'text-[#f4f7ff]'}`}>
          {error ? 'GALAT PIN — FIR SE DAREIN' : title}
        </h1>
        <p className="text-[10px] text-[#9baac2] font-semibold mt-1 mb-4">{error ? 'Sahi 4-digit PIN darein' : subtitle}</p>

        <div className="flex justify-center gap-3 mb-6">
          {[0, 1, 2, 3].map((i) => (
            <span
              key={i}
              className={`w-3.5 h-3.5 rounded-full border-2 transition-colors ${
                error
                  ? 'border-[#ff6474]'
                  : i < pin.length
                    ? 'bg-[#ffdf91] border-[#ffdf91]'
                    : 'border-[#405273] bg-transparent'
              }`}
            />
          ))}
        </div>

        <div className="grid grid-cols-3 gap-2.5">
          {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map((d) => (
            <button
              key={d}
              onClick={() => press(d)}
              className="bg-[#18243a] hover:bg-[#202e48] active:scale-95 border border-[#2b3b5b] rounded-xl py-3.5 text-xl font-black text-white transition"
            >
              {d}
            </button>
          ))}
          <span />
          <button
            onClick={() => press('0')}
            className="bg-[#18243a] hover:bg-[#202e48] active:scale-95 border border-[#2b3b5b] rounded-xl py-3.5 text-xl font-black text-white transition"
          >
            0
          </button>
          <button
            onClick={backspace}
            className="bg-[#18243a]/60 hover:bg-[#202e48] active:scale-95 border border-[#2b3b5b] rounded-xl py-3.5 flex items-center justify-center text-[#9baac2] transition"
          >
            <Delete className="w-5 h-5" />
          </button>
        </div>
      </div>
    </div>
  );
}
