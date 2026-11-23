import { useMemo, useRef, useState } from 'react';
import { PinOff, Volume2, VolumeX, Expand } from 'lucide-react';
import { calculateRecords } from '@/lib/game';
import { playClick, playMelody } from '@/lib/sound';
import { vibrate } from '@/lib/haptics';

interface PinWidgetProps {
  spins: number[];
  soundOn: boolean;
  onAddSpin: (num: number) => void;
  onUnpin: () => void;
}

export default function PinWidget({ spins, soundOn, onAddSpin, onUnpin }: PinWidgetProps) {
  const [input, setInput] = useState('');
  const [pos, setPos] = useState({ x: 0, y: 0 });
  const dragRef = useRef<{ startX: number; startY: number; baseX: number; baseY: number } | null>(null);
  const state = useMemo(() => calculateRecords(spins), [spins]);

  const submit = () => {
    const val = parseInt(input, 10);
    if (Number.isNaN(val) || val < 0 || val > 36) {
      alert('0 se 36 ke beech valid spin number dalein.');
      return;
    }
    onAddSpin(val);
    setInput('');
    playClick();
    vibrate(30);
  };

  const onPointerDown = (e: React.PointerEvent) => {
    if ((e.target as HTMLElement).closest('input, button')) return;
    dragRef.current = { startX: e.clientX, startY: e.clientY, baseX: pos.x, baseY: pos.y };
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  };
  const onPointerMove = (e: React.PointerEvent) => {
    const d = dragRef.current;
    if (!d) return;
    setPos({ x: d.baseX + e.clientX - d.startX, y: d.baseY + e.clientY - d.startY });
  };
  const onPointerUp = () => {
    dragRef.current = null;
  };

  const lastSpin = spins.length ? spins[spins.length - 1] : null;

  return (
    <div className="min-h-screen bg-[#060a13]/95 flex items-center justify-center p-4" style={{ fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif' }}>
      <div
        style={{ transform: `translate(${pos.x}px, ${pos.y}px)` }}
        className={`w-[300px] rounded-2xl border bg-gradient-to-b from-[#141f33] to-[#0b1220] shadow-[0_20px_60px_rgba(0,0,0,0.7)] select-none ${
          state.isNextPlay
            ? 'border-[#35df91] shadow-[0_0_30px_rgba(53,223,145,0.25)]'
            : 'border-[#2b3b5b]'
        }`}
      >
        {/* drag handle / header */}
        <div
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          className="flex items-center justify-between px-3 py-2 cursor-grab active:cursor-grabbing border-b border-[#2b3b5b]/60 touch-none"
        >
          <div className="flex items-center gap-1.5">
            <span className={`w-2 h-2 rounded-full ${state.isNextPlay ? 'bg-[#35df91] animate-pulse' : 'bg-[#ffc45c]'}`} />
            <span className="text-[#ffdf91] font-black tracking-[0.2em] text-xs">MAGIC</span>
            <span className={`text-[9px] font-black px-1.5 py-0.5 rounded-full border ${
              state.isNextPlay ? 'text-[#35df91] border-[#35df91] bg-[#35df9120]' : 'text-[#ffc45c] border-[#ffc45c] bg-[#ffc45c15]'
            }`}>
              {state.isNextPlay ? 'PLAY NOW' : 'BASE'}
            </span>
          </div>
          <div className="flex gap-1">
            <button onClick={onUnpin} title="Full screen wapas kholein" className="text-[#9baac2] hover:text-white p-1 active:scale-90 transition">
              <Expand className="w-3.5 h-3.5" />
            </button>
            <button title={soundOn ? 'Sound ON' : 'Sound OFF'} className="text-[#9baac2] p-1 pointer-events-none">
              {soundOn ? <Volume2 className="w-3.5 h-3.5" /> : <VolumeX className="w-3.5 h-3.5" />}
            </button>
            <button onClick={onUnpin} title="Unpin" className="text-[#9baac2] hover:text-white p-1 active:scale-90 transition">
              <PinOff className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        <div className="p-3 space-y-2.5">
          {/* Pettan + multiplier */}
          <div className="grid grid-cols-3 gap-2 text-center">
            <div className="bg-[#080d17] border border-[#2b3b5b] rounded-xl py-2">
              <div className="text-[8px] text-[#9baac2] font-black tracking-widest">PETTAN</div>
              <div className="text-2xl font-black text-[#35df91] leading-tight">{state.nextPettan}</div>
            </div>
            <div className="bg-[#080d17] border border-[#2b3b5b] rounded-xl py-2">
              <div className="text-[8px] text-[#9baac2] font-black tracking-widest">KITNA GUNA</div>
              <div className="text-2xl font-black text-[#ffdf91] leading-tight">{state.nextMult}x</div>
            </div>
            <div className="bg-[#080d17] border border-[#2b3b5b] rounded-xl py-2">
              <div className="text-[8px] text-[#9baac2] font-black tracking-widest">UNIT</div>
              <div className={`text-2xl font-black leading-tight ${state.totalUnit >= 0 ? 'text-[#35df91]' : 'text-[#ff6474]'}`}>
                {state.totalUnit > 0 ? '+' : ''}{state.totalUnit}
              </div>
            </div>
          </div>

          {/* Enter spin */}
          <div className="flex gap-2">
            <input
              type="number"
              min={0}
              max={36}
              inputMode="numeric"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && submit()}
              placeholder="Enter Spin (0-36)"
              autoFocus
              className="min-w-0 flex-1 bg-[#080d17] border-[1.5px] border-[#55c7ff] rounded-lg text-white text-lg font-black py-2 px-2 text-center outline-none placeholder:text-[#55c7ff]/50 placeholder:text-xs placeholder:font-semibold"
            />
            <button
              onClick={submit}
              className="bg-gradient-to-br from-[#0891b2] to-[#2563eb] rounded-lg px-4 text-xs font-black tracking-wider text-white active:scale-95 transition-transform"
            >
              ENTER
            </button>
          </div>

          {/* last spin + last 5 */}
          <div className="flex items-center justify-between text-[10px] text-[#9baac2] font-semibold px-0.5">
            <span>
              {lastSpin !== null ? (
                <>LAST SPIN: <b className={lastSpin === 0 ? 'text-[#35df91]' : 'text-white'}>{lastSpin}</b></>
              ) : (
                'Koi spin nahi'
              )}
            </span>
            <span>
              H/M: <b className="text-[#35df91]">{state.hits}</b>/<b className="text-[#ff6474]">{state.misses}</b>
            </span>
          </div>
          <div className="grid grid-cols-5 gap-1.5">
            {spins.slice(-5).reverse().map((n, i) => (
              <div key={`${spins.length - i}-${n}`} className="bg-[#080f1d] border border-[#344766] rounded-lg py-1 text-center">
                <div className={`text-sm font-black ${n === 0 ? 'text-[#35df91]' : 'text-white'}`}>{n}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
