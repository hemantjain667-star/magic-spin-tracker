import { useMemo, useRef, useState } from 'react';
import { PinOff, Volume2, VolumeX, Expand } from 'lucide-react';
import { calculateRecords } from '@/lib/game';
import { playClick } from '@/lib/sound';
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

  const rawState = useMemo(() => {
    try {
      return calculateRecords(spins);
    } catch {
      return null;
    }
  }, [spins]);

  const safe = {
    isNextPlay: Boolean(rawState?.isNextPlay),
    nextPettan: rawState?.nextPettan ?? '-',
    nextMult: rawState?.nextMult ?? 1,
    totalUnit: typeof rawState?.totalUnit === 'number' ? rawState.totalUnit : 0,
    hits: typeof rawState?.hits === 'number' ? rawState.hits : 0,
    misses: typeof rawState?.misses === 'number' ? rawState.misses : 0,
  };

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
    <div
      className="fixed inset-0 pointer-events-none flex items-center justify-center p-2 z-50"
      style={{ fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif' }}
    >
      <div
        style={{ transform: `translate(${pos.x}px, ${pos.y}px)` }}
        className={`pointer-events-auto w-[156px] rounded-xl border bg-gradient-to-b from-[#141f33] to-[#0b1220] shadow-[0_12px_32px_rgba(0,0,0,0.75)] select-none ${
          safe.isNextPlay
            ? 'border-[#35df91] shadow-[0_0_18px_rgba(53,223,145,0.28)]'
            : 'border-[#2b3b5b]'
        }`}
      >
        <div
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          className="flex items-center justify-between px-2 py-1 cursor-grab active:cursor-grabbing border-b border-[#2b3b5b]/60 touch-none"
        >
          <div className="flex items-center gap-1 min-w-0">
            <span
              className={`w-1.5 h-1.5 rounded-full shrink-0 ${
                safe.isNextPlay ? 'bg-[#35df91] animate-pulse' : 'bg-[#ffc45c]'
              }`}
            />
            <span className="text-[#ffdf91] font-black tracking-[0.12em] text-[10px] truncate">
              MAGIC
            </span>
          </div>
          <div className="flex items-center gap-0.5 shrink-0">
            <button
              onClick={onUnpin}
              title="Full screen"
              className="text-[#9baac2] hover:text-white p-0.5 active:scale-90 transition"
            >
              <Expand className="w-3 h-3" />
            </button>
            <span title={soundOn ? 'Sound ON' : 'Sound OFF'} className="text-[#9baac2] p-0.5 pointer-events-none">
              {soundOn ? <Volume2 className="w-3 h-3" /> : <VolumeX className="w-3 h-3" />}
            </span>
            <button
              onClick={onUnpin}
              title="Unpin"
              className="text-[#9baac2] hover:text-white p-0.5 active:scale-90 transition"
            >
              <PinOff className="w-3 h-3" />
            </button>
          </div>
        </div>

        <div className="p-1.5 space-y-1.5">
          <div className="grid grid-cols-3 gap-1 text-center">
            <div className="bg-[#080d17] border border-[#2b3b5b] rounded-lg py-1">
              <div className="text-[7px] text-[#9baac2] font-bold">PET</div>
              <div className="text-sm font-black text-[#35df91] leading-none mt-0.5">{safe.nextPettan}</div>
            </div>
            <div className="bg-[#080d17] border border-[#2b3b5b] rounded-lg py-1">
              <div className="text-[7px] text-[#9baac2] font-bold">GUNA</div>
              <div className="text-sm font-black text-[#ffdf91] leading-none mt-0.5">{safe.nextMult}x</div>
            </div>
            <div className="bg-[#080d17] border border-[#2b3b5b] rounded-lg py-1">
              <div className="text-[7px] text-[#9baac2] font-bold">UNIT</div>
              <div
                className={`text-sm font-black leading-none mt-0.5 ${
                  safe.totalUnit >= 0 ? 'text-[#35df91]' : 'text-[#ff6474]'
                }`}
              >
                {safe.totalUnit > 0 ? `+${safe.totalUnit}` : safe.totalUnit}
              </div>
            </div>
          </div>

          <div className="flex gap-1">
            <input
              type="number"
              min={0}
              max={36}
              inputMode="numeric"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && submit()}
              placeholder="0-36"
              className="min-w-0 flex-1 bg-[#080d17] border border-[#55c7ff] rounded-md text-white text-xs font-black py-1 px-1.5 text-center outline-none placeholder:text-[#55c7ff]/50 placeholder:text-[10px]"
            />
            <button
              onClick={submit}
              className="bg-gradient-to-br from-[#0891b2] to-[#2563eb] rounded-md px-2 text-[10px] font-black text-white active:scale-95 transition-transform"
            >
              GO
            </button>
          </div>

          <div className="flex items-center justify-between text-[9px] text-[#9baac2] font-semibold px-0.5">
            <span>
              L:{' '}
              <b className={lastSpin === 0 ? 'text-[#35df91]' : 'text-white'}>
                {lastSpin !== null ? lastSpin : '-'}
              </b>
            </span>
            <span>
              <b className="text-[#35df91]">{safe.hits}</b>/<b className="text-[#ff6474]">{safe.misses}</b>
            </span>
          </div>

          <div className="grid grid-cols-5 gap-0.5">
            {spins.slice(-5).reverse().map((n, i) => (
              <div
                key={`${spins.length - i}-${n}`}
                className="bg-[#080f1d] border border-[#344766] rounded py-0.5 text-center"
              >
                <div className={`text-[10px] font-black leading-tight ${n === 0 ? 'text-[#35df91]' : 'text-white'}`}>
                  {n}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
