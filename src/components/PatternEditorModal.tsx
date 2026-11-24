import { useState } from 'react';
import { X, RotateCcw, Save } from 'lucide-react';
import {
  ALL_NUMS,
  DEFAULT_PETTAN_PATTERNS,
  cloneDefaultPatterns,
  getCutPettanNumbers,
  normalizePatternNumbers,
  type PatternMap,
} from '@/lib/game';

const REDS = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36]);

interface PatternEditorModalProps {
  patterns: PatternMap;
  onSave: (next: PatternMap) => void;
  onClose: () => void;
}

export default function PatternEditorModal({
  patterns,
  onSave,
  onClose,
}: PatternEditorModalProps) {
  const [draft, setDraft] = useState<PatternMap>(() => {
    const copy: PatternMap = {};
    for (let d = 0; d <= 9; d++) {
      copy[d] = [...(patterns[d] ?? DEFAULT_PETTAN_PATTERNS[d])];
    }
    return copy;
  });

  const [activeDigit, setActiveDigit] = useState<number>(1);
  const [textInput, setTextInput] = useState<string>(() => (patterns[1] ?? []).join(', '));

  const switchDigit = (digit: number) => {
    setActiveDigit(digit);
    setTextInput((draft[digit] ?? []).join(', '));
  };

  const toggleNumber = (num: number) => {
    setDraft((prev) => {
      const current = new Set(prev[activeDigit] ?? []);
      if (current.has(num)) {
        current.delete(num);
      } else {
        current.add(num);
      }
      const nextList = Array.from(current).sort((a, b) => a - b);
      setTextInput(nextList.join(', '));
      return { ...prev, [activeDigit]: nextList };
    });
  };

  const applyTextInput = (value: string) => {
    setTextInput(value);
    const parsed = value
      .split(/[\s,]+/)
      .map((s) => s.trim())
      .filter(Boolean)
      .map((s) => Number(s));
    const normalized = normalizePatternNumbers(parsed);
    setDraft((prev) => ({ ...prev, [activeDigit]: normalized }));
  };

  const resetCurrentPattern = () => {
    const def = [...DEFAULT_PETTAN_PATTERNS[activeDigit]];
    setDraft((prev) => ({ ...prev, [activeDigit]: def }));
    setTextInput(def.join(', '));
  };

  const resetAllPatterns = () => {
    if (!confirm('Kya sabhi 10 Patterns ko purane default par reset karna hai?')) return;
    const defAll = cloneDefaultPatterns();
    setDraft(defAll);
    setTextInput(defAll[activeDigit].join(', '));
  };

  const selectedSet = new Set(draft[activeDigit] ?? []);
  const cutNums = getCutPettanNumbers(activeDigit, draft);

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-3">
      <div className="w-full max-w-[560px] max-h-[92vh] overflow-y-auto bg-[#0f172a] border border-[#38bdf8]/40 rounded-2xl shadow-2xl text-[#f4f7ff]">
        <div className="sticky top-0 z-10 flex items-center justify-between px-4 py-3 bg-[#17243a] border-b border-[#2b3b5b]">
          <div>
            <h2 className="text-sm font-black text-[#ffdf91] tracking-wider">
              EDIT ALL 10 PATTERNS (0P – 9P)
            </h2>
            <p className="text-[10px] text-[#9baac2] font-semibold">
              Selected = Pattern Numbers (Skipped) • Unselected = Cut Numbers (Play)
            </p>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg bg-[#202e48] border border-[#3a4c6c] text-[#f4f7ff]"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-4 space-y-4">
          <div className="grid grid-cols-5 gap-1.5">
            {[1, 2, 3, 4, 5, 6, 7, 8, 9, 0].map((d) => (
              <button
                key={d}
                onClick={() => switchDigit(d)}
                className={`py-2 rounded-xl text-xs font-black border transition ${
                  activeDigit === d
                    ? 'bg-[#38bdf8]/20 border-[#38bdf8] text-[#ffdf91]'
                    : 'bg-[#111a2b] border-[#2b3b5b] text-[#9baac2]'
                }`}
              >
                {d}P ({(draft[d] ?? []).length})
              </button>
            ))}
          </div>

          <div className="grid grid-cols-2 gap-2 text-center">
            <div className="bg-[#111a2b] border border-[#2b3b5b] rounded-xl py-2 px-3">
              <div className="text-[10px] text-[#9baac2] font-bold">PATTERN {activeDigit}P NUMBERS</div>
              <div className="text-lg font-black text-[#55c7ff]">{selectedSet.size}</div>
            </div>
            <div className="bg-[#111a2b] border border-[#35df91]/40 rounded-xl py-2 px-3">
              <div className="text-[10px] text-[#9baac2] font-bold">CUT (PLAY) NUMBERS</div>
              <div className="text-lg font-black text-[#35df91]">{cutNums.length}</div>
            </div>
          </div>

          <div>
            <div className="text-[10px] text-[#ffdf91] font-bold mb-1.5">
              TAP NUMBERS TO ADD / REMOVE IN PATTERN {activeDigit}P:
            </div>
            <div className="grid grid-cols-7 gap-1.5">
              {ALL_NUMS.map((num) => {
                const isSelected = selectedSet.has(num);
                const baseColor =
                  num === 0
                    ? 'bg-[#15803d]'
                    : REDS.has(num)
                      ? 'bg-[#991b1b]'
                      : 'bg-[#1e293b]';
                return (
                  <button
                    key={num}
                    onClick={() => toggleNumber(num)}
                    className={`py-2 rounded-lg text-xs font-black border transition ${
                      isSelected
                        ? `${baseColor} border-[#facc15] text-white ring-1 ring-[#facc15]`
                        : 'bg-[#080d17] border-[#263650] text-[#64748b] opacity-60'
                    }`}
                  >
                    {num}
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <label className="block text-[10px] text-[#9baac2] font-bold mb-1">
              DIRECT EDIT (Comma se alag numbers, 0-36):
            </label>
            <textarea
              rows={2}
              value={textInput}
              onChange={(e) => applyTextInput(e.target.value)}
              className="w-full bg-[#080d17] border border-[#334155] rounded-xl p-2.5 text-xs font-mono text-white outline-none focus:border-[#38bdf8]"
            />
          </div>

          <div className="bg-[#080f1d] border border-[#35df91]/30 rounded-xl p-2.5">
            <div className="text-[10px] text-[#35df91] font-black mb-1">
              PLAY / CUT NUMBERS ({cutNums.length}):
            </div>
            <div className="text-xs font-bold text-[#e2e8f0] leading-relaxed">
              {cutNums.length ? cutNums.join(', ') : 'Koi cut number nahi bacha'}
            </div>
          </div>

          <div className="flex flex-wrap gap-2 justify-between pt-2 border-t border-[#2b3b5b]">
            <div className="flex gap-2">
              <button
                onClick={resetCurrentPattern}
                className="inline-flex items-center gap-1 px-3 py-2 rounded-xl bg-[#1e293b] border border-[#475569] text-xs font-bold text-[#e2e8f0]"
              >
                <RotateCcw className="w-3.5 h-3.5" />
                Reset {activeDigit}P
              </button>
              <button
                onClick={resetAllPatterns}
                className="inline-flex items-center gap-1 px-3 py-2 rounded-xl bg-[#3f1d24] border border-[#8e4651] text-xs font-bold text-[#ff8993]"
              >
                Reset All 10
              </button>
            </div>
            <button
              onClick={() => {
                onSave(draft);
                onClose();
              }}
              className="inline-flex items-center gap-1.5 px-5 py-2 rounded-xl bg-gradient-to-r from-[#16a34a] to-[#059669] text-xs font-black text-white shadow-lg active:scale-95"
            >
              <Save className="w-4 h-4" />
              SAVE PATTERNS
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
