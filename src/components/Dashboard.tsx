import { useMemo, useRef, useState } from 'react';
import {
  Volume2,
  VolumeX,
  Undo2,
  Trash2,
  Pin,
  PinOff,
  Sparkles,
  Play,
  SlidersHorizontal,
  ScanEye,
  Square,
  Crosshair,
} from 'lucide-react';
import { calculateRecords, type PatternMap, type SpinRecord } from '@/lib/game';
import { playClick } from '@/lib/sound';
import { vibrate } from '@/lib/haptics';
import RouletteArt from './RouletteArt';

interface DashboardProps {
  spins: number[];
  patterns: PatternMap;
  soundOn: boolean;
  isPinned: boolean;
  isCapturing: boolean;
  onToggleSound: () => void;
  onAddSpin: (num: number) => void;
  onUndo: () => void;
  onReset: () => void;
  onPin: () => void;
  onUnpin: () => void;
  onToggleCapture: () => void;
  onToggleTargetBox: () => void;
  onOpenPatternEditor: () => void;
}

function fmt(n: number) {
  return n > 0 ? `+${n}` : `${n}`;
}

export default function Dashboard({
  spins,
  patterns,
  soundOn,
  isPinned,
  isCapturing,
  onToggleSound,
  onAddSpin,
  onUndo,
  onReset,
  onPin,
  onUnpin,
  onToggleCapture,
  onToggleTargetBox,
  onOpenPatternEditor,
}: DashboardProps) {
  const [input, setInput] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  const state = useMemo(() => calculateRecords(spins, patterns), [spins, patterns]);

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
    inputRef.current?.focus();
  };

  const recent = spins.slice(-5).reverse();
  const totalBlocks = Math.ceil(state.records.length / 20);
  const blockRows = [];
  for (let b = totalBlocks - 1; b >= 0; b--) {
    const chunk: SpinRecord[] = state.records.slice(b * 20, b * 20 + 20);
    const bHit = chunk.filter((x) => x.isPlayHit).length;
    const bMiss = chunk.filter((x) => x.isPlayMiss).length;
    const bUnit = chunk.reduce((a, x) => a + x.unit, 0);
    const bTotal = chunk[chunk.length - 1].totalUnit;
    blockRows.push({ idx: b, chunk, bHit, bMiss, bUnit, bTotal });
  }

  return (
    <div
      className="min-h-screen bg-[#090d18] text-[#f4f7ff] pb-10"
      style={{ fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif' }}
    >
      <div className="max-w-[680px] mx-auto p-3 sm:p-4">
        <div className="relative overflow-hidden rounded-3xl border border-[#405273] mb-3 shadow-[0_10px_40px_rgba(0,0,0,0.5)]">
          <RouletteArt />
          <div className="absolute inset-0 bg-gradient-to-t from-[#090d18] via-[#090d18]/40 to-transparent" />
          <div className="absolute inset-0 flex flex-col justify-between p-4">
            <div className="flex items-start justify-between gap-2">
              <div className="flex items-center gap-2">
                <span className="inline-flex items-center gap-1.5 bg-[#090d18]/70 backdrop-blur border border-[#f6c85f]/40 rounded-full px-3 py-1.5">
                  <Sparkles className="w-3.5 h-3.5 text-[#f6c85f]" />
                  <span className="text-[#ffdf91] font-black tracking-[0.2em] text-sm">MAGIC</span>
                </span>
              </div>
              <div className="flex gap-1.5 flex-wrap justify-end">
                <HeaderBtn onClick={onOpenPatternEditor} title="Edit All 10 Patterns">
                  <SlidersHorizontal className="w-3.5 h-3.5 text-[#ffdf91]" />
                </HeaderBtn>
                <HeaderBtn onClick={onToggleSound} title={soundOn ? 'Sound ON' : 'Sound OFF'} active={soundOn}>
                  {soundOn ? <Volume2 className="w-3.5 h-3.5" /> : <VolumeX className="w-3.5 h-3.5" />}
                </HeaderBtn>
                <HeaderBtn onClick={onUndo} title="Undo last spin">
                  <Undo2 className="w-3.5 h-3.5" />
                </HeaderBtn>
                <HeaderBtn
                  onClick={isPinned ? onUnpin : onPin}
                  title={isPinned ? 'Unpin Floating Overlay' : 'Pin Floating Overlay'}
                  active={isPinned}
                >
                  {isPinned ? <PinOff className="w-3.5 h-3.5" /> : <Pin className="w-3.5 h-3.5" />}
                </HeaderBtn>
                <HeaderBtn onClick={onReset} title="Reset all" danger>
                  <Trash2 className="w-3.5 h-3.5" />
                </HeaderBtn>
              </div>
            </div>
            <div>
              <h1 className="text-2xl sm:text-3xl font-black text-[#ffdf91] tracking-wide drop-shadow-[0_0_20px_rgba(246,200,95,0.3)]">
                ✦ MAGIC ✦
              </h1>
              <p className="text-[#9baac2] text-xs font-semibold tracking-wider mt-0.5">
                PETTAN CUT 2X2X + AUTO SCREEN SCANNER
              </p>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2 mb-4">
          <button
            onClick={onToggleCapture}
            className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-2xl border text-xs font-black tracking-wide active:scale-95 transition ${
              isCapturing
                ? 'bg-[#3f1d24] border-[#ff6474] text-[#ff8993]'
                : 'bg-gradient-to-r from-[#0e7490] to-[#1d4ed8] border-[#38bdf8]/60 text-white'
            }`}
          >
            {isCapturing ? <Square className="w-4 h-4" /> : <ScanEye className="w-4 h-4" />}
            {isCapturing ? 'STOP AUTO CAPTURE' : 'START AUTO CAPTURE'}
          </button>

          {isCapturing ? (
            <button
              onClick={onToggleTargetBox}
              className="flex items-center justify-center gap-2 py-2.5 px-3 rounded-2xl bg-[#112a24] border border-[#35df91] text-[#35df91] text-xs font-black active:scale-95 transition"
            >
              <Crosshair className="w-4 h-4" />
              SCAN BOX SHOW / HIDE
            </button>
          ) : (
            <button
              onClick={onOpenPatternEditor}
              className="flex items-center justify-center gap-2 py-2.5 px-3 rounded-2xl bg-[#17243a] border border-[#f6c85f]/50 text-[#ffdf91] text-xs font-black active:scale-95 transition"
            >
              <SlidersHorizontal className="w-4 h-4" />
              EDIT 10 PATTERNS
            </button>
          )}
        </div>

        <div className="grid grid-cols-3 gap-2.5 mb-4">
          <StatBox label="HIT / PASS" value={String(state.hits)} tone="hit" />
          <StatBox label="MISS / FAIL" value={String(state.misses)} tone="miss" />
          <StatBox label="UNIT" value={fmt(state.totalUnit)} tone={state.totalUnit >= 0 ? 'pos' : 'neg'} />
        </div>

        <div className="bg-gradient-to-b from-[#17243a] to-[#111a2b] border border-[#2b3b5b] rounded-2xl p-3 mb-4">
          <div className="flex justify-between items-center mb-2.5">
            <span className="text-[#ffdf91] text-[11px] font-black tracking-wider">LAST 5 SPIN</span>
            <span className="text-[#9baac2] text-[10px] font-semibold">LATEST FIRST</span>
          </div>
          {recent.length ? (
            <div className="grid grid-cols-5 gap-2">
              {recent.map((n, i) => (
                <div
                  key={`${spins.length - i}-${n}`}
                  className="bg-[#080f1d] border border-[#344766] rounded-xl py-2 text-center"
                >
                  <div className="text-[9px] text-[#9baac2] font-bold">#{spins.length - i}</div>
                  <div className={`text-xl font-black mt-0.5 ${n === 0 ? 'text-[#35df91]' : 'text-white'}`}>
                    {n}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="text-[#9baac2] text-xs text-center py-2">Abhi koi spin nahi</div>
          )}
        </div>

        <div
          className={`rounded-2xl p-4 mb-4 text-center border-[1.5px] transition-colors ${
            state.isNextPlay
              ? 'border-[#35df91] bg-gradient-to-b from-[#173b3a] to-[#101d2b] shadow-[0_0_25px_rgba(53,223,145,0.25)] animate-[badgeBlink_1s_ease-in-out_infinite]'
              : 'border-[#ffc45c] bg-gradient-to-b from-[#1b2b45] to-[#101827] shadow-[0_8px_25px_rgba(0,0,0,0.3)]'
          }`}
        >
          <span
            className={`inline-flex items-center gap-1.5 px-4 py-1.5 rounded-full text-sm font-black tracking-wider mb-3 border ${
              state.isNextPlay
                ? 'bg-[#35df9128] text-[#35df91] border-[#35df91]'
                : 'bg-[#ffc45c20] text-[#ffdf91] border-[#ffc45c]'
            }`}
          >
            {state.isNextPlay && <Play className="w-3.5 h-3.5" />}
            {state.isNextPlay ? 'PLAY NOW' : 'BASE MODE'}
          </span>
          <div className="grid grid-cols-3 gap-2 bg-[#080d18a8] border border-white/10 rounded-xl py-3 px-1">
            <NextItem label="MODE">
              <span className={`text-[15px] font-black ${state.isNextPlay ? 'text-[#55c7ff]' : 'text-[#ffdf91]'}`}>
                {state.isNextPlay ? 'PLAY NOW' : 'BASE BET'}
              </span>
            </NextItem>
            <NextItem label="PETTAN">
              <span className="text-3xl font-black text-[#35df91] leading-none">{state.nextPettan}</span>
            </NextItem>
            <NextItem label="BET">
              <span className="text-lg font-black text-[#ffdf91]">BET {state.nextMult}x</span>
            </NextItem>
          </div>
          <div className="mt-2.5 text-[10px] text-[#9baac2] font-semibold">
            CUT PLAY ({state.nextCutNumbers.length}):{' '}
            <span className="text-[#e2e8f0] font-bold">{state.nextCutNumbers.join(', ')}</span>
          </div>
        </div>

        <div className="bg-gradient-to-b from-[#17243a] to-[#111a2b] border border-[#2b3b5b] rounded-2xl p-3 mb-4">
          <div className="flex gap-2.5">
            <input
              ref={inputRef}
              type="number"
              min={0}
              max={36}
              inputMode="numeric"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && submit()}
              placeholder="Enter Spin (0-36)"
              className="min-w-0 flex-1 bg-[#080d17] border-[1.5px] border-[#55c7ff] rounded-xl text-white text-xl font-black py-3 text-center outline-none focus:shadow-[0_0_0_3px_rgba(85,199,255,0.15)] placeholder:text-[#55c7ff]/50 placeholder:text-sm placeholder:font-semibold"
            />
            <button
              onClick={submit}
              className="bg-gradient-to-br from-[#0891b2] to-[#2563eb] rounded-xl px-6 text-sm font-black tracking-wider text-white shadow-[0_5px_15px_rgba(2,132,199,0.3)] active:scale-95 transition-transform"
            >
              ENTER
            </button>
          </div>
        </div>

        <SectionCard
          title="SPIN HISTORY (HIT/MISS • PASS/FAIL)"
          right={`${state.records.length} Spins`}
          maxHeight="max-h-[460px]"
        >
          <thead>
            <tr>
              {['Count', 'Spin', 'Pettan', 'Bet', 'Result', 'Unit', 'Total'].map((h) => (
                <th key={h}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {state.records.length === 0 && (
              <tr>
                <td colSpan={7} className="text-center text-[#9baac2] py-4">
                  Koi record nahi
                </td>
              </tr>
            )}
            {[...state.records].reverse().map((r, i) => (
              <tr
                key={r.counting}
                className={i === 0 ? 'bg-[#55c7ff12]' : r.isPlaying ? 'bg-[#35df910f]' : ''}
              >
                <td>
                  <b>{r.counting}</b>
                </td>
                <td>
                  <span
                    className={`inline-block min-w-[27px] px-1.5 py-0.5 rounded-md bg-[#263650] text-white font-black ${
                      r.spin === 0 ? '!bg-[#15803d]' : ''
                    }`}
                  >
                    {r.spin}
                  </span>
                </td>
                <td>{r.pettan}P</td>
                <td className={r.isPlaying ? 'text-[#ffdf91] font-extrabold' : 'text-[#9baac2]'}>
                  {r.betLabel}
                </td>
                <td>
                  <span
                    className={`inline-block px-1.5 py-0.5 rounded-md text-[10px] font-black ${
                      r.statusType === 'HIT'
                        ? 'bg-[#35df9124] text-[#35df91]'
                        : 'bg-[#ff647424] text-[#ff6474]'
                    }`}
                  >
                    {r.result}
                  </span>
                </td>
                <td
                  className={
                    r.unit > 0 ? 'text-[#35df91]' : r.unit < 0 ? 'text-[#ff6474]' : 'text-[#9baac2]'
                  }
                >
                  {fmt(r.unit)}
                </td>
                <td>
                  <b>{fmt(r.totalUnit)}</b>
                </td>
              </tr>
            ))}
          </tbody>
        </SectionCard>

        <SectionCard
          title="HAR 20 SPIN KA FAYDA / NUKSAAN"
          right={`${totalBlocks} Blocks`}
          maxHeight="max-h-[250px]"
          tint
        >
          <thead>
            <tr>
              {['Block', 'Spins', 'Counting', 'HIT/PASS', 'MISS/FAIL', '20-Spin Unit', 'Total'].map(
                (h) => (
                  <th key={h}>{h}</th>
                )
              )}
            </tr>
          </thead>
          <tbody>
            {blockRows.map(({ idx, chunk, bHit, bMiss, bUnit, bTotal }) => (
              <tr key={idx}>
                <td>
                  <b>#{idx + 1}</b>
                </td>
                <td>
                  {idx * 20 + 1}–{idx * 20 + chunk.length}
                </td>
                <td>
                  {chunk[0].counting}–{chunk[chunk.length - 1].counting}
                </td>
                <td className="text-[#35df91] font-bold">{bHit}</td>
                <td className="text-[#ff6474] font-bold">{bMiss}</td>
                <td
                  className={
                    bUnit > 0
                      ? 'text-[#35df91] font-bold'
                      : bUnit < 0
                        ? 'text-[#ff6474] font-bold'
                        : 'text-[#f6c85f] font-bold'
                  }
                >
                  {fmt(bUnit)}
                </td>
                <td>
                  <b>{fmt(bTotal)}</b>
                </td>
              </tr>
            ))}
          </tbody>
        </SectionCard>
      </div>
    </div>
  );
}

function HeaderBtn({
  children,
  onClick,
  title,
  active,
  danger,
}: {
  children: React.ReactNode;
  onClick: () => void;
  title: string;
  active?: boolean;
  danger?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={`bg-[#202e48]/80 backdrop-blur border rounded-lg p-2 active:scale-95 transition-transform ${
        danger
          ? 'border-[#8e4651] text-[#ff8993]'
          : active
            ? 'border-[#327d65] text-[#35df91]'
            : 'border-[#3a4c6c] text-[#f4f7ff]'
      }`}
    >
      {children}
    </button>
  );
}

function StatBox({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone: 'hit' | 'miss' | 'pos' | 'neg';
}) {
  const color =
    tone === 'hit' || tone === 'pos'
      ? 'text-[#35df91]'
      : tone === 'miss' || tone === 'neg'
        ? 'text-[#ff6474]'
        : 'text-white';
  return (
    <div className="relative overflow-hidden bg-gradient-to-br from-[#17243a] to-[#101827] border border-[#2b3b5b] rounded-2xl py-3 text-center shadow-[0_5px_16px_rgba(0,0,0,0.2)]">
      <div className="absolute left-[18%] right-[18%] top-0 h-0.5 bg-[#f6c85f] opacity-70" />
      <div className="text-[10px] text-[#9baac2] font-black tracking-[0.15em]">{label}</div>
      <div className={`text-2xl font-black mt-0.5 ${color}`}>{value}</div>
    </div>
  );
}

function NextItem({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <span className="block text-[10px] text-[#9baac2] font-black tracking-wider mb-1">{label}</span>
      {children}
    </div>
  );
}

function SectionCard({
  title,
  right,
  maxHeight,
  tint,
  children,
}: {
  title: string;
  right: string;
  maxHeight: string;
  tint?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="bg-[#111a2b] border border-[#2b3b5b] rounded-2xl overflow-hidden mb-4 shadow-[0_6px_18px_rgba(0,0,0,0.2)]">
      <div
        className={`flex justify-between items-center gap-2 px-3 py-2.5 border-b border-[#2b3b5b] text-[11px] font-black ${
          tint
            ? 'bg-gradient-to-r from-[#202f4a] to-[#17243a] text-[#ffdf91]'
            : 'bg-gradient-to-r from-[#202f4a] to-[#17243a] text-[#c4d0e4]'
        }`}
      >
        <span>{title}</span>
        <span className="text-[#9baac2] font-semibold">{right}</span>
      </div>
      <div className={`${maxHeight} overflow-auto`}>
        <table className="w-full border-collapse text-[11px] text-center whitespace-nowrap">
          {children}
        </table>
      </div>
    </div>
  );
}
