import { useMemo } from 'react';
import { Sparkles, Sun, CheckCircle2 } from 'lucide-react';

const WISHES = [
  'Best of the Day! Aaj ka din aapke liye shubh, shant aur profitable ho. Discipline aur patience ke saath khelein!',
  'Aapka din mangalmay ho! Hamesha apne Target Profit aur Stop-Loss ka dhyan rakhein — samajhdari hi sabse badi jeet hai!',
  'Good Luck Today! Fresh mind aur smart strategy ke saath shuruat karein — Best Wishes for a Winning Day!',
  'Naye din ki nayi shuruat! Har decision sukoon aur bharose se lein — Have a Great & Lucky Day!',
];

export default function WelcomeModal({ onClose }: { onClose: () => void }) {
  const { greeting, wish } = useMemo(() => {
    const h = new Date().getHours();
    const g =
      h < 12
        ? 'Shubh Prabhat ☀️'
        : h < 17
          ? 'Namaste & Good Afternoon ✨'
          : 'Shubh Sandhya 🌙';
    const idx = new Date().getDate() % WISHES.length;
    return { greeting: g, wish: WISHES[idx] };
  }, []);

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="w-full max-w-[360px] rounded-3xl bg-gradient-to-b from-[#172541] to-[#0b1220] border border-[#f6c85f]/50 p-6 text-center shadow-[0_20px_60px_rgba(0,0,0,0.7)] text-[#f4f7ff]">
        <div className="flex justify-center mb-3">
          <span className="inline-flex items-center gap-1.5 bg-[#090d18] border border-[#f6c85f]/50 rounded-full px-4 py-1.5">
            <Sparkles className="w-4 h-4 text-[#f6c85f]" />
            <span className="text-[#ffdf91] font-black tracking-[0.2em] text-xs">MAGIC TRACKER</span>
          </span>
        </div>

        <div className="flex justify-center mb-2">
          <Sun className="w-9 h-9 text-[#f6c85f]" />
        </div>

        <h2 className="text-xl font-black text-[#ffdf91] tracking-wide">{greeting}</h2>
        <div className="text-[11px] font-black text-[#35df91] tracking-widest mt-1 mb-3">
          ★ BEST OF THE DAY ★
        </div>

        <p className="text-xs text-[#cbd5e1] leading-relaxed font-semibold bg-[#080d18]/80 border border-[#2b3b5b] rounded-2xl p-3.5 mb-5">
          {wish}
        </p>

        <button
          onClick={onClose}
          className="w-full inline-flex items-center justify-center gap-2 py-3 rounded-2xl bg-gradient-to-r from-[#16a34a] to-[#059669] text-white font-black text-sm tracking-wider shadow-[0_6px_20px_rgba(22,163,74,0.4)] active:scale-95 transition"
        >
          <CheckCircle2 className="w-4 h-4" />
          SHURU KAREIN
        </button>
      </div>
    </div>
  );
}
